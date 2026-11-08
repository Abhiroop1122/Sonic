package app.runout.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import app.runout.model.Album
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface ScanState {
    data class Scanning(val progress: ScanProgress) : ScanState
    data class FindingCovers(val done: Int, val total: Int) : ScanState
}

/** The user's shelf from their folder, cached on the phone between launches. */
class LibraryRepository(
    private val context: Context,
    private val scanner: LibraryScanner,
    private val artwork: ArtworkRepository,
    private val scope: CoroutineScope,
    private val lookupCoversOnline: Boolean = false,
) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "library.json")
    private val coverPrefs = context.getSharedPreferences("cover-lookup", Context.MODE_PRIVATE)
    private val _coverLookupEnabled = MutableStateFlow(coverPrefs.getBoolean("enabled", lookupCoversOnline))
    val coverLookupEnabled: StateFlow<Boolean> = _coverLookupEnabled.asStateFlow()
    private val _coverLookupNoticePending = MutableStateFlow(run {
        if (!coverPrefs.contains("policy-initialized")) {
            val existing = prefs.contains(KEY_FOLDER) || listOf("library.json", "pressings.json", "received.json", "drops.json")
                .any { File(context.filesDir, it).exists() } ||
                context.getSharedPreferences("soulseek-setup", Context.MODE_PRIVATE).getBoolean("onboarded", false)
            coverPrefs.edit(commit = true) { putBoolean("policy-initialized", true); putBoolean("notice-pending", existing) }
        }
        coverPrefs.getBoolean("notice-pending", false)
    })
    val coverLookupNoticePending: StateFlow<Boolean> = _coverLookupNoticePending.asStateFlow()

    fun setCoverLookupEnabled(enabled: Boolean) {
        coverPrefs.edit { putBoolean("enabled", enabled) }
        _coverLookupEnabled.value = enabled
        // Switched on: look for the missing covers now, not at the next scan. A scan under way
        // does its own pass when it finishes.
        if (enabled) synchronized(scanLock) {
            if (scanJob?.isActive != true && coverJob?.isActive != true) coverJob = scope.launch { findMissingCovers() }
        }
    }
    private var coverJob: Job? = null

    private val localAudioPrefs = context.getSharedPreferences(MP3_PREFS, Context.MODE_PRIVATE)
    private val _mp3Local = MutableStateFlow(localAudioPrefs.getBoolean(KEY_MP3, false))
    /** Settings › Flags › MP3 in your folder: MP3 files in the music folder are records too. Off by default. */
    val mp3Local: StateFlow<Boolean> = _mp3Local.asStateFlow()

    /** What the music folder admits right now: FLAC, and MP3 with [mp3Local] on. */
    val localPolicy: LocalAudioPolicy get() = LocalAudioPolicy.of(_mp3Local.value)

    /**
     * Switched on: the folder is read again, now with its MP3s. Switched off: the MP3 records leave
     * the shelf at once, and a fresh scan rebuilds it from the FLAC alone. The
     * caller ejects an MP3 record that is on the platter. Returns the scan, when one started.
     */
    fun setMp3Local(on: Boolean): Job? {
        if (_mp3Local.value == on) return null
        localAudioPrefs.edit(commit = true) { putBoolean(KEY_MP3, on) }
        _mp3Local.value = on
        return if (on) {
            rescan()
        } else {
            // The saved index follows when the scan finishes; until then a restart filters it too.
            synchronized(scanLock) {
                scanJob?.cancel()
                _albums.value = withoutMp3(_albums.value)
            }
            repair()
        }
    }

    /** The shelf the current setting allows: with MP3 off, no MP3 track (and no record left without tracks). */
    private fun allowed(list: List<Album>): List<Album> = if (_mp3Local.value) list else withoutMp3(list)

    fun dismissCoverLookupNotice() {
        coverPrefs.edit { putBoolean("notice-pending", false) }
        _coverLookupNoticePending.value = false
    }

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    private val _folder = MutableStateFlow(prefs.getString(KEY_FOLDER, null)?.let(Uri::parse))
    val folder: StateFlow<Uri?> = _folder.asStateFlow()

    private val _scan = MutableStateFlow<ScanState?>(null)
    val scan: StateFlow<ScanState?> = _scan.asStateFlow()

    private val _report = MutableStateFlow<ScanReport?>(null)
    val report: StateFlow<ScanReport?> = _report.asStateFlow()

    private var scanJob: Job? = null
    private val scanLock = Any()

    init {
        scope.launch {
            val cached = withContext(Dispatchers.IO) { AlbumJson.validated(context, allowed(AlbumJson.readList(cacheFile)), localPolicy) }
            if (_albums.value.isEmpty()) _albums.value = cached
            // Cached shelf but a folder that may have changed: refresh quietly.
            val tree = _folder.value
            if (tree != null && cached.isEmpty()) refreshQuietly(tree)
        }
    }

    /**
     * Uses [uri] as the music folder, keeping read access to it across restarts. Picking the same
     * folder again is a plain rescan that keeps the shelf until the folder has been listed.
     *
     * A different folder replaces the shelf: the old folder's records leave the crate at once
     * (their files are no longer ours to read), the saved index is emptied so a restart mid-scan
     * reads the new folder instead of showing the old one, and a partial scan never mixes old
     * records back in. The old folder's read grant is released unless [keepGrant] says another
     * part of the app (the downloads or shared folder) still uses that same folder.
     */
    fun setFolder(uri: Uri, keepGrant: (Uri) -> Boolean = { false }): Job? {
        // Read to play; write too when Android granted it, so Settings › Storage can delete a record.
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.recoverCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val old = _folder.value
        prefs.edit().putString(KEY_FOLDER, uri.toString()).apply()
        _folder.value = uri
        if (old == null || old == uri) return rescan()
        return synchronized(scanLock) {
            // Stop reading the old folder before giving up access to it.
            scanJob?.cancel()
            if (!keepGrant(old)) runCatching {
                context.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }.recoverCatching {
                context.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            _albums.value = emptyList()
            startScan(uri, fresh = false, replace = true)
        }
    }

    /**
     * Rebuilds the shelf from the folder alone: forgets the saved index, so albums that are gone
     * or broken drop off, and the report lists every file that couldn't be read and why. Music
     * files are only read, never changed.
     */
    fun repair(): Job? = rescan(fresh = true)

    fun rescan(fresh: Boolean = false): Job? {
        val tree = _folder.value ?: return null
        return synchronized(scanLock) {
            scanJob?.cancel()
            startScan(tree, fresh)
        }
    }

    /**
     * The first scan of a saved folder, started quietly. It never cancels a scan that is already
     * running, and never starts a second one beside it: a scan started since startup owns the
     * shelf, and cancelling it would leave the shelf empty — the "albums vanish" phone bug.
     */
    private fun refreshQuietly(tree: Uri): Job? = synchronized(scanLock) {
        if (scanJob?.isActive == true) null else startScan(tree, fresh = false)
    }

    /** [replace]: the folder changed, so nothing from the shelf before belongs on it any more. */
    private fun startScan(tree: Uri, fresh: Boolean, replace: Boolean = false): Job {
        // LAZY so that [scanJob] is set before the body can run: a scan that starts late must
        // already be the live one when another scan asks whether anything is running.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            _scan.value = ScanState.Scanning(ScanProgress(0, 0))
            _report.value = null
            // An older Sonic's covers in this folder's thumbnails/ stay out of the photo gallery.
            withContext(Dispatchers.IO) { runCatching { GalleryPrivacy.hideLeftovers(context, androidx.documentfile.provider.DocumentFile.fromTreeUri(context, tree)) } }
            val mp3 = _mp3Local.value
            // With MP3 off, no MP3 record from before survives a partial scan or a failed one.
            val previous = if (replace) emptyList() else if (mp3) _albums.value else withoutMp3(_albums.value)
            if (fresh || replace) _albums.value = emptyList()
            if (replace) withContext(Dispatchers.IO) { AlbumJson.writeList(cacheFile, emptyList()) }
            val found = mutableListOf<Album>()
            val report = try {
                scanner.scan(
                    treeUri = tree,
                    mp3 = mp3,
                    onProgress = { p -> _scan.value = ScanState.Scanning(p) },
                    onAlbum = { s ->
                        val album = withContext(Dispatchers.IO) {
                            val cover = artwork.cachedFile(s.album.id)
                                ?: s.embeddedArt?.let { artwork.saveBytes(s.album.id, it) }
                                ?: s.folderImage?.let { artwork.copyFromUri(s.album.id, it) }
                            s.album.withCover(cover)
                        }
                        found += album
                        // Show each album the moment it's read.
                        _albums.update { list -> sortShelf(list.filterNot { it.id == album.id } + album) }
                    },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ScanReport(0, 0, 0, found.size, emptyMap(), error = e.message ?: e.javaClass.simpleName)
            }
            _report.value = report
            if (report.error != null && found.isEmpty()) {
                // Nothing was listed: keep the shelf rather than empty it.
                _albums.value = previous
                _scan.value = null
                return@launch
            }
            // A partial scan (some files unreadable, or the listing broke off) can't prove that the
            // albums it didn't see were removed, so keep them next to everything it did read.
            val partial = report.error != null || report.unreadable > 0
            _albums.value = sortShelf(if (partial && !fresh) mergePartial(previous, found) else found)
            withContext(Dispatchers.IO) { AlbumJson.writeList(cacheFile, _albums.value) }

            findMissingCovers()
            _scan.value = null
        }
        scanJob = job
        job.start()
        return job
    }

    /** With online covers on: MusicBrainz and the Cover Art Archive for each album still without one. */
    private suspend fun findMissingCovers() {
        if (!coverLookupEnabled.value) return
        val missing = _albums.value.filter { it.coverPath == null }
        missing.forEachIndexed { i, album ->
            if (!coverLookupEnabled.value) return@forEachIndexed
            _scan.value = ScanState.FindingCovers(i, missing.size)
            val file = artwork.lookupOnline(album.id, album.artist, album.title)
            if (file != null) {
                val updated = withContext(Dispatchers.IO) { album.withCover(file) }
                _albums.update { list -> list.map { if (it.id == album.id) updated else it } }
            }
        }
        if (_scan.value is ScanState.FindingCovers) _scan.value = null
        if (missing.isNotEmpty()) withContext(Dispatchers.IO) { AlbumJson.writeList(cacheFile, _albums.value) }
    }

    private fun Album.withCover(file: File?): Album =
        if (file == null) this else copy(coverPath = file.absolutePath, tint = ArtworkRepository.averageColor(file))

    companion object {
        private const val KEY_FOLDER = "folder"
        /** Where Settings › Flags › MP3 in your folder is kept. */
        const val MP3_PREFS = "local-audio"
        const val KEY_MP3 = "mp3"

        /**
         * A partial scan's shelf: everything it read, plus whatever it couldn't see from before.
         * An album read only in part keeps its earlier tracks next to the ones read now.
         */
        fun mergePartial(previous: List<Album>, found: List<Album>): List<Album> {
            val byId = found.associateBy { it.id }
            val merged = found.map { now ->
                val before = previous.firstOrNull { it.id == now.id } ?: return@map now
                val missing = before.tracks.filter { old -> now.tracks.none { it.id == old.id } }
                if (missing.isEmpty()) now
                else now.copy(tracks = (now.tracks + missing).sortedWith(compareBy({ it.discNumber }, { it.trackNumber }, { it.fileName })))
            }
            return merged + previous.filter { it.id !in byId }
        }

        /** Folder records without their MP3 tracks; a record that was only MP3 leaves. */
        fun withoutMp3(list: List<Album>): List<Album> = list.mapNotNull { album ->
            if (album.tracks.none(LocalAudioPolicy::isMp3Track)) album
            else album.tracks.filterNot(LocalAudioPolicy::isMp3Track).takeIf { it.isNotEmpty() }?.let { album.copy(tracks = it) }
        }

        fun sortShelf(list: List<Album>): List<Album> =
            list.sortedWith(compareBy({ it.artist.lowercase() }, { it.year ?: 0 }, { it.title.lowercase() }))
    }
}

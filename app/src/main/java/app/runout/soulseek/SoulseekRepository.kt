package app.runout.soulseek

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import androidx.room.withTransaction
import app.runout.discovery.folderNames
import app.runout.library.AlbumJson
import app.runout.library.ArtworkRepository
import app.runout.library.FlacPolicy
import app.runout.model.Album
import app.runout.model.AudioQuality
import app.runout.model.Source
import app.runout.model.Track
import app.runout.sharing.VinylPackage
import app.runout.social.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Wait before continuing a download Android interrupted, and how often to try in a row. */
private const val AUTO_RESUME_DELAY_MS = 2_000L
private const val AUTO_RESUME_LIMIT = 3
/** How long one search for another copy of a failed file listens for answers. */
private const val SEEK_TIMEOUT_MS = 20_000L
/** A search no one has answered after this long is widened (see [SearchTerms.variants]). */
private const val WIDEN_AFTER_MS = 6_000L
/** How many times a search for another copy starts over after a search of yours cut it short. */
private const val SEEK_ATTEMPTS = 3
/** Said of a failed download once no one else turned out to share the identical file. */
private const val NO_COPY = " No one else shares this exact file right now."
/** A download's byte count reaches the database at most this often; its state changes at once. */
private const val PROGRESS_WRITE_MS = 1_000L
private val PROGRESS_STATES = setOf("QUEUED", "DOWNLOADING")
private fun nowMs() = System.nanoTime() / 1_000_000

class SoulseekRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val transportFactory: ((String) -> Int) -> SoulseekTransport = { NativeTransport(it) },
    database: SoulseekDatabase? = null,
    private val artwork: ArtworkRepository? = null,
    private val retryDelaysMs: List<Long> = SoulseekPolicy.RETRY_DELAYS_MS,
    private val seekTimeoutMs: Long = SEEK_TIMEOUT_MS,
    /** Settings › find missing covers online: off unless you opt in. */
    private val coverLookup: () -> Boolean = { false },
) {
    private val db = database ?: Room.databaseBuilder(context.applicationContext, SoulseekDatabase::class.java, "soulseek.db").build()
    private val dao = db.soulseek()
    private val prefs = context.getSharedPreferences("soulseek", Context.MODE_PRIVATE)
    val preferences = SoulseekPreferences(context)
    private val accountMutex = Mutex()
    private val publicationMutex = Mutex()
    private val searchMutex = Mutex()
    private val archiveJobs = ConcurrentHashMap<String, Job>()
    private var ownerJob: Job? = null
    private var observedOwner: String? = null
    private val connection = SoulseekConnectionManager(context, scope, { transportFactory(::openSharedFile) },
        { backgroundConsented() || hasActiveTransfers() }, ::handleEvent, { _error.value = it }, busy = ::nativeBusy,
        listenPort = { preferences.listenPort() })
    private val ports = PortMapper(context, scope)
    /** Whether the router forwards Sonic's port, so other people can reach this phone (Settings › Soulseek). */
    val reachability: StateFlow<PortState> get() = _reachability
    /** Our address as the Soulseek server sees it, for telling an open router from one behind another (CGNAT). */
    private val publicIp = MutableStateFlow<String?>(null)
    private val _reachability = combine(ports.state, publicIp) { state, ip -> PortMapper.verdict(state, ip) }
        .stateIn(scope, SharingStarted.Eagerly, PortState.Off)
    @Volatile private var boundPort = 0
    private val generation get() = connection.generation
    private val activeTransfers = ConcurrentHashMap.newKeySet<String>()
    private val publicationJobs = ConcurrentHashMap<String, Job>()
    private data class BrowseRequest(val username: String, val directory: String)
    private val pendingBrowse = ConcurrentHashMap<String, BrowseRequest>()
    private val shares = ConcurrentHashMap<String, SharedFile>()
    private val shareRevision = java.util.concurrent.atomic.AtomicLong()
    private var searchId: String? = null
    /** Searches for another copy of a failed file, by search id; one at a time, never over yours. */
    private class RepairSearch {
        val results: MutableList<SoulseekSearchResult> = java.util.Collections.synchronizedList(mutableListOf())
        /** Completes with whether the search was cut short. */
        val done = CompletableDeferred<Boolean>()
    }
    private val repairSearches = ConcurrentHashMap<String, RepairSearch>()
    private val seekMutex = Mutex()
    private val seekJobs = ConcurrentHashMap<String, Job>()
    /** One waiting retry per download: coming back to the app or signing in again doesn't stack them. */
    private val retryJobs = ConcurrentHashMap<String, Job>()
    /** When each download's progress was last written, and the newest progress still held back. */
    private val progressWritten = ConcurrentHashMap<String, Long>()
    private val heldProgress = ConcurrentHashMap<String, TransferRow>()
    val session = connection.session
    private val _searchResults = MutableStateFlow<List<SoulseekSearchResult>>(emptyList())
    val searchResults = _searchResults.asStateFlow()
    private val _searchStatus = MutableStateFlow(SoulseekSearchStatus())
    val searchStatus = _searchStatus.asStateFlow()
    private val searchIndex = LinkedHashMap<String, SoulseekSearchResult>()
    private val _folderPreview = MutableStateFlow<SoulseekFolderPreview?>(null)
    val folderPreview = _folderPreview.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching = _searching.asStateFlow()
    private val _transfers = MutableStateFlow<List<SoulseekTransfer>>(emptyList())
    val transfers = _transfers.asStateFlow()
    private val _uploads = MutableStateFlow<List<SoulseekUpload>>(emptyList())
    private val _totals = MutableStateFlow(TransferTotals())
    /** Uploaded and downloaded so far by the signed-in account (Settings › Soulseek). */
    val totals: StateFlow<TransferTotals> = _totals.asStateFlow()
    private val _shelf = MutableStateFlow<SoulseekShelf?>(null)
    /** The friend's shelf open in Friends, while it loads and after. */
    val shelf: StateFlow<SoulseekShelf?> = _shelf.asStateFlow()
    private val _shareOffer = MutableStateFlow(false)
    /**
     * The first album has come in and nothing is shared yet: Sonic asks, once, whether to share the
     * download folder. Peers treat people who share nothing as leechers; nothing is shared without a yes.
     */
    val shareOffer: StateFlow<Boolean> = _shareOffer.asStateFlow()
    val uploads = _uploads.asStateFlow()
    private val _contacts = MutableStateFlow<List<SoulseekContact>>(emptyList())
    val contacts = _contacts.asStateFlow()
    private val _conversations = MutableStateFlow<List<SoulseekConversation>>(emptyList())
    val conversations = _conversations.asStateFlow()
    private val _downloadAlbums = MutableStateFlow<List<Album>>(emptyList())
    val downloadAlbums = _downloadAlbums.asStateFlow()
    private val _destination = MutableStateFlow(prefs.getString("destination", null)?.let(Uri::parse))
    val destination = _destination.asStateFlow()
    private val storage = DownloadStorage(context) { destination.value }
    private val _sharedFolder = MutableStateFlow<Uri?>(null)
    val sharedFolder = _sharedFolder.asStateFlow()
    // Actual runtime activity is distinct from each account's saved consent.
    private val _sharing = MutableStateFlow(false)
    val sharing = _sharing.asStateFlow()
    private val _stayOnline = MutableStateFlow(false)
    val stayOnline = _stayOnline.asStateFlow()
    /** On Wi-Fi or Ethernet right now (assumed until Android says otherwise). */
    @Volatile private var onWifi = true
    /** Sharing is on, but held back because Wi-Fi only is on and the phone is on mobile data. */
    private val _waitingForWifi = MutableStateFlow(false)
    val waitingForWifi = _waitingForWifi.asStateFlow()
    val savedUsername = connection.savedUsername
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val initialized = scope.async(Dispatchers.IO) {
        dao.pauseInterrupted("Interrupted. Sign in and resume to continue.")
        cleanupBrokenDownloads()
        // Folders from before 1.2.0 have covers in thumbnails/ the photo gallery can see.
        runCatching { storage.hideExistingThumbnails() }
        dao.offlineContacts()
        dao.failInterruptedMessages()
        connection.initialize()
    }

    init {
        SoulseekRuntime.repository = this
        scope.launch(Dispatchers.IO) {
            initialized.await()
            dao.albums().collectLatest { rows ->
                _downloadAlbums.value = rows.mapNotNull { runCatching { FlacPolicy.filterAlbum(context, AlbumJson.fromJson(JSONObject(it.json))) }.getOrNull() }
            }
        }
    }

    private fun work(block: suspend () -> Unit): Job = scope.launch(Dispatchers.IO) {
        try { initialized.await(); block() } catch (e: CancellationException) { throw e }
        catch (e: Throwable) { _error.value = friendly(e) }
    }
    fun canDownload(): Boolean = session.value.signedIn
    fun requireSignedIn() { check(canDownload()) { "Sign in with your Soulseek account to download and keep music." } }
    fun clearError() { _error.value = null }
    /**
     * Drops a "sign in" or "offline" error once the session is back, so the prompt doesn't stay up
     * after you've signed in. Any other error is still news and stays.
     */
    private fun clearSessionError() { _error.update { if (it != null && isSessionError(it)) null else it } }

    fun signIn(username: String, password: String, remember: Boolean): Job = work {
        require(SoulseekPolicy.validUsername(username)) { "Use a Soulseek username of 1–30 printable ASCII characters, without surrounding spaces." }
        require(password.isNotEmpty() && password.length <= 128 && password.none { it == '\u0000' }) { "Enter a password of 1–128 characters." }
        accountMutex.withLock {
            connection.disconnect(SoulseekDisconnectReason.MANUAL)
            clearSessionWork("Account changed", true)
            connection.signIn(username, password, remember)
            observeOwner(username)
            _error.value = null
        }
    }
    fun signInSaved(): Job = work {
        accountMutex.withLock { connection.disconnect(SoulseekDisconnectReason.MANUAL); clearSessionWork("Account changed", true); connection.signInSaved() }
        clearSessionError()
    }
    fun reconnect(): Job = work { connection.reconnect(); clearSessionError() }
    private fun observeOwner(username: String) {
        if (observedOwner == username) return
        observedOwner = username
        ownerJob?.cancel()
        ownerJob = scope.launch(Dispatchers.IO) {
            launch { dao.transfers(username).collectLatest { rows -> _transfers.value = rows.map { it.model() }; updateServices() } }
            launch { dao.contacts(username).collectLatest { _contacts.value = it.map { c -> SoulseekContact(c.username, c.online) } } }
            launch { dao.messages(username).collectLatest { rows -> _conversations.value = rows.groupBy { it.username }.map { (name, messages) -> SoulseekConversation(name, messages.map { it.model() }, messages.count { !it.outgoing && !it.read }) }.sortedByDescending { it.messages.lastOrNull()?.timestamp ?: 0 } } }
        }
    }
    fun logout(): Job = work {
        accountMutex.withLock {
            connection.disconnect(SoulseekDisconnectReason.MANUAL)
            boundPort = 0; ports.close(); publicIp.value = null
            clearSessionWork("Signed out. Sign in and resume to continue.", true)
            connection.disconnect(SoulseekDisconnectReason.MANUAL, clearAccount = true)
        }
    }
    private suspend fun clearSessionWork(reason: String, clearAccount: Boolean) {
        val owner = session.value.username
        publicationJobs.values.forEach { it.cancel() }; publicationJobs.clear()
        archiveJobs.values.forEach { it.cancel() }; archiveJobs.clear()
        // Seeks pick up again at the next sign-in; their rows keep saying what they were doing.
        seekJobs.values.forEach { it.cancel() }; seekJobs.clear()
        repairSearches.values.forEach { it.done.complete(true) }
        activeTransfers.clear(); _uploads.value = emptyList(); pendingBrowse.clear(); _folderPreview.value = null; _shelf.value = null
        searchMutex.withLock {
            searchId = null; _searching.value = false
            if (_searchStatus.value.phase == SearchPhase.SEARCHING) _searchStatus.update { it.copy(phase = SearchPhase.INTERRUPTED, error = reason) }
        }
        if (owner != null) { dao.pauseOwner(owner, reason); dao.failPendingMessages(owner) }
        dao.offlineContacts()
        shareRevision.incrementAndGet(); _sharing.value = false; shares.clear()
        val preserveBackground = !clearAccount && session.value.state == SoulseekConnection.RECONNECTING && _stayOnline.value && backgroundConsented()
        if (!preserveBackground) _stayOnline.value = false
        if (clearAccount) {
            ownerJob?.cancel(); observedOwner = null
            _contacts.value = emptyList(); _conversations.value = emptyList(); _transfers.value = emptyList()
            searchMutex.withLock { searchIndex.clear(); _searchResults.value = emptyList(); _searchStatus.value = SoulseekSearchStatus() }
        }
        stopService(SoulseekTransferService::class.java)
        if (!preserveBackground) stopService(SoulseekOnlineService::class.java)
    }
    private suspend fun command(op: String, body: JSONObject.() -> Unit = {}) = connection.command(op, body)

    fun search(query: String): Job = work { searchMutex.withLock {
        requireSignedIn()
        val text = query.trim().replace(Regex("\\s+"), " ")
        require(text.toByteArray(Charsets.UTF_8).size in 2..256) { "Search needs 2–256 bytes. Try an artist, album or song." }
        searchId?.let { old -> command("cancel_search") { put("id", old) } }
        val id = UUID.randomUUID().toString(); searchId = id
        searchIndex.clear(); _searchResults.value = emptyList(); _searching.value = true
        _searchStatus.value = SoulseekSearchStatus(id, text, SearchPhase.SEARCHING)
        try {
            command("search") {
                put("id", id); put("query", text); put("timeoutMs", 30000)
                put("queries", JSONArray(listOf(SearchTerms.network(text))))
                put("variants", JSONArray(SearchTerms.variants(text))); put("widenAfterMs", WIDEN_AFTER_MS)
            }
        }
        catch (e: Exception) { _searching.value = false; _searchStatus.update { it.copy(phase = SearchPhase.FAILED, error = friendly(e)) }; throw e }
    } }
    /** Dig left behind: the search stopped and its results gone, so Dig opens on its own page. */
    fun clearSearch(): Job = work { searchMutex.withLock {
        searchId?.let { id -> runCatching { command("cancel_search") { put("id", id) } }.onFailure { if (it is CancellationException) throw it } }
        searchId = null; _searching.value = false
        searchIndex.clear(); _searchResults.value = emptyList(); _searchStatus.value = SoulseekSearchStatus()
    } }
    fun stopSearch(): Job = work { searchMutex.withLock {
        searchId?.let { id -> command("cancel_search") { put("id", id) } }
        searchId = null; _searching.value = false
        _searchStatus.update { it.copy(phase = SearchPhase.CANCELLED) }
    } }
    fun removeAlbum(id: String): Job = work { dao.removeAlbum(id) }
    /** The once-only question answered: yes shares the download folder (FLAC only, Wi-Fi only unless changed). */
    fun answerShareOffer(share: Boolean): Job = work {
        val owner = session.value.username ?: return@work
        preferences.setShareAsked(owner); _shareOffer.value = false
        if (!share) return@work
        val folder = _destination.value ?: return@work
        setSharedFolder(folder).join()
        setSharing(true).join()
    }
    fun download(result: SoulseekSearchResult): Job = work { enqueue(result) }
    /**
     * Just these tracks of a folder, into [albumId]'s folder and record when it's an album Runout
     * downloaded (so a broken or missing song joins the rest), else into the folder's own album.
     */
    fun downloadTracks(files: List<SoulseekSearchResult>, albumId: String?): Job = work {
        requireSignedIn(); storage.requireDestination()
        files.forEach { enqueue(it, albumId) }
    }
    fun downloadFolder(result: SoulseekSearchResult): Job = previewFolder(result)
    fun previewFolder(result: SoulseekSearchResult): Job = previewFolder(result.username, result.directory)
    /** Lists [directory] on [username]'s share (`/` separators), as the folder a record card names. */
    fun previewFolder(username: String, directory: String): Job = work {
        requireSignedIn()
        val id = UUID.randomUUID().toString(); pendingBrowse.clear(); pendingBrowse[id] = BrowseRequest(username, directory)
        _folderPreview.value = SoulseekFolderPreview(id, username, directory)
        command("browse") { put("id", id); put("username", username); put("directory", directory) }
    }
    fun closeFolderPreview() { pendingBrowse.clear(); _folderPreview.value = null }
    /** Everything [username] shares, FLAC only, as folders: Friends › a friend › their shelf. */
    fun browseShelf(username: String): Job = work {
        requireSignedIn()
        val id = UUID.randomUUID().toString(); pendingBrowse[id] = BrowseRequest(username, "")
        _shelf.value = SoulseekShelf(id, username)
        command("browse") { put("id", id); put("username", username); put("directory", "") }
    }
    fun closeShelf() { _shelf.value?.let { pendingBrowse.remove(it.requestId) }; _shelf.value = null }
    fun confirmFolderDownload(): Job = work {
        val preview = folderPreview.value ?: return@work
        check(!preview.loading && preview.error == null) { "Wait for the complete folder listing first." }
        requireSignedIn(); storage.requireDestination()
        preview.files.forEach { enqueue(it) }
        _folderPreview.value = null
    }
    private suspend fun enqueue(result: SoulseekSearchResult, albumId: String? = null) {
        requireSignedIn(); storage.requireDestination()
        require(FlacPolicy.isFlacName(result.fileName) && result.size > 0 && result.size <= 1L shl 40) { "Only FLAC music files can be downloaded." }
        val owner = session.value.username!!
        val id = SoulseekPolicy.accountKey(owner, result.id)
        val previous = dao.transfer(id)
        // Done and still in the folder: nothing to do. Done but the file has gone: fetch it again.
        val redo = previous?.state == SoulseekTransferState.COMPLETED.name
        if (redo && previous?.let(::stillPublished) == true) return
        if (previous?.state in listOf("DOWNLOADING", "QUEUED", "VALIDATING", "PUBLISHING")) return
        val row = previous?.copy(
            state = "QUEUED", error = null, updatedAt = System.currentTimeMillis(), albumId = albumId ?: previous.albumId,
            failure = null, recovery = null, retryAt = null, retries = 0,
            bytes = if (redo) 0 else previous.bytes, stagePath = if (redo) null else previous.stagePath, publishedUri = if (redo) null else previous.publishedUri,
        ) ?: TransferRow(id, owner, result.username, result.path, result.size, albumId = albumId)
        dao.putTransfer(row)
        try { startService(SoulseekTransferService::class.java); startTransfer(row) }
        catch (e: Throwable) { dao.putTransfer(row.copy(state = "PAUSED", error = friendly(e))); throw e }
    }
    private suspend fun startTransfer(row: TransferRow) {
        requireSignedIn(); check(row.owner == session.value.username) { "Sign in with the account that started this download." }
        if (preferences.turtle() && activeTransfers.isNotEmpty() && row.id !in activeTransfers) {
            dao.putTransfer(row.copy(state = "QUEUED", error = null))
            return
        }
        if (activeTransfers.contains(row.id)) command("resume") { put("id", row.id) }
        else {
            command("download") { put("id", row.id); put("username", row.username); put("path", row.path); put("size", row.size); put("localDirectory", storage.stagingDirectory(row.id).absolutePath) }
            activeTransfers.add(row.id)
        }
    }
    private suspend fun pumpQueue() {
        if (!preferences.turtle() || activeTransfers.isNotEmpty()) return
        val owner = session.value.username ?: return
        val next = dao.resumable(owner).firstOrNull { it.state == "QUEUED" && it.error == null } ?: return
        startTransfer(next)
    }
    /**
     * Stops every download that's moving or about to: queued or downloading, and also the paused
     * ones Sonic would start again by itself (a retry or a search for another copy waiting, or a
     * resume after Android interrupted it). One that can't be paused doesn't stop the rest.
     */
    fun pauseAll(): Job = work {
        requireSignedIn()
        val ids = _transfers.value.filter {
            it.state == SoulseekTransferState.QUEUED || it.state == SoulseekTransferState.DOWNLOADING ||
                (it.state == SoulseekTransferState.PAUSED && (it.recovery != null || interruptedIds.containsKey(it.id)))
        }.map { it.id }
        ids.forEach { id -> runCatching { pauseOne(id) }.onFailure { if (it is CancellationException) throw it } }
    }
    /** Starts every paused or queued Soulseek download again; one that can't start doesn't hold up the rest. */
    fun resumeAll(): Job = work {
        requireSignedIn()
        var failure: Throwable? = null
        dao.resumable(session.value.username!!).filter { it.provider == "Soulseek" }.forEach { row ->
            runCatching { resumeOne(row.id) }.onFailure { if (it is CancellationException) throw it; failure = failure ?: it }
        }
        failure?.let { throw it }
    }
    fun setTurtle(enabled: Boolean) { preferences.setTurtle(enabled) }
    fun pause(id: String): Job = work { pauseOne(id); pumpQueue() }
    private suspend fun pauseOne(id: String) {
        interruptedIds.remove(id)
        val row = ownTransfer(id)
        retryJobs.remove(id)?.cancel()
        seekJobs.remove(id)?.cancel()
        publicationJobs.remove(id)?.cancel()
        archiveJobs.remove(id)?.cancel()
        if (activeTransfers.contains(id)) command("pause") { put("id", id) }
        activeTransfers.remove(id)
        dao.putTransfer(row.copy(state = "PAUSED", speed = 0.0, error = null, recovery = null, retryAt = null))
    }
    fun resume(id: String): Job = work { resumeOne(id) }
    private suspend fun resumeOne(id: String) {
        requireSignedIn(); storage.requireDestination(); val row = ownTransfer(id)
        if (row.state == "COMPLETED") return
        check(row.provider == "Soulseek") { "Retry this album from Friday Drops to continue an Internet Archive download." }
        startService(SoulseekTransferService::class.java)
        // Asked again now, by you or by its timer: nothing is waiting any more.
        val ready = row.copy(recovery = null, retryAt = null)
        if (row.stagePath?.let { File(it).exists() && File(it).length() == row.size } == true) { dao.putTransfer(ready); publishAsync(ready) }
        else { val queued = ready.copy(state = "QUEUED", error = null); dao.putTransfer(queued); startTransfer(queued) }
    }
    /** Your retry: the same peer again, with the full round of automatic retries after it. */
    fun retry(id: String): Job = work {
        val row = ownTransfer(id)
        dao.putTransfer(row.copy(retries = 0, recovery = null, retryAt = null))
        resumeOne(id)
    }
    /** Look for this failed file again from anyone sharing the identical copy. */
    fun findAnotherCopy(id: String): Job = work { requireSignedIn(); seekAgain(ownTransfer(id)) }
    /** A record's failed tracks, each looked for again (a folder problem is simply retried). */
    fun fetchMissing(albumId: String): Job = work {
        requireSignedIn(); storage.requireDestination()
        dao.failed(session.value.username!!).filter { it.effectiveAlbumId() == albumId }.forEach { seekAgain(it) }
    }
    private suspend fun seekAgain(row: TransferRow) {
        if (row.provider != "Soulseek" || row.failure == FailureKind.FOLDER.name) { resumeOne(row.id); return }
        dao.putTransfer(row.copy(state = "PAUSED", recovery = Recovery.SEEK.name, retryAt = null, error = row.error?.removeSuffix(NO_COPY)))
        seekAsync(row.id)
    }
    fun cancel(id: String): Job = work {
        interruptedIds.remove(id)
        val row = ownTransfer(id)
        seekJobs.remove(id)?.cancel()
        publicationJobs.remove(id)?.cancel()
        archiveJobs.remove(id)?.cancel()
        if (activeTransfers.contains(id)) command("cancel") { put("id", id) }
        activeTransfers.remove(id)
        dao.putTransfer(row.copy(state = "CANCELLED", speed = 0.0, error = null))
        storage.stagingDirectory(id).deleteRecursively()
        // Cancellation never removes music already published to the user's folder.
    }
    /** Off the list: stopped if it's running, its partial file deleted. Music already in your folder stays. */
    fun remove(id: String): Job = work {
        interruptedIds.remove(id)
        val row = ownTransfer(id)
        seekJobs.remove(id)?.cancel()
        publicationJobs.remove(id)?.cancel()
        archiveJobs.remove(id)?.cancel()
        if (activeTransfers.contains(id)) command("cancel") { put("id", id) }
        activeTransfers.remove(id)
        dao.deleteTransfer(row.id)
        storage.stagingDirectory(id).deleteRecursively()
        pumpQueue()
    }
    /** Every failed or cancelled download off the list at once. */
    fun removeFailed(): Job = work {
        val owner = session.value.username ?: return@work
        _transfers.value.filter { it.state == SoulseekTransferState.FAILED || it.state == SoulseekTransferState.CANCELLED }.forEach { t ->
            val row = dao.transfer(t.id)?.takeIf { it.owner == owner } ?: return@forEach
            seekJobs.remove(row.id)?.cancel()
            dao.deleteTransfer(row.id)
            storage.stagingDirectory(row.id).deleteRecursively()
        }
    }
    /** A completed download whose file is still in the folder, whole. */
    private fun stillPublished(row: TransferRow): Boolean = runCatching {
        val file = row.publishedUri?.let { DocumentFile.fromSingleUri(context, Uri.parse(it)) } ?: return false
        file.exists() && file.length() == row.size
    }.getOrDefault(false)
    private suspend fun ownTransfer(id: String): TransferRow {
        val row = dao.transfer(id) ?: throw IllegalArgumentException("This download no longer exists.")
        check(row.owner == session.value.username) { "Sign in with the account that started this download." }
        return row
    }

    fun addContact(username: String): Job = work {
        requireSignedIn(); require(SoulseekPolicy.validUsername(username)) { "Enter an exact Soulseek username." }
        dao.addContact(ContactRow(session.value.username!!, username))
        command("contact") { put("username", username) }
    }
    fun openConversation(username: String): Job = work {
        requireSignedIn(); require(SoulseekPolicy.validUsername(username)) { "Enter an exact Soulseek username." }
        dao.addContact(ContactRow(session.value.username!!, username)); dao.markRead(session.value.username!!, username)
        command("contact") { put("username", username) }
    }
    fun markRead(username: String): Job = work { session.value.username?.let { dao.markRead(it, username) } }
    fun sendMessage(username: String, text: String, onPersisted: () -> Unit = {}): Job = work {
        requireSignedIn(); require(SoulseekPolicy.validUsername(username)) { "Enter an exact Soulseek username." }
        val body = text.trim(); require(body.isNotEmpty() && body.toByteArray(Charsets.UTF_8).size <= 4096) { "Messages must contain 1–4,096 bytes of text." }
        val owner = session.value.username!!; val id = UUID.randomUUID().toString()
        dao.addContact(ContactRow(owner, username))
        dao.insertMessage(MessageRow(id, owner, username, body, System.currentTimeMillis(), true, "PENDING", true))
        onPersisted()
        try { command("message") { put("username", username); put("text", body); put("clientId", id) } }
        catch (e: Throwable) { dao.messageState(id, "FAILED"); throw e }
    }

    fun setDestination(uri: Uri): Job = work {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        check(DocumentFile.fromTreeUri(context, uri)?.canWrite() == true) { "Choose a writable music folder." }
        prefs.edit().putString("destination", uri.toString()).apply(); _destination.value = uri
    }
    fun setSharedFolder(uri: Uri): Job = work {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        check(DocumentFile.fromTreeUri(context, uri)?.canRead() == true) { "Choose a readable folder to share." }
        shareRevision.incrementAndGet(); _sharing.value = false; shares.clear()
        session.value.username?.let { preferences.setSharing(it, false) }
        if (session.value.signedIn) command("shares") { put("files", JSONArray()) }
        session.value.username?.let { preferences.setSharedFolder(it,uri.toString()) }
        prefs.edit().putString("sharedFolder", uri.toString()).apply(); _sharedFolder.value = uri
    }
    fun setSharing(enabled: Boolean): Job {
        val revision = shareRevision.incrementAndGet()
        return work {
            val owner = session.value.username ?: error("Sign in before choosing a shared folder.")
            val epoch = generation
            if (shareRevision.get() != revision) return@work
            preferences.setSharing(owner, enabled)
            _waitingForWifi.value = false
            if (enabled && offWifi(owner)) { holdForWifi(); return@work }
            if (!enabled) { _sharing.value = false; shares.clear(); if (session.value.signedIn) command("shares") { put("files", JSONArray()) }; _uploads.value = emptyList(); updateServices(); return@work }
            requireSignedIn()
            val tree = sharedFolder.value?.let { DocumentFile.fromTreeUri(context, it) } ?: throw IllegalStateException("Choose a folder to share first.")
            check(tree.canRead() && tree.isDirectory) { "Sharing folder access was lost. Choose the folder again." }
            val manifest = JSONArray(); val indexed = mutableMapOf<String, SharedFile>()
            val visited = mutableSetOf<String>()
            suspend fun visit(dir: DocumentFile, prefix: String, depth: Int) {
                currentCoroutineContext().ensureActive()
                if (depth > 64 || !visited.add(dir.uri.toString())) return
                dir.listFiles().forEach { doc ->
                    val name = doc.name ?: return@forEach
                    val safe = if (doc.isDirectory) SoulseekPolicy.safeName(name) else SoulseekPolicy.safeFileName(name)
                    val virtual = if (prefix.isEmpty()) safe else prefix + "\\" + safe
                    if (doc.isDirectory) visit(doc, virtual, depth + 1)
                    else if (doc.isFile && FlacPolicy.isFlacName(name)) {
                        val info = FlacPolicy.read(context, doc.uri, name) ?: return@forEach
                        val key = SoulseekPolicy.digest(doc.uri.toString()); val size = doc.length()
                        if (size <= 0) return@forEach
                        indexed[key] = SharedFile(doc.uri, name, size)
                        manifest.put(JSONObject().put("key", key).put("path", "Music\\$virtual").put("size", size).apply {
                            put("durationSec", (info.durationMs / 1000).toInt()); info.sampleRate?.let { put("sampleRate", it) }; info.bits?.let { put("bitDepth", it) }
                        })
                    }
                }
            }
            visit(tree, "", 0)
            accountMutex.withLock {
                if (shareRevision.get() != revision || epoch != generation || session.value.username != owner || !session.value.connected) return@withLock
                shares.clear(); shares.putAll(indexed); _sharing.value = true
                try { command("shares") { put("files", manifest) } } catch (e: Throwable) { _sharing.value = false; shares.clear(); throw e }
            }
        }
    }
    /** Wi-Fi only is on and the phone is on mobile data. */
    private fun offWifi(owner: String) = preferences.wifiOnly(owner) && !onWifi
    /** Nothing new is offered while off Wi-Fi; an upload already running finishes. */
    private suspend fun holdForWifi() {
        shareRevision.incrementAndGet(); _sharing.value = false; shares.clear()
        _waitingForWifi.value = true
        if (session.value.signedIn) runCatching { command("shares") { put("files", JSONArray()) } }.onFailure { if (it is CancellationException) throw it }
    }
    /** After a network change or a change to Wi-Fi only: share or hold back to match. */
    private suspend fun applyWifiRule() {
        val owner = session.value.username ?: return
        if (!preferences.sharing(owner)) { _waitingForWifi.value = false; return }
        if (offWifi(owner)) { if (!_waitingForWifi.value) holdForWifi() }
        else if (_waitingForWifi.value) {
            _waitingForWifi.value = false
            if (session.value.connected && preferences.sharedFolder(owner) != null) setSharing(true)
        }
    }
    fun setWifiOnly(enabled: Boolean): Job = work {
        val owner = session.value.username ?: error("Sign in before choosing how to share.")
        preferences.setWifiOnly(owner, enabled)
        applyWifiRule()
    }
    /** How many people can download from you at once; applied now if connected, else at sign-in. */
    fun setUploadSlots(slots: Int): Job = work {
        val owner = session.value.username ?: error("Sign in before choosing an upload limit.")
        preferences.setUploadSlots(owner, slots)
        if (session.value.connected) applyUploadSlots(owner)
    }
    private suspend fun applyUploadSlots(owner: String) {
        runCatching { command("upload_slots") { put("slots", preferences.uploadSlots(owner)) } }
            .onFailure { if (it is CancellationException) throw it }
    }
    private data class SharedFile(val uri: Uri, val name: String, val size: Long)
    private fun openSharedFile(key: String): Int {
        if (!sharing.value || !session.value.signedIn) return -1
        val file = shares[key] ?: return -1
        return runCatching {
            check(FlacPolicy.read(context, file.uri, file.name) != null) { "Shared file is no longer valid FLAC" }
            val descriptor = context.contentResolver.openFileDescriptor(file.uri, "r") ?: return -1
            if (!sharing.value) { descriptor.close(); return -1 }
            val seekable = runCatching { android.system.Os.lseek(descriptor.fileDescriptor, 0, android.system.OsConstants.SEEK_SET); true }.getOrDefault(false)
            if (seekable && descriptor.statSize == file.size) descriptor.detachFd() else {
                descriptor.close()
                // Cloud-backed providers may supply pipes. Spool only this requested file;
                // native uploads need a seekable descriptor for resume offsets.
                val temporary = File.createTempFile("share-", ".flac", context.cacheDir)
                try {
                    context.contentResolver.openInputStream(file.uri)?.use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            var copied = 0L
                            while (true) {
                                check(sharing.value && session.value.signedIn)
                                val count = input.read(buffer); if (count < 0) break
                                copied += count; check(copied <= file.size)
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: error("Shared file is unavailable")
                    check(temporary.length() == file.size && FlacPolicy.isValid(temporary))
                    android.os.ParcelFileDescriptor.open(temporary, android.os.ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
                } finally { temporary.delete() }
            }
        }.getOrDefault(-1)
    }

    private fun backgroundConsented(): Boolean = session.value.username?.let { preferences.background(it) } == true && androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun setStayOnline(enabled: Boolean): Job = work {
        val owner = session.value.username ?: error("Sign in before choosing a connection mode.")
        if (enabled) { requireSignedIn(); startService(SoulseekOnlineService::class.java) }
        else stopService(SoulseekOnlineService::class.java)
        preferences.setBackground(owner, enabled); _stayOnline.value = enabled
        connection.updatePolicy()
    }
    fun onForegroundChanged(isForeground: Boolean): Job = work {
        foreground = isForeground
        connection.foregroundChanged(isForeground)
        // Back in the app: continue downloads Android interrupted while it was in the background.
        if (isForeground) { interruptedIds.keys.toList().forEach { id -> autoResume(id) }; recoverPending() }
    }

    @Volatile private var foreground = true
    /** Downloads paused by an interrupted read, with how many times each was resumed for it. */
    private val interruptedIds = ConcurrentHashMap<String, Int>()

    /** Android's battery setting "Background usage: off" (Android 9 and later). */
    fun backgroundRestricted(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 28 &&
            context.getSystemService(android.app.ActivityManager::class.java)?.isBackgroundRestricted == true

    private fun interruptionReason(): String =
        if (backgroundRestricted()) "Android paused this download: Background usage is off for Sonic. Allow it in the app's Battery settings, or keep Sonic open. It continues when you're back."
        else "Android interrupted this download. Your progress is kept, and it continues by itself."

    private fun scheduleAutoResume(id: String) {
        interruptedIds.putIfAbsent(id, 0)
        // Restricted apps can't do network work in the background; wait for the app to come back.
        if (!foreground || backgroundRestricted()) return
        scope.launch(Dispatchers.IO) { delay(AUTO_RESUME_DELAY_MS); if (foreground) autoResume(id) }
    }

    private suspend fun autoResume(id: String) {
        val tries = interruptedIds[id] ?: return
        val row = dao.transfer(id)
        if (row == null || row.state != "PAUSED" || tries >= AUTO_RESUME_LIMIT) { interruptedIds.remove(id); return }
        interruptedIds[id] = tries + 1
        runCatching { resumeOne(id) }.onFailure { if (it is CancellationException) throw it }
    }
    fun onExternalFlow(active: Boolean) { connection.externalFlow(active) }
    /** [newNetwork] is false when the same network only reported new capabilities. */
    fun onNetworkChanged(available: Boolean, wifi: Boolean = onWifi, newNetwork: Boolean = true): Job = work {
        val changed = wifi != onWifi
        onWifi = wifi
        connection.networkChanged(available, newNetwork)
        // Another network is another router (or none): ask again, or let go on mobile data.
        if ((changed || newNetwork) && boundPort > 0) ports.open(boundPort)
        if (changed) applyWifiRule()
    }
    /** Something waits on the native client's events: they are polled quickly while it does. */
    private fun nativeBusy(): Boolean = _searching.value || repairSearches.isNotEmpty() || pendingBrowse.isNotEmpty() ||
        activeTransfers.isNotEmpty() || _uploads.value.any { it.state == "uploading" || it.state == "queued" }
    private fun hasActiveTransfers() = archiveJobs.isNotEmpty() || transfers.value.any { it.state in listOf(SoulseekTransferState.QUEUED, SoulseekTransferState.DOWNLOADING, SoulseekTransferState.VALIDATING, SoulseekTransferState.PUBLISHING) } || uploads.value.any { it.state == "uploading" || it.state == "queued" }
    private fun startService(type: Class<*>) { ContextCompat.startForegroundService(context, Intent(context, type)) }
    private fun stopService(type: Class<*>) { context.stopService(Intent(context, type)) }
    private suspend fun updateServices() {
        if (!hasActiveTransfers()) stopService(SoulseekTransferService::class.java)
        connection.updatePolicy()
    }
    fun onServiceTimeout(): Job = work {
        clearSessionWork(SoulseekDisconnectReason.SERVICE_TIMEOUT.label, false)
        connection.disconnect(SoulseekDisconnectReason.SERVICE_TIMEOUT)
        _error.value = "Android stopped background activity. Your progress is saved. Open Sonic and reconnect."
    }
    fun disconnectFromNotification(): Job = work {
        clearSessionWork(SoulseekDisconnectReason.MANUAL.label, false)
        connection.disconnect(SoulseekDisconnectReason.MANUAL)
    }

    private suspend fun handleEvent(event: JSONObject, owner: String) {
        when (event.optString("type")) {
            "session" -> when (event.optString("state")) {
                "connected" -> {
                    // First, so an error this branch raises below survives.
                    clearSessionError()
                    boundPort = event.optInt("listenPort")
                    if (boundPort > 0) ports.open(boundPort)
                    observeOwner(owner)
                    _sharedFolder.value = preferences.sharedFolder(owner)?.let(Uri::parse)
                    _totals.value = preferences.totals(owner)
                    if (backgroundConsented()) {
                        try { startService(SoulseekOnlineService::class.java); _stayOnline.value = true }
                        catch (_: RuntimeException) { _error.value = "Background connection is unavailable. Open Account to check notification permission." }
                    }
                    _contacts.value.forEach { contact ->
                        runCatching { command("contact") { put("username", contact.username) } }
                            .onFailure {
                                if (it is CancellationException) throw it
                                if (app.runout.BuildConfig.DEBUG) android.util.Log.d("RunoutSession", "contact watch rejected")
                            }
                    }
                    applyUploadSlots(owner)
                    if (preferences.sharing(owner) && preferences.sharedFolder(owner) != null) setSharing(true)
                    recoverPending()
                }
                "error", "disconnected", "paused" -> {
                    boundPort = 0; ports.close(); publicIp.value = null
                    clearSessionWork(event.optString("reason", "Connection interrupted. Reconnect and resume."), false)
                }
            }
            "address" -> publicIp.value = event.optString("publicIp").takeIf { it.isNotBlank() }
            "search_result", "search_batch" -> {
                repairSearches[event.optString("id")]?.let { search ->
                    val files = event.optJSONArray("files") ?: JSONArray().put(event)
                    for (i in 0 until files.length()) {
                        val file = files.getJSONObject(i)
                        val result = parseResult(file, file.optString("username", event.optString("username")))
                        if (FlacPolicy.isFlacName(result.fileName) && result.size > 0 && search.results.size < SearchTerms.MAX_RESULTS) search.results += result
                    }
                    return
                }
                searchMutex.withLock { if (event.optString("id") == searchId) {
                val files = event.optJSONArray("files") ?: JSONArray().put(event)
                for (i in 0 until files.length()) {
                    val file = files.getJSONObject(i)
                    val result = parseResult(file, file.optString("username", event.optString("username")))
                    if (FlacPolicy.isFlacName(result.fileName) && result.size > 0 && searchIndex.size < SearchTerms.MAX_RESULTS) searchIndex.putIfAbsent(result.id, result)
                }
                _searchResults.value = searchIndex.values.toList()
                } }
            }
            "search_widened" -> searchMutex.withLock { if (event.optString("id") == searchId) _searchStatus.update { it.copy(widened = true) } }
            "search_done" -> {
                // What the network gave and what the door turned away, for diagnosing searches that come back thin.
                android.util.Log.i("SonicSearch", "done in ${event.optLong("elapsedMs")} ms: ${event.optInt("peerReplies")} replies, " +
                    "${event.optInt("rawFiles")} files, peers admitted ${event.optLong("peersAdmitted")}, refused ${event.optLong("peersRefused")}")
                repairSearches[event.optString("id")]?.done?.complete(event.optBoolean("cancelled")) ?: searchMutex.withLock { if (event.optString("id") == searchId) {
                    _searching.value = false
                    _searchStatus.update { it.copy(phase = if (event.optBoolean("cancelled")) SearchPhase.CANCELLED else SearchPhase.COMPLETE, limited = event.optBoolean("limited") || searchIndex.size >= SearchTerms.MAX_RESULTS, widened = it.widened || event.optBoolean("widened")) }
                } }
            }
            "browse_result" -> {
                val requested = pendingBrowse.remove(event.optString("id")) ?: return
                if (event.optString("username") != requested.username || event.optString("directory").replace('\\', '/') != requested.directory) return
                val files = event.optJSONArray("files") ?: JSONArray()
                if (requested.directory.isEmpty()) {
                    // A whole share: its FLAC files as folders, the way Dig shows a search.
                    val folders = (0 until files.length()).map { parseResult(files.getJSONObject(it), requested.username) }
                        .filter { FlacPolicy.isFlacName(it.fileName) && it.size > 0 }.distinctBy { it.id }
                        .groupBy { it.directory }
                        .map { (dir, tracks) -> ShelfFolder(dir, tracks.sortedBy { it.fileName.lowercase(java.util.Locale.ROOT) }) }
                        .sortedBy { it.directory.lowercase(java.util.Locale.ROOT) }
                    if (_shelf.value?.requestId == event.optString("id")) _shelf.value = SoulseekShelf(event.optString("id"), requested.username, folders, loading = false, total = event.optInt("total", -1))
                    return
                }
                val valid = (0 until files.length()).map { parseResult(files.getJSONObject(it), requested.username) }
                    .filter { it.directory == requested.directory && FlacPolicy.isFlacName(it.fileName) && it.size > 0 }.distinctBy { it.id }
                    .sortedWith(compareBy({ it.fileName.lowercase(java.util.Locale.ROOT) }, { it.path }))
                if (_folderPreview.value?.requestId == event.optString("id")) _folderPreview.value = SoulseekFolderPreview(event.optString("id"), requested.username, requested.directory, valid, loading = false)
            }
            "transfer" -> {
                val id = event.optString("id"); val row = dao.transfer(id) ?: return
                if (row.owner != owner || row.state in listOf("COMPLETED", "CANCELLED")) return
                val reported = event.optString("state").uppercase()
                if (row.state == "PAUSED" && reported !in listOf("PAUSED", "CANCELLED")) return
                val nativeError = if (event.isNull("error")) null else event.optString("error").takeIf { it.isNotBlank() }
                // An interrupted read is Android pausing the app, not a broken file or peer: keep the
                // bytes, say why, and pick it up again.
                val interrupted = reported == "FAILED" && SoulseekPolicy.isInterruption(nativeError)
                val state = if (interrupted) "PAUSED" else reported
                val updated = row.copy(bytes = event.optLong("bytes", row.bytes).coerceIn(0, row.size), state = if (state == "COMPLETED") "VALIDATING" else state,
                    queuePosition = if (event.has("queuePosition") && !event.isNull("queuePosition")) event.optLong("queuePosition") else null,
                    speed = event.optDouble("speed", 0.0), stagePath = event.optString("path").ifBlank { row.stagePath }, error = if (interrupted) interruptionReason() else nativeError)
                if (runCatching { SoulseekTransferState.valueOf(updated.state) }.isFailure) return
                // Only the byte count, speed or queue place moved: written at most once a second
                // (the newest value follows), since every write re-runs the transfers query and
                // redraws the notification. A change of state is written at once.
                if (updated.state in PROGRESS_STATES && updated == row.copy(bytes = updated.bytes, speed = updated.speed, queuePosition = updated.queuePosition)) {
                    val last = progressWritten[id]
                    if (last != null && nowMs() - last < PROGRESS_WRITE_MS) { holdProgress(updated, last); return }
                }
                // A real failure: decide at once whether to ask again later or look elsewhere.
                val failed = state == "FAILED"
                val next = if (failed) recovered(updated, SoulseekPolicy.failureKind(nativeError)) else updated
                heldProgress.remove(id)
                dao.putTransfer(next)
                if (next.state in PROGRESS_STATES) progressWritten[id] = nowMs() else progressWritten.remove(id)
                if (state == "COMPLETED") {
                    activeTransfers.remove(id); publishAsync(updated)
                    _totals.value = preferences.addTotals(owner, downBytes = row.size, downFiles = 1)
                    if (!preferences.shareAsked(owner) && !preferences.sharing(owner) && _destination.value != null) _shareOffer.value = true
                }
                if (state == "FAILED" || state == "CANCELLED" || state == "PAUSED") activeTransfers.remove(id)
                if (state == "COMPLETED" || state == "FAILED" || state == "CANCELLED" || state == "PAUSED") pumpQueue()
                if (interrupted) scheduleAutoResume(id)
                if (failed) follow(next)
            }
            "message" -> {
                val serverId = event.getLong("id"); val username = event.getString("username")
                require(SoulseekPolicy.validUsername(username)) { "Invalid incoming Soulseek sender" }
                val id = SoulseekPolicy.messageId(owner, serverId)
                // Commit first. A crash before the ack simply replays the unique server message ID.
                db.withTransaction {
                    dao.addContact(ContactRow(owner, username))
                    dao.insertMessage(MessageRow(id, owner, username, event.getString("text"), event.optLong("timestamp", System.currentTimeMillis()).let { if (it < 10_000_000_000L) it * 1000 else it }, false, "RECEIVED"))
                }
                command("ack") { put("id", serverId) }
            }
            "message_sent" -> dao.messageState(event.getString("clientId"), "SENT")
            "upload" -> {
                val upload = SoulseekUpload(event.getString("username"), event.getString("path"), event.optLong("bytes"), event.optLong("total"), event.optString("state"))
                val before = _uploads.value.firstOrNull { it.username == upload.username && it.path == upload.path }
                val previous = before?.state
                // Every byte that went out counts, a broken-off upload's too; a finished one adds a file.
                val sent = (upload.bytes - (before?.bytes ?: 0)).coerceAtLeast(0)
                val finished = upload.state == "completed" && previous != "completed"
                if (sent > 0 || finished) _totals.value = preferences.addTotals(owner, upBytes = sent, upFiles = if (finished) 1 else 0)
                _uploads.update { list -> (list.filterNot { it.username == upload.username && it.path == upload.path } + upload).takeLast(100) }
                val serving = setOf("uploading", "queued")
                when {
                    upload.state !in serving -> updateServices()
                    // Already being served: its progress only moves the notification.
                    previous in serving -> Unit
                    else -> try { startService(SoulseekTransferService::class.java) }
                    catch (e: RuntimeException) {
                        _sharing.value = false; shares.clear(); _uploads.value = emptyList()
                        command("shares") { put("files", JSONArray()) }
                        _error.value = "Android paused background sharing. Open Sonic to enable sharing again."
                    }
                }
            }
            "presence" -> dao.presence(owner, event.getString("username"), event.optBoolean("online"))
            "error" -> {
                val id = event.optString("id"); val message = event.optString("error", "Soulseek could not complete this action.")
                repairSearches[id]?.let { it.done.complete(true); return }
                if (event.optString("operation") == "message" && id.isNotBlank()) dao.messageState(id, "FAILED")
                if (id == searchId) { _searching.value = false; _searchStatus.update { it.copy(phase = SearchPhase.FAILED, error = message) } }
                if (id == folderPreview.value?.requestId) _folderPreview.update { it?.copy(loading = false, error = message) }
                if (id == _shelf.value?.requestId) { _shelf.update { it?.copy(loading = false, error = message, reason = event.optString("reasonCode").ifBlank { null }) }; pendingBrowse.remove(id); return }
                pendingBrowse.remove(id); _error.value = message
            }
        }
    }
    private fun parseResult(o: JSONObject, username: String) = SoulseekSearchResult(username, o.optString("path"), o.optLong("size"), o.intOrNull("durationSec"), o.intOrNull("sampleRate"), o.intOrNull("bitDepth"), o.intOrNull("bitrate"), o.optInt("freeSlots"), o.optLong("uploadSpeed"), if (o.has("queueLength") && !o.isNull("queueLength")) o.optLong("queueLength") else null)
    private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key).takeIf { it > 0 } else null
    private fun publishAsync(row: TransferRow) {
        if (publicationJobs.containsKey(row.id)) return
        publicationJobs[row.id] = scope.launch(Dispatchers.IO) {
            try { publishSoulseek(row) }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) {
                val kind = publishFailure(e)
                dao.transfer(row.id)?.let { current ->
                    val failed = current.copy(error = friendly(e), speed = 0.0)
                    val next = if (kind == null) failed.copy(state = "FAILED") else recovered(failed, kind)
                    dao.putTransfer(next)
                    if (kind != null) follow(next)
                }
                // A broken file fixes itself; a folder problem or a sign-in needs you.
                if (kind != FailureKind.BROKEN) _error.value = friendly(e)
            }
            finally { publicationJobs.remove(row.id) }
        }
    }
    private suspend fun publishSoulseek(initial: TransferRow) = publicationMutex.withLock {
        requireSignedIn(); check(initial.owner == session.value.username)
        val directory = storage.stagingDirectory(initial.id).canonicalFile
        val stage = initial.stagePath?.let(::File)?.canonicalFile ?: throw IOException("The completed download was not found.")
        require(stage.parentFile == directory && stage.length() == initial.size && FlacPolicy.isFlacName(stage.name)) { "The completed download has an invalid path or size." }
        val info = FlacPolicy.read(stage, wantPicture = true) ?: throw IOException("This file is not valid FLAC music.")
        val remoteDirectory = initial.path.replace('\\', '/').substringBeforeLast('/', "")
        // Aimed at an album already on the shelf: its folder and record, whoever shares the file.
        val aimed = initial.albumId?.let { dao.album(it) }?.let { AlbumJson.fromJson(JSONObject(it.json)) }
        // Aimed but not on the shelf yet (a replacement that finished first): it starts that album.
        val albumId = initial.albumId ?: SoulseekPolicy.albumId(initial.username, remoteDirectory)
        val albumTitle = aimed?.title ?: info.album ?: remoteDirectory.substringAfterLast('/').ifBlank { "Soulseek downloads" }
        val folder = storage.albumDirectory(albumTitle, albumId)
        var row = dao.transfer(initial.id) ?: return@withLock
        val finalName = initial.id.take(10) + " " + SoulseekPolicy.safeFileName(stage.name)
        val partialName = ".${initial.id}.sonic-part"
        val target = row.publishedUri?.let { DocumentFile.fromSingleUri(context, Uri.parse(it)) }?.takeIf { it.exists() }
            ?: folder.findFile(partialName)
            ?: folder.createFile("application/octet-stream", partialName)
            ?: throw IOException("Could not create a file in your download folder.")
        row = row.copy(state = "PUBLISHING", publishedUri = target.uri.toString()); dao.putTransfer(row)
        storage.copyValidated(stage, target.uri, initial.size)
        currentCoroutineContext().ensureActive(); requireSignedIn(); check(session.value.username == row.owner)
        if (target.name != finalName) check(target.renameTo(finalName)) { "This folder cannot publish a completed FLAC file. Choose another download folder and retry." }
        row = row.copy(publishedUri = target.uri.toString()); dao.putTransfer(row)
        val track = Track("soulseek-track-${row.id}", target.uri.toString(), info.title ?: stage.nameWithoutExtension, info.track ?: 1, info.disc ?: 1, info.durationMs, target.name ?: stage.name, info.trackGainDb)
        val existing = dao.album(albumId)?.let { AlbumJson.fromJson(JSONObject(it.json)) }
        val quality = AudioQuality("FLAC", listOfNotNull(existing?.quality?.bitsPerSample, info.bits).minOrNull(), listOfNotNull(existing?.quality?.sampleRateHz, info.sampleRate).minOrNull())
        val cover = info.picture?.let { bytes -> File(context.filesDir, "soulseek-art/$albumId.jpg").apply { parentFile?.mkdirs(); writeBytes(bytes) }.absolutePath } ?: existing?.coverPath
        val album = rememberCover(Album(albumId, albumTitle, info.albumArtist ?: info.artist ?: existing?.artist ?: "Unknown artist", info.year ?: existing?.year, folder.uri.toString(),
            (existing?.tracks.orEmpty().filterNot { it.id == track.id } + track).sortedWith(compareBy({ it.discNumber }, { it.trackNumber }, { it.fileName })), quality, coverPath = cover,
            source = Source.SOULSEEK, albumGainDb = info.albumGainDb, owned = false,
            // The first source names the record's peer and folder, kept as a pair, even when a
            // missing track later comes from someone else: a shared record card opens that folder.
            from = existing?.takeIf { it.remoteFolder != null }?.from ?: initial.username,
            remoteFolder = existing?.remoteFolder ?: remoteDirectory))
        db.withTransaction { dao.putAlbum(DownloadAlbumRow(albumId, AlbumJson.toJson(album).toString())); dao.putTransfer(row.copy(state = "COMPLETED", bytes = row.size, speed = 0.0, error = null, failure = null, recovery = null, retryAt = null)) }
        writeDownloadRecord(folder, album, listOf("slsk://${initial.username}/${initial.path}"))
        stage.delete()
    }
    /**
     * What happens next to a download that just failed. A peer who went away is asked again after
     * 1, 5 and 15 minutes (the partial file is kept, so it resumes); after that, or at once when
     * the peer refused or the file arrived broken, Runout searches for the identical file from
     * someone else. A folder problem waits for you.
     */
    private fun recovered(row: TransferRow, kind: FailureKind): TransferRow {
        val base = row.copy(speed = 0.0, queuePosition = null, failure = kind.name)
        return when {
            row.provider != "Soulseek" || kind == FailureKind.FOLDER -> base.copy(state = "FAILED", recovery = null, retryAt = null)
            kind == FailureKind.PEER_AWAY && row.retries < retryDelaysMs.size -> base.copy(
                state = "PAUSED", recovery = Recovery.RETRY.name,
                retryAt = System.currentTimeMillis() + retryDelaysMs[row.retries], retries = row.retries + 1,
            )
            else -> base.copy(
                state = "PAUSED", recovery = Recovery.SEEK.name, retryAt = null,
                // Someone who refused, or sent a broken file, is not asked for it again.
                skip = if (kind == FailureKind.PEER_AWAY) row.skip else (row.skipped() + row.username).joinToString("\n"),
            )
        }
    }
    private fun follow(row: TransferRow) {
        if (row.failure == FailureKind.BROKEN.name) storage.stagingDirectory(row.id).deleteRecursively()
        when (row.recovery) {
            Recovery.RETRY.name -> scheduleRetry(row.id, row.retryAt ?: 0)
            Recovery.SEEK.name -> seekAsync(row.id)
        }
    }
    /** Why publishing failed: null when it was the sign-in, not the file or the folder. */
    private fun publishFailure(e: Throwable): FailureKind? {
        val message = e.message.orEmpty()
        return when {
            e is SecurityException -> FailureKind.FOLDER
            message.contains("Sign in", ignoreCase = true) -> null
            listOf("folder", "could not create", "saved flac", "destination").any { message.contains(it, ignoreCase = true) } -> FailureKind.FOLDER
            else -> FailureKind.BROKEN
        }
    }
    private fun scheduleRetry(id: String, at: Long) {
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            delay((at - System.currentTimeMillis()).coerceAtLeast(0))
            // Off the list before resuming, so a retry scheduled by the resume's own failure stands.
            retryJobs.remove(id, coroutineContext.job)
            retryDue(id)
        }
        retryJobs.put(id, job)?.cancel()
        job.start()
    }
    /** Retries waiting to run; tests read it. */
    internal val pendingRetries: Int get() = retryJobs.size
    /** Writes [row]'s held-back progress once a second has passed since [last], unless its state moved on. */
    private fun holdProgress(row: TransferRow, last: Long) {
        if (heldProgress.put(row.id, row) != null) return // a write is already on its way
        scope.launch(Dispatchers.IO) {
            delay((last + PROGRESS_WRITE_MS - nowMs()).coerceAtLeast(0))
            val held = heldProgress.remove(row.id) ?: return@launch
            if (dao.progress(held.id, held.state, held.bytes, held.speed, held.queuePosition) > 0) progressWritten[held.id] = nowMs()
        }
    }
    private suspend fun retryDue(id: String) {
        val row = dao.transfer(id) ?: return
        if (row.recovery != Recovery.RETRY.name || (row.retryAt ?: 0) > System.currentTimeMillis() + 1_000) return
        // Out of the app or signed out: it's asked again when you're back, or at the next sign-in.
        if (!foreground || !session.value.signedIn || row.owner != session.value.username) return
        runCatching { resumeOne(id) }.onFailure { if (it is CancellationException) throw it }
    }
    /** Retries and searches Runout had under way, after a sign-in or coming back to the app. */
    private suspend fun recoverPending() {
        val owner = session.value.username ?: return
        if (!session.value.signedIn) return
        dao.recovering(owner).forEach { row ->
            when (row.recovery) {
                Recovery.RETRY.name -> scheduleRetry(row.id, row.retryAt ?: 0)
                Recovery.SEEK.name -> seekAsync(row.id)
            }
        }
    }
    private fun seekAsync(id: String) {
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try { seek(id) }
            catch (e: CancellationException) { throw e }
            catch (_: Throwable) { /* Left as it was: looked for again at the next sign-in. */ }
            finally { seekJobs.remove(id, coroutineContext.job) }
        }
        if (seekJobs.putIfAbsent(id, job) == null) job.start() else job.cancel()
    }
    /**
     * Searches for the identical file (same track, same size) from someone else and takes it into
     * the same album and folder. When no one shares it, the download stays failed, saying so.
     */
    private suspend fun seek(id: String) {
        for (attempt in 1..SEEK_ATTEMPTS) {
            val row = dao.transfer(id)?.takeIf { it.recovery == Recovery.SEEK.name } ?: return
            if (!session.value.signedIn || row.owner != session.value.username) return
            val title = row.effectiveAlbumId()?.let { dao.album(it) }?.let { runCatching { AlbumJson.fromJson(JSONObject(it.json)).title }.getOrNull() }
                ?: folderNames(row.path.replace('\\', '/').substringBeforeLast('/', "")).second
            var cutShort = false
            for (query in SoulseekPolicy.seekQueries(row.path, title)) {
                val (results, cancelled) = repairSearch(query)
                SoulseekPolicy.pickCopy(results, row.path, row.size, row.skipped())?.let { takeCopy(row, it); return }
                cutShort = cutShort || cancelled
            }
            if (!cutShort) break
        }
        dao.transfer(id)?.takeIf { it.recovery == Recovery.SEEK.name }?.let {
            dao.putTransfer(it.copy(state = "FAILED", recovery = null, error = (it.error ?: "The download failed.").removeSuffix(NO_COPY) + NO_COPY))
        }
    }
    private suspend fun repairSearch(query: String): Pair<List<SoulseekSearchResult>, Boolean> = seekMutex.withLock {
        // Soulseek runs one search at a time: never cut a search of yours short, wait for it.
        _searching.first { !it }
        val id = "repair-" + UUID.randomUUID()
        val search = RepairSearch()
        repairSearches[id] = search
        try {
            val started = searchMutex.withLock {
                if (_searching.value) false
                else { command("search") { put("id", id); put("query", query); put("timeoutMs", seekTimeoutMs) }; true }
            }
            if (!started) return@withLock emptyList<SoulseekSearchResult>() to true
            val cancelled = withTimeoutOrNull(seekTimeoutMs + 5_000) { search.done.await() } ?: true
            synchronized(search.results) { search.results.toList() } to cancelled
        } finally { repairSearches.remove(id) }
    }
    /** Swaps a failed download for [copy]: a new download from its peer, into the same album. */
    private suspend fun takeCopy(row: TransferRow, copy: SoulseekSearchResult) {
        val id = SoulseekPolicy.accountKey(row.owner, copy.id)
        // The same peer is back with it: continue the partial file instead.
        if (id == row.id) { resumeOne(row.id); return }
        val existing = dao.transfer(id)
        val next = TransferRow(id, row.owner, copy.username, copy.path, copy.size, albumId = row.effectiveAlbumId(),
            skip = (row.skipped() + row.username).joinToString("\n"), replaced = row.username)
        val busy = existing != null && (existing.state in listOf("QUEUED", "DOWNLOADING", "VALIDATING", "PUBLISHING") || (existing.state == "COMPLETED" && stillPublished(existing)))
        db.withTransaction { dao.deleteTransfer(row.id); if (!busy) dao.putTransfer(next) }
        storage.stagingDirectory(row.id).deleteRecursively()
        if (busy) return
        try { startService(SoulseekTransferService::class.java); startTransfer(next) }
        catch (e: Throwable) { if (e is CancellationException) throw e; dao.putTransfer(next.copy(state = "PAUSED", error = friendly(e))) }
    }

    /** Internet Archive stays an independent HTTPS source; only publication uses this account gate. */
    suspend fun publishArchiveDownload(album: Album, stagingDir: File): Album = publicationMutex.withLock {
        initialized.await(); requireSignedIn()
        val expectedOwner = session.value.username
        val expectedGeneration = generation
        val folder = storage.albumDirectory(album.title, album.id)
        val tracks = album.tracks.mapIndexed { index, track ->
            currentCoroutineContext().ensureActive(); requireSignedIn()
            check(session.value.username == expectedOwner && generation == expectedGeneration) { "The Soulseek account changed. Retry this download." }
            val source = if (track.uri.startsWith("file:")) File(Uri.parse(track.uri).path!!) else File(stagingDir, track.fileName)
            require(source.canonicalFile.parentFile == stagingDir.canonicalFile && FlacPolicy.read(source) != null) { "Internet Archive supplied an invalid FLAC file." }
            val name = SoulseekPolicy.safeFileName(track.fileName.ifBlank { source.name })
            val partialName = ".sonic-${SoulseekPolicy.digest(album.id).take(16)}-$index.part"
            val target = folder.findFile(name)?.takeIf { it.isFile } ?: folder.findFile(partialName) ?: folder.createFile("application/octet-stream", partialName) ?: throw IOException("Could not create a file in your download folder.")
            storage.copyValidated(source, target.uri, source.length())
            currentCoroutineContext().ensureActive(); requireSignedIn()
            if (target.name != name) check(target.renameTo(name)) { "This folder cannot publish a completed FLAC file. Choose another download folder and retry." }
            track.copy(uri = target.uri.toString(), fileName = target.name ?: name)
        }
        val published = rememberCover(album.copy(folderUri = folder.uri.toString(), tracks = tracks))
        val identifier = album.id.removePrefix("drop-")
        writeDownloadRecord(folder, published, published.tracks.map { "https://archive.org/download/$identifier/${it.fileName}" } + listOf("https://archive.org/details/$identifier"))
        published
    }
    /** Tracks Archive's existing HTTPS downloader without sending it Soulseek credentials. */
    suspend fun withArchiveDownload(
        identifier: String,
        title: String,
        block: suspend (onProgress: (Long, Long) -> Unit) -> Result<Album>,
    ): Result<Album> {
        initialized.await()
        return try {
            requireSignedIn(); storage.requireDestination()
            val owner = session.value.username!!
            val epoch = generation
            val id = SoulseekPolicy.accountKey(owner, "archive:$identifier")
            check(!archiveJobs.containsKey(id)) { "This Internet Archive album is already downloading." }
            val job = currentCoroutineContext().job
            archiveJobs[id] = job
            val row = TransferRow(id, owner, "Internet Archive", title, 1_000_000L, state = "DOWNLOADING", provider = "Internet Archive")
            dao.putTransfer(row)
            // Progress reaches the database at most once a second; the last write marks it done.
            val written = java.util.concurrent.atomic.AtomicLong(nowMs() - PROGRESS_WRITE_MS)
            try {
                startService(SoulseekTransferService::class.java)
                val result = block { bytes, total ->
                    val now = nowMs(); val last = written.get()
                    if (total > 0 && !job.isCancelled && now - last >= PROGRESS_WRITE_MS && written.compareAndSet(last, now)) scope.launch(Dispatchers.IO) {
                        val existing = dao.transfer(id)
                        if (existing != null && existing.state == "DOWNLOADING" && archiveJobs[id] === job) {
                            dao.putTransfer(existing.copy(bytes = bytes.coerceIn(0, total), size = total))
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                check(session.value.username == owner && session.value.signedIn && generation == epoch) { "The Soulseek session changed. Sign in and retry." }
                val current = dao.transfer(id) ?: row
                dao.putTransfer(current.copy(state = if (result.isSuccess) "COMPLETED" else "FAILED", bytes = if (result.isSuccess) current.size else current.bytes, error = result.exceptionOrNull()?.let(::friendly)))
                result
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    dao.transfer(id)?.let { current -> if (current.state != "CANCELLED") dao.putTransfer(current.copy(state = "PAUSED", error = "Interrupted. Retry this album from Friday Drops.")) }
                }
                throw e
            } catch (e: Throwable) {
                dao.transfer(id)?.let { dao.putTransfer(it.copy(state = "FAILED", error = friendly(e))) }
                Result.failure(e)
            } finally { archiveJobs.remove(id) }
        } catch (e: CancellationException) { throw e }
        catch (e: Throwable) { Result.failure(e) }
    }
    private suspend fun rememberCover(album: Album): Album {
        if (album.coverPath != null || album.artist.isBlank() || album.title.isBlank() || !coverLookup()) return album
        val file = runCatching { artwork?.lookupOnline(album.id, album.artist, album.title) }.getOrNull() ?: return album
        return album.copy(coverPath = file.absolutePath)
    }

    private fun writeDownloadRecord(folder: DocumentFile, album: Album, links: List<String>) {
        runCatching {
            val bytes = java.io.ByteArrayOutputStream().use { out ->
                VinylPackage.write(out, VinylPackage.Kind.CARD, Profile("sonic", "Sonic.", 0), album, sleeve = album.coverPath?.let(::File), sources = links + listOf("thumbnails/${album.id}.jpg"))
                out.toByteArray()
            }
            storage.tidyRecordFiles(folder)
            storage.writeDocument(folder, "record.vinyl", bytes)
            album.coverPath?.let(::File)?.takeIf { it.isFile }?.readBytes()?.let { thumb ->
                // Beside the music, so a fresh install (or any player) finds the cover; hidden by its
                // dot so it stays out of the phone's photo gallery.
                storage.writeDocument(folder, ".cover.jpg", thumb)
                val thumbs = storage.thumbnailsDirectory()
                storage.writeDocument(thumbs, "${album.id}.jpg", thumb)
            }
        }
    }

    private suspend fun cleanupBrokenDownloads() {
        val root = File(context.noBackupFilesDir, "soulseek-staging")
        root.listFiles()?.forEach { dir ->
            val row = dao.transfer(dir.name)
            if (row == null || row.state == "CANCELLED" || row.state == "COMPLETED") dir.deleteRecursively()
        }
    }

    /** The errors that only say the session was missing: signed out, or offline. */
    private fun isSessionError(message: String) =
        message.startsWith("Sign in with your Soulseek account") || message.startsWith("Soulseek is offline")

    private fun friendly(e: Throwable): String = when (e) {
        is SecurityException -> "Folder access was lost. Choose the folder again and retry."
        else -> e.message?.takeIf { it.isNotBlank() } ?: "This action could not be completed. Please retry."
    }
}

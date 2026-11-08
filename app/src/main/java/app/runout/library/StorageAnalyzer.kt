package app.runout.library

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import app.runout.model.Album
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What a folder's bytes are: the FLAC that plays, split by resolution, the MP3 (which plays with
 * Settings › Flags › MP3 in your folder on), and everything around it. The order is the display order.
 */
enum class StorageKind(val label: String) {
    FLAC_HI_RES("hi-res flac"),
    FLAC_CD("cd-quality flac"),
    MP3("mp3"),
    OTHER_AUDIO("other audio"),
    PICTURES("pictures"),
    PARTIAL("unfinished downloads"),
    OTHER("other files"),
}

/**
 * One record's folder and what it takes. [deletable] when the folder is the record's own: not the
 * picked folder itself, and holding no other record's folder.
 */
data class RecordUsage(
    val title: String,
    val artist: String?,
    val bytes: Long,
    val folderUri: String? = null,
    val albumId: String? = null,
    val deletable: Boolean = false,
)

/** A picked folder, measured. */
data class FolderUsage(
    val label: String,
    val bytes: Long,
    val files: Int,
    val folders: Int,
    val albums: Int,
    val byKind: Map<StorageKind, Long>,
    /** Every record in the folder, largest first. */
    val largest: List<RecordUsage>,
    /** The folder couldn't be read (permission gone, provider away). */
    val error: String? = null,
)

/** The phone's own storage, and Sonic's corner of it. */
data class DeviceUsage(val total: Long, val free: Long)
data class AppUsage(val covers: Long, val cache: Long, val data: Long)

data class StorageReport(
    val device: DeviceUsage?,
    val music: FolderUsage?,
    /** Null with [downloadsInMusic] when the download folder is the music folder or inside it. */
    val downloads: FolderUsage?,
    val downloadsInMusic: Boolean,
    val app: AppUsage,
    val at: Long,
)

/** Settings › Storage. */
sealed interface StorageState {
    data object Idle : StorageState
    data class Measuring(val files: Int) : StorageState
    data class Done(val report: StorageReport) : StorageState
}

/**
 * Settings › Storage: walks the music folder and the download folder through Android's document
 * provider (the same access the library scan has, nothing broader), adds up what's there by kind,
 * and puts it beside the phone's free space and Sonic's own files. Records' resolutions come from
 * the shelf, matched by folder, so nothing is read but sizes and names.
 */
class StorageAnalyzer(private val context: Context) {
    private val _state = MutableStateFlow<StorageState>(StorageState.Idle)
    val state: StateFlow<StorageState> = _state.asStateFlow()

    /** One entry in a folder, as the provider lists it. */
    data class Entry(val docId: String, val name: String, val isDir: Boolean, val size: Long)

    suspend fun analyze(music: Uri?, downloads: Uri?, albums: List<Album>) {
        if (_state.value is StorageState.Measuring) return
        _state.value = StorageState.Measuring(0)
        val report = withContext(Dispatchers.IO) {
            var seen = 0
            val progress = { n: Int -> seen += n; if (seen % 200 < n) _state.value = StorageState.Measuring(seen) }
            val within = music != null && downloads != null && inside(downloads, music)
            StorageReport(
                device = runCatching { StatFs(Environment.getExternalStorageDirectory().path).let { DeviceUsage(it.totalBytes, it.availableBytes) } }.getOrNull(),
                music = music?.let { measure(it, albums, ::list, progress) },
                downloads = if (within) null else downloads?.let { measure(it, albums, ::list, progress) },
                downloadsInMusic = within,
                app = AppUsage(
                    covers = sizeOf(File(context.filesDir, "covers")),
                    cache = sizeOf(context.cacheDir) + (context.externalCacheDir?.let(::sizeOf) ?: 0L),
                    data = (sizeOf(context.filesDir.parentFile ?: context.filesDir) - sizeOf(File(context.filesDir, "covers")) - sizeOf(context.cacheDir)).coerceAtLeast(0L),
                ),
                at = System.currentTimeMillis(),
            )
        }
        _state.value = StorageState.Done(report)
    }

    /**
     * Deletes a record's folder and every file in it from the phone, through the folder's grant.
     * Only ever a [RecordUsage.deletable] one; the caller takes it off the shelf and measures again.
     */
    suspend fun delete(record: RecordUsage): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(record.deletable) { "that folder holds more than one record" }
            val uri = Uri.parse(requireNotNull(record.folderUri))
            val done = try {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } catch (e: SecurityException) {
                error("sonic can't delete in this folder yet. choose it again in library, then try again.")
            }
            check(done) { "android couldn't delete that folder." }
        }
    }

    /** Empties Sonic's caches (never the covers or the library), then measures its corner again. */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        context.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        (_state.value as? StorageState.Done)?.let { done ->
            _state.value = StorageState.Done(done.report.copy(app = done.report.app.copy(cache = sizeOf(context.cacheDir))))
        }
    }

    private fun list(tree: Uri, parentId: String): List<Entry>? = runCatching {
        val out = mutableListOf<Entry>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        context.contentResolver.query(children, projection, null as android.os.Bundle?, null)?.use { c ->
            while (c.moveToNext()) out += Entry(
                c.getString(0), c.getString(1) ?: "", c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                if (c.isNull(3)) 0L else c.getLong(3),
            )
        } ?: return null
        out
    }.getOrNull()

    companion object {
        private val OTHER_AUDIO = setOf("m4a", "aac", "ogg", "oga", "opus", "wav", "aif", "aiff", "alac", "ape", "wv", "dsf", "dff", "wma", "mka")
        /** The kinds that make up a record's own size. */
        private val RECORD_AUDIO = setOf(StorageKind.FLAC_HI_RES, StorageKind.FLAC_CD, StorageKind.MP3, StorageKind.OTHER_AUDIO)
        private val PICTURES = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")

        fun kindOf(name: String, hiRes: Boolean): StorageKind {
            val lower = name.lowercase()
            if (lower.endsWith(".part") || lower.endsWith(".sonic-part")) return StorageKind.PARTIAL
            return when (lower.substringAfterLast('.', "")) {
                "flac" -> if (hiRes) StorageKind.FLAC_HI_RES else StorageKind.FLAC_CD
                "mp3" -> StorageKind.MP3
                in OTHER_AUDIO -> StorageKind.OTHER_AUDIO
                in PICTURES -> StorageKind.PICTURES
                else -> StorageKind.OTHER
            }
        }

        /** "primary:Music/Sonic" is in "primary:Music" (or is it). */
        fun inside(child: Uri, parent: Uri): Boolean {
            val c = runCatching { DocumentsContract.getTreeDocumentId(child) }.getOrNull() ?: return false
            val p = runCatching { DocumentsContract.getTreeDocumentId(parent) }.getOrNull() ?: return false
            return child.authority == parent.authority && (c == p || c.startsWith(p.trimEnd('/') + "/"))
        }

        /**
         * Walks [tree] with [list] and adds it up: each file by kind (FLAC as hi-res when the shelf's
         * record for its folder is), each folder's audio as one record's size.
         */
        fun measure(tree: Uri, albums: List<Album>, list: (Uri, String) -> List<Entry>?, progress: (Int) -> Unit = {}): FolderUsage {
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            val label = "/" + (rootId?.substringAfter(':', rootId) ?: tree.lastPathSegment.orEmpty()).trimStart('/')
            if (rootId == null) return FolderUsage(label, 0, 0, 0, 0, emptyMap(), emptyList(), error = "not a folder sonic can read")
            // The shelf's records by their folder's document id: their resolution and names.
            val byFolder = albums.mapNotNull { a ->
                runCatching { DocumentsContract.getDocumentId(Uri.parse(a.folderUri)) }.getOrNull()?.let { it to a }
            }.toMap()
            val kinds = mutableMapOf<StorageKind, Long>()
            val records = mutableListOf<Pair<String, RecordUsage>>()
            val parent = mutableMapOf<String, String>()
            var bytes = 0L
            var files = 0
            var folders = 0
            val queue = ArrayDeque(listOf(rootId))
            var first = true
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                val children = list(tree, id)
                if (children == null) {
                    if (first) return FolderUsage(label, 0, 0, 0, 0, emptyMap(), emptyList(), error = "couldn't open the folder. choose it again in library.")
                    continue
                }
                first = false
                folders++
                val album = byFolder[id]
                var audio = 0L
                for (e in children) {
                    if (e.isDir) { queue += e.docId; parent[e.docId] = id; continue }
                    val kind = kindOf(e.name, album?.quality?.isHiRes == true)
                    kinds[kind] = (kinds[kind] ?: 0L) + e.size
                    bytes += e.size
                    files++
                    if (kind in RECORD_AUDIO) audio += e.size
                }
                progress(children.count { !it.isDir })
                if (audio > 0) records += id to RecordUsage(
                    album?.title ?: id.substringAfterLast('/').substringAfterLast(':'), album?.artist, audio,
                    folderUri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), albumId = album?.id,
                )
            }
            // A folder that holds another record's folder can't go without taking that one too.
            val holders = records.flatMap { (id, _) -> generateSequence(parent[id]) { parent[it] }.toList() }.toSet()
            val all = records.map { (id, r) -> r.copy(deletable = id != rootId && id !in holders) }
            return FolderUsage(label, bytes, files, folders, records.size, kinds, all.sortedByDescending { it.bytes })
        }

        fun sizeOf(file: File): Long =
            if (!file.exists()) 0L else if (file.isFile) file.length() else file.listFiles()?.sumOf(::sizeOf) ?: 0L
    }
}

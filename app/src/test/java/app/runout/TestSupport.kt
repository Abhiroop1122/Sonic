package app.runout

import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import app.runout.model.Album
import app.runout.model.AudioQuality
import app.runout.model.Track
import app.runout.net.Http
import app.runout.ui.RoomActions
import org.robolectric.Robolectric
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/** Shared helpers for the test suites. */
object Fixtures {
    val context: Context get() = ApplicationProvider.getApplicationContext()

    /** A file from src/test/resources/fixtures. */
    fun file(path: String): File {
        val url = javaClass.classLoader!!.getResource("fixtures/$path") ?: error("missing fixture $path")
        return File(url.toURI())
    }

    fun bytes(path: String): ByteArray = file(path).readBytes()

    fun text(path: String): String = file(path).readText()

    /** Copies fixtures into the app's own storage and returns them as file:// tracks. */
    fun copyTracks(vararg paths: String): List<Track> {
        val dir = File(context.filesDir, "test-audio").apply { mkdirs() }
        return paths.mapIndexed { i, p ->
            val src = file(p)
            val dst = File(dir, src.name)
            src.copyTo(dst, overwrite = true)
            Track(
                id = "t$i-${src.name}", uri = Uri.fromFile(dst).toString(), title = src.nameWithoutExtension.substringAfter(' '),
                trackNumber = i + 1, discNumber = 1, durationMs = 2_000, fileName = src.name,
            )
        }
    }

    fun nightDrive(owned: Boolean = false, license: String? = "https://creativecommons.org/licenses/by/4.0/"): Album {
        val tracks = copyTracks("Music/Vela Park - Night Drive (2023)/01 Opening.flac", "Music/Vela Park - Night Drive (2023)/02 Lowlight.flac")
        return Album(
            id = "nd", title = "Night Drive", artist = "Vela Park", year = 2023, folderUri = "", tracks = tracks,
            quality = AudioQuality("FLAC", 24, 96_000), coverPath = cover("night").absolutePath, license = license, owned = owned,
        )
    }

    fun paperMoons(): Album {
        val tracks = copyTracks(
            "Music/Ines Varga/Paper Moons/01 Paper Moons.mp3",
            "Music/Ines Varga/Paper Moons/02 Glass Hours.mp3",
            "Music/Ines Varga/Paper Moons/03 Signal.mp3",
        )
        return Album(
            id = "pm", title = "Paper Moons", artist = "Ines Varga", year = 2021, folderUri = "", tracks = tracks,
            quality = AudioQuality("MP3", bitrateKbps = 128),
        )
    }

    /** A plain generated cover image. */
    fun cover(name: String, w: Int = 600, h: Int = 600, colour: Int = 0xFF2B6BFF.toInt()): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(colour)
        val p = Paint().apply { color = 0xFFFFFFFF.toInt() }
        c.drawCircle(w / 2f, h / 2f, minOf(w, h) / 4f, p)
        val f = File(context.cacheDir, "covers-test/$name.png").apply { parentFile?.mkdirs() }
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }
}

/** Serves recorded responses instead of the network, and counts calls. */
/** A FLAC with its PICTURE blocks removed; STREAMINFO, comments and audio frames stay as they are. */
fun flacWithoutPicture(bytes: ByteArray): ByteArray {
    val blocks = mutableListOf<ByteArray>()
    var pos = 4
    while (true) {
        val header = bytes[pos].toInt() and 255
        val length = ((bytes[pos + 1].toInt() and 255) shl 16) or ((bytes[pos + 2].toInt() and 255) shl 8) or (bytes[pos + 3].toInt() and 255)
        if (header and 127 != 6) blocks += bytes.copyOfRange(pos, pos + 4 + length).also { it[0] = (header and 127).toByte() }
        pos += 4 + length
        if (header and 128 != 0) break
    }
    blocks.last()[0] = (blocks.last()[0].toInt() or 128).toByte()
    return bytes.copyOfRange(0, 4) + blocks.reduce { a, b -> a + b } + bytes.copyOfRange(pos, bytes.size)
}

class FakeHttp(private val routes: Map<String, () -> ByteArray?>) : Http {
    val calls = CopyOnWriteArrayList<String>()

    private fun find(url: String): (() -> ByteArray?)? = routes.entries.firstOrNull { url.contains(it.key) }?.value

    override fun get(url: String): ByteArray? {
        calls += url
        return find(url)?.invoke()
    }

    override fun download(url: String, to: File, onBytes: (Long) -> Unit): Boolean {
        calls += url
        val body = find(url)?.invoke() ?: return false
        to.parentFile?.mkdirs()
        to.writeBytes(body)
        onBytes(body.size.toLong())
        return true
    }
}

/**
 * A stand-in for Android's folder picker storage: serves a real folder through the same
 * DocumentsContract calls the phone's storage provider answers, so the scanner runs its real code.
 */
class FolderProvider : DocumentsProvider() {
    companion object {
        const val AUTHORITY = "app.runout.test.documents"
        lateinit var root: File
        private var writable = false
        const val ROOT_ID = "primary:Music"

        /**
         * When set, listing a folder waits for it. A scan parked here is provably still in
         * flight, so a test can decide for itself when it may finish.
         */
        @Volatile var block: java.util.concurrent.CountDownLatch? = null

        fun install(folder: File, allowWrite: Boolean = false): Uri {
            root = folder
            writable = allowWrite
            block = null
            val info = ProviderInfo().apply {
                authority = AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
                writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
            }
            Robolectric.buildContentProvider(FolderProvider::class.java).create(info)
            return DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID)
        }

        private val DEFAULT = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }

    override fun onCreate() = true

    private fun idOf(f: File): String = if (f == root) ROOT_ID else ROOT_ID + "/" + f.relativeTo(root).path

    private fun fileOf(id: String): File = if (id == ROOT_ID) root else File(root, id.removePrefix("$ROOT_ID/"))

    private fun mime(f: File) = if (f.isDirectory) Document.MIME_TYPE_DIR else when (f.extension.lowercase()) {
        "flac" -> "audio/flac"; "mp3" -> "audio/mpeg"; "wav" -> "audio/x-wav"; "ogg" -> "audio/ogg"
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    private fun row(c: MatrixCursor, f: File) {
        val b = c.newRow()
        c.columnNames.forEach { col ->
            when (col) {
                Document.COLUMN_DOCUMENT_ID -> b.add(col, idOf(f))
                Document.COLUMN_DISPLAY_NAME -> b.add(col, f.name)
                Document.COLUMN_MIME_TYPE -> b.add(col, mime(f))
                Document.COLUMN_SIZE -> b.add(col, if (f.isDirectory) null else f.length())
                Document.COLUMN_LAST_MODIFIED -> b.add(col, f.lastModified())
                Document.COLUMN_FLAGS -> b.add(col, if (!writable) 0 else if (f.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_RENAME else Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME)
            }
        }
    }

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf("root_id"))

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DEFAULT).also { row(it, fileOf(documentId)) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        block?.let { gate ->
            try { gate.await() } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        }
        val c = MatrixCursor(projection ?: DEFAULT)
        fileOf(parentDocumentId).listFiles()?.sortedBy { it.name }?.forEach { row(c, it) }
        return c
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileOf(documentId), if (writable) ParcelFileDescriptor.parseMode(mode) else ParcelFileDescriptor.MODE_READ_ONLY)

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        check(writable)
        require(displayName.isNotEmpty() && '/' !in displayName && '\\' !in displayName && displayName != "..")
        val target = File(fileOf(parentDocumentId), displayName)
        if (mimeType == Document.MIME_TYPE_DIR) check(target.mkdir()) else check(target.createNewFile())
        return idOf(target)
    }

    override fun deleteDocument(documentId: String) { check(writable); fileOf(documentId).deleteRecursively() }
    override fun renameDocument(documentId: String, displayName: String): String {
        check(writable)
        require('/' !in displayName && '\\' !in displayName && displayName != "..")
        val original = fileOf(documentId)
        val renamed = File(original.parentFile, displayName)
        check(original.renameTo(renamed))
        return idOf(renamed)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String) =
        documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")
}

/** Records what the UI asked for. */
open class RecordingActions : RoomActions {
    val log = CopyOnWriteArrayList<String>()
    override fun pickFolder() { log += "pickFolder" }
    override fun rescan() { log += "rescan" }
    override fun repairLibrary() { log += "repair" }
    override fun dismissCoverLookupNotice() { log += "dismissCoverLookupNotice" }
    override fun load(album: Album) { log += "load:${album.title}" }
    override fun cueTrack(album: Album, trackId: String?) { log += "cue:${album.title}:$trackId" }
    override fun playTrack(sideIndex: Int, trackIndex: Int) { log += "play:$sideIndex:$trackIndex" }
    override fun checkForUpdates() { log += "checkUpdates" }
    override fun setUpdateChannel(channel: app.runout.update.UpdateChannel) { log += "channel:${channel.name}" }
    override fun setBluetoothCodec(enabled: Boolean) { log += "btcodec:$enabled" }
    override fun openUpdate(page: String) { log += "openUpdate:$page" }
    override fun toggleCrackle() { log += "crackle" }
    override fun needleDrop(fraction: Float) { log += "needleDrop" }
    override fun liftNeedle() { log += "lift" }
    override fun holdPlatter() { log += "hold" }
    override fun releasePlatter() { log += "release" }
    override fun flip() { log += "flip" }
    override fun eject() { log += "eject" }
    override fun share(album: Album) { log += "share:${album.title}" }
    override fun shareMyCard() { log += "shareCard" }
    override fun refreshDrops() { log += "refreshDrops" }
    override fun claim(item: app.runout.drops.DropItem) { log += "claim:${item.identifier}" }
    override fun applyPreset(name: String) { log += "preset:$name" }
    override fun importAutoEq() { log += "autoeq" }
    override fun rename(name: String) { log += "rename:$name" }
    override fun startStop() { log += "startStop" }
    override fun nextTrack() { log += "next" }
    override fun previousTrack() { log += "prev" }
    override fun dismissMessage() { log += "dismissMessage" }
    override fun clearSoulseekError() { log += "clearSoulseekError" }
    override fun createAccount(username: String, name: String, accent: Int) { log += "createAccount:$username:$name" }
    override fun restoreAccount(key: String, username: String?) { log += "restore" }
    override fun searchPeople(query: String) { log += "search:$query" }
    override fun openProfile(pubkey: String) { log += "open:${pubkey.take(6)}" }
    override fun closeProfile() { log += "closeProfile" }
    override fun addFriend(profile: app.runout.account.RemoteProfile) { log += "addFriend:${profile.username}" }
    override fun copyAccountKey() { log += "copyKey" }
    override fun signInSoulseek(username: String, password: String, remember: Boolean) { log += "soulseekSignIn:$username:$remember" }
    override fun searchSoulseek(query: String) { log += "soulseekSearch:$query" }
    override fun downloadSoulseek(result: app.runout.soulseek.SoulseekSearchResult) { log += "soulseekDownload:${result.id}" }
    override fun downloadSoulseekFolder(result: app.runout.soulseek.SoulseekSearchResult) { log += "soulseekFolder:${result.id}" }
    override fun openSoulseekConversation(username: String) { log += "soulseekConversation:$username" }
    override fun sendSoulseekMessage(username: String, text: String) { log += "soulseekMessage:$username:$text" }
}

/**
 * A Nostr relay running inside the test, speaking the real protocol over real WebSockets:
 * it checks signatures, keeps the newest version of each profile and answers tag searches.
 */
class FakeRelay {
    private val server = okhttp3.mockwebserver.MockWebServer()
    private val events = CopyOnWriteArrayList<app.runout.account.NostrEvent>()
    /** When set, the relay alters events it serves, as a dishonest relay might. */
    @Volatile var tamper = false
    val received = CopyOnWriteArrayList<String>()

    val url: String

    init {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                okhttp3.mockwebserver.MockResponse().withWebSocketUpgrade(listener)
        }
        server.start()
        url = "ws://${server.hostName}:${server.port}"
    }

    fun close() {
        runCatching { server.shutdown() }
    }

    val stored: List<app.runout.account.NostrEvent> get() = events.toList()

    private val listener = object : okhttp3.WebSocketListener() {
        override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
            received += text
            val m = org.json.JSONArray(text)
            when (m.getString(0)) {
                "EVENT" -> {
                    val e = app.runout.account.NostrEvent.fromJson(m.getJSONObject(1))
                    val ok = e.isValid()
                    if (ok) {
                        val d = e.tag("d")
                        events.removeIf { it.pubkey == e.pubkey && it.kind == e.kind && it.tag("d") == d && it.createdAt <= e.createdAt }
                        if (events.none { it.pubkey == e.pubkey && it.kind == e.kind && it.tag("d") == d }) events += e
                    }
                    webSocket.send(org.json.JSONArray().put("OK").put(e.id).put(ok).put(if (ok) "" else "invalid: bad signature").toString())
                }
                "REQ" -> {
                    val sub = m.getString(1)
                    val f = m.getJSONObject(2)
                    fun list(k: String) = f.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.get(it).toString() } }
                    val kinds = list("kinds"); val authors = list("authors")
                    val tagFilters = f.keys().asSequence().filter { it.startsWith("#") }.associateWith { list(it)!! }
                    val limit = f.optInt("limit", 500)
                    events.filter { e ->
                        (kinds == null || e.kind.toString() in kinds) &&
                            (authors == null || e.pubkey in authors) &&
                            tagFilters.all { (k, vals) -> e.tags.any { it.size > 1 && it[0] == k.drop(1) && it[1] in vals } }
                    }.sortedByDescending { it.createdAt }.take(limit).forEach { e ->
                        val out = if (tamper) e.copy(content = e.content.replace("\"shelfCount\":", "\"shelfCount\":9")) else e
                        webSocket.send(org.json.JSONArray().put("EVENT").put(sub).put(out.toJson()).toString())
                    }
                    webSocket.send(org.json.JSONArray().put("EOSE").put(sub).toString())
                }
            }
        }
    }
}

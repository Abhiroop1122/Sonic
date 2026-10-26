package app.runout

import android.os.Looper
import androidx.room.Room
import app.runout.model.Source
import app.runout.soulseek.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/** Exercise the actual repositories and SAF publication without contacting the public network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, shadows = [ModernDocumentsResolver::class])
class SoulseekRepositoryTest {
    private lateinit var db: SoulseekDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: SoulseekRepository
    private val transports = CopyOnWriteArrayList<FakeTransport>()
    private val transport get() = transports.last()
    private var opens = 0
    private val coverHttp = FakeHttp(mapOf("musicbrainz" to { "{\"release-groups\":[]}".toByteArray() }))
    @Volatile private var coversOnline = false
    private val flac get() = Fixtures.bytes("Music/Vela Park - Night Drive (2023)/01 Opening.flac")

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(Fixtures.context, SoulseekDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repo = SoulseekRepository(Fixtures.context, scope, { opens++; FakeTransport().also { transports += it } }, db,
            retryDelaysMs = listOf(150L, 150L, 150L), seekTimeoutMs = 3_000L,
            artwork = app.runout.library.ArtworkRepository(Fixtures.context, http = coverHttp), coverLookup = { coversOnline })
    }
    @After fun teardown() = runBlocking {
        repo.logout().join()
        scope.coroutineContext[Job]!!.cancelAndJoin()
        db.close()
    }
    private fun await(description: String, predicate: () -> Boolean) {
        val until = System.nanoTime() + 8_000_000_000L
        while (!predicate() && System.nanoTime() < until) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(15)
        }
        assertTrue("$description (repository error: ${repo.error.value})", predicate())
    }
    private fun login(name: String = "runout-test") {
        runBlocking { repo.signIn(name, "local-test-password", false).join() }
        await("signed in") { repo.session.value.signedIn && repo.session.value.username == name }
    }
    private fun destination(): File {
        val folder = File(Fixtures.context.cacheDir, "destination").apply { mkdirs() }
        val uri = FolderProvider.install(folder, allowWrite = true)
        runBlocking { repo.setDestination(uri).join() }
        assertEquals(repo.error.value, uri, repo.destination.value)
        return folder
    }
    private fun incoming(id: Long = 7) = JSONObject().put("type", "message").put("id", id)
        .put("username", "maya").put("text", "A stored message").put("timestamp", 1_700_000_000L).put("replayed", true)

    @Test fun `guest does not load native client and cannot start either download provider`() = runBlocking {
        repo.search("Vela Park").join()
        var archiveCalled = false
        val result = repo.withArchiveDownload("example", "Example") { archiveCalled = true; Result.success(Fixtures.nightDrive()) }
        assertFalse(result.isSuccess)
        assertFalse(archiveCalled)
        assertFalse(repo.session.value.signedIn)
        assertEquals(0, opens)
        assertTrue(repo.downloadAlbums.value.isEmpty())
    }

    @Test fun `invalid usernames are rejected before transport creation`() = runBlocking {
        for (name in listOf(" bad", "bad ", "अ", "a".repeat(31))) repo.signIn(name, "p", false).join()
        assertEquals(0, opens)
        assertTrue(SoulseekPolicy.validUsername("A!"))
        assertTrue(SoulseekPolicy.validUsername("Mixed Case"))
    }

    @Test fun `incoming messages commit before ack and replay does not duplicate history`() = runBlocking {
        login()
        val persistedBeforeAck = java.util.concurrent.atomic.AtomicBoolean(false)
        transport.onCommand = { command ->
            if (command.optString("op") == "ack") {
                persistedBeforeAck.set(runBlocking { db.soulseek().messages("runout-test").first().size == 1 })
            }
        }
        transport.emit(incoming())
        await("first message persisted before acknowledgement") { persistedBeforeAck.get() }
        assertEquals(1, transport.commands.count { it.optString("op") == "ack" })
        transport.emit(incoming())
        await("replay acknowledged") { transport.commands.count { it.optString("op") == "ack" } == 2 }
        assertEquals(1, db.soulseek().messages("runout-test").first().size)
        await("unread count") { repo.conversations.value.singleOrNull()?.unread == 1 }
        repo.markRead("maya").join()
        await("marked read") { repo.conversations.value.singleOrNull()?.unread == 0 }
    }

    @Test fun `messages are pending until transport reports sent and failures remain visible`() = runBlocking {
        login()
        repo.sendMessage("maya", "Hello").join()
        val request = transport.commands.last { it.optString("op") == "message" }
        val id = request.getString("clientId")
        assertEquals("PENDING", db.soulseek().message(id)!!.state)
        transport.emit(JSONObject().put("type", "message_sent").put("clientId", id))
        await("sent state") { runBlocking { db.soulseek().message(id)?.state == "SENT" } }
        transport.failMessage = true
        repo.sendMessage("maya", "Offline").join()
        assertEquals("FAILED", db.soulseek().messages("runout-test").first().last().state)
    }

    @Test fun `logout clears account view and account switch isolates conversations`() = runBlocking {
        login("first")
        transport.emit(incoming())
        await("first account message") { repo.conversations.value.size == 1 }
        repo.logout().join()
        assertTrue(repo.conversations.value.isEmpty())
        assertFalse(repo.session.value.signedIn)
        assertTrue(transport.closed)
        login("second")
        assertEquals(1, db.soulseek().messages("first").first().size)
        assertTrue(db.soulseek().messages("second").first().isEmpty())
    }

    @Test fun `a sharing folder and consent never carry over to another account`() = runBlocking {
        login("first")
        val folder = destination()
        File(folder, "01 Opening.flac").writeBytes(flac)
        val uri = FolderProvider.install(folder, allowWrite = true)
        repo.setSharedFolder(uri).join()
        repo.setSharing(true).join()
        assertTrue(repo.error.value, repo.sharing.value)
        assertEquals(uri, repo.sharedFolder.value)
        login("second")
        await("second account connected callback") { repo.sharedFolder.value == null }
        assertFalse(repo.sharing.value)
        assertFalse(repo.preferences.sharing("second"))
        assertNull(repo.preferences.sharedFolder("second"))
        login("first")
        await("first sharing restored") { repo.sharing.value }
        assertEquals(uri, repo.sharedFolder.value)
    }

    @Test fun `search ignores stale and non FLAC results without requiring quality metadata`() = runBlocking {
        login()
        repo.search("Vela Park").join()
        val id = transport.commands.last { it.optString("op") == "search" }.getString("id")
        fun result(path: String, token: String = id) = JSONObject().put("type", "search_result").put("id", token)
            .put("username", "maya").put("path", path).put("size", 66089L)
        transport.emit(result("Album\\ignored.mp3"))
        transport.emit(result("Album\\stale.flac", "old"))
        transport.emit(result("Album\\track.FLAC"))
        transport.emit(result("Album\\track.FLAC"))
        transport.emit(JSONObject().put("type", "search_done").put("id", id))
        await("search completed") { !repo.searching.value }
        assertEquals(listOf("track.FLAC"), repo.searchResults.value.map { it.fileName })
        assertNull(repo.searchResults.value.single().bitDepth)
    }

    @Test fun `completed FLAC publishes to selected folder and remains after logout`() = runBlocking {
        login(); val folder = destination()
        transport.onCommand = { command ->
            if (command.optString("op") == "download") {
                val stage = File(command.getString("localDirectory"), "01 Opening.flac").apply { parentFile!!.mkdirs(); writeBytes(flac) }
                transport.emit(JSONObject().put("type", "transfer").put("id", command.getString("id")).put("state", "completed")
                    .put("bytes", stage.length()).put("path", stage.absolutePath).put("queuePosition", JSONObject.NULL).put("error", JSONObject.NULL))
            }
        }
        repo.download(SoulseekSearchResult("maya", "Music\\Night Drive\\01 Opening.flac", flac.size.toLong())).join()
        await("published FLAC") { repo.transfers.value.singleOrNull()?.state == SoulseekTransferState.COMPLETED }
        await("crate updated") { repo.downloadAlbums.value.size == 1 }
        val album = repo.downloadAlbums.value.single()
        assertEquals(Source.SOULSEEK, album.source)
        assertEquals("FLAC", album.quality.codec)
        assertEquals(24, album.quality.bitsPerSample)
        assertFalse(album.owned)
        assertNull(repo.transfers.value.single().queuePosition)
        assertNull(repo.transfers.value.single().error)
        val files = folder.walkTopDown().filter { it.isFile }.toList()
        val saved = files.single { it.name.endsWith(".flac") }
        assertArrayEquals(flac, saved.readBytes())
        // The download record is written just after the transfer is marked complete.
        await("download record") { folder.walkTopDown().any { it.isFile && it.name == "record.vinyl" } }
        repo.logout().join()
        assertTrue(saved.exists())
        assertEquals(1, repo.downloadAlbums.value.size)
    }

    @Test fun `renamed MP3 download never publishes into music folder`() = runBlocking {
        login(); val folder = destination()
        val mp3 = Fixtures.bytes("Music/Ines Varga/Paper Moons/01 Paper Moons.mp3")
        transport.onCommand = { command ->
            if (command.optString("op") == "download") {
                val stage = File(command.getString("localDirectory"), "renamed.flac").apply { parentFile!!.mkdirs(); writeBytes(mp3) }
                transport.emit(JSONObject().put("type", "transfer").put("id", command.getString("id")).put("state", "completed").put("bytes", mp3.size).put("path", stage.absolutePath))
            }
        }
        repo.download(SoulseekSearchResult("maya", "Album\\renamed.flac", mp3.size.toLong())).join()
        // A broken copy is never published: Runout drops it and looks for the file from someone else.
        await("invalid download set aside") { repo.transfers.value.singleOrNull()?.failure == FailureKind.BROKEN }
        await("looking elsewhere") { repairSearches().isNotEmpty() }
        assertNull("not the broken copy again", SoulseekPolicy.pickCopy(listOf(SoulseekSearchResult("maya", "Album\\renamed.flac", mp3.size.toLong())), "Album\\renamed.flac", mp3.size.toLong(), runBlocking { db.soulseek().transfer(repo.transfers.value.single().id)!!.skipped() }))
        assertTrue(repo.downloadAlbums.value.isEmpty())
        assertTrue(folder.walkTopDown().none { it.isFile })
    }

    @Test fun `folder preview filters the full listing and never downloads until confirmed`() = runBlocking {
        login()
        val selected=SoulseekSearchResult("maya","Artist/Album/01 First.flac",100)
        repo.previewFolder(selected).join()
        val id=transport.commands.last {it.optString("op")=="browse"}.getString("id")
        val files=JSONArray().put(JSONObject().put("path","Artist/Album/01 First.flac").put("size",100))
            .put(JSONObject().put("path","Artist/Album/02 Second.FLAC").put("size",200))
            .put(JSONObject().put("path","Artist/Album/03 Other.mp3").put("size",300))
            .put(JSONObject().put("path","Artist/Other/04 Wrong.flac").put("size",400))
        transport.emit(JSONObject().put("type","browse_result").put("id",id).put("username","maya").put("directory","Artist/Album").put("files",files))
        await("full folder preview") {repo.folderPreview.value?.loading==false}
        assertEquals(2,repo.folderPreview.value!!.files.size)
        assertTrue(transport.commands.none {it.optString("op")=="download"})
        destination();repo.confirmFolderDownload().join()
        assertEquals(2,transport.commands.count {it.optString("op")=="download"})
        assertNull(repo.folderPreview.value)
    }

    @Test fun `batched search deltas deduplicate and report the visible result limit`() = runBlocking {
        login();repo.search("Album").join()
        val id=transport.commands.last {it.optString("op")=="search"}.getString("id")
        val files=JSONArray()
        repeat(app.runout.soulseek.SearchTerms.MAX_RESULTS + 1) {files.put(JSONObject().put("username","maya").put("path","Album/track-$it.flac").put("size",100))}
        files.put(JSONObject().put("username","maya").put("path","Album/no.mp3").put("size",100))
        transport.emit(JSONObject().put("type","search_batch").put("id",id).put("files",files))
        transport.emit(JSONObject().put("type","search_done").put("id",id))
        await("bounded completed result set") {repo.searchStatus.value.phase==SearchPhase.COMPLETE}
        assertEquals(app.runout.soulseek.SearchTerms.MAX_RESULTS,repo.searchResults.value.size)
        assertTrue(repo.searchStatus.value.limited)
        assertFalse(repo.searching.value)
    }

    @Test fun `message draft is acknowledged only after durable insertion even when send fails`() = runBlocking {
        login()
        var persisted = 0
        var existedAtCallback = false
        transport.failMessage = true
        repo.sendMessage("maya", "Keep in failed history") {
            persisted++
            existedAtCallback = runBlocking { db.soulseek().messages("runout-test").first().any { it.text == "Keep in failed history" } }
        }.join()
        assertEquals(1, persisted)
        assertTrue(existedAtCallback)
        assertEquals("FAILED", db.soulseek().messages("runout-test").first().single().state)
    }

    @Test fun `invalid message never acknowledges draft persistence`() = runBlocking {
        login()
        var persisted = false
        repo.sendMessage("maya", "é".repeat(2049)) { persisted = true }.join()
        assertFalse(persisted)
        assertTrue(db.soulseek().messages("runout-test").first().isEmpty())
    }

    @Test fun `invalid incoming sender is not stored as a contact`() = runBlocking {
        login()
        transport.emit(incoming().put("username", " invalid "))
        transport.emit(JSONObject().put("type", "presence").put("username", "sentinel").put("online", true))
        // A later valid DM proves the earlier event was consumed.
        transport.emit(incoming(8).put("username", "valid-peer"))
        await("valid message processed") { runBlocking { db.soulseek().message(SoulseekPolicy.messageId("runout-test", 8)) != null } }
        assertTrue(db.soulseek().contacts("runout-test").first().none { it.username == " invalid " })
        assertNull(db.soulseek().message(SoulseekPolicy.messageId("runout-test", 7)))
        assertTrue(repo.session.value.connected)
    }

    @Test fun `one failed contact watch does not skip following watches`() = runBlocking {
        login()
        db.soulseek().addContact(ContactRow("runout-test", "a-bad"))
        db.soulseek().addContact(ContactRow("runout-test", "z-good"))
        await("contacts observed") { repo.contacts.value.size == 2 }
        transport.failContact = "a-bad"
        transport.emit(JSONObject().put("type", "session").put("state", "connected"))
        await("first watch attempted") { transport.commands.any { it.optString("op") == "contact" && it.optString("username") == "a-bad" } }
        Thread.sleep(200)
        assertTrue("remaining contacts must still be watched", transport.commands.any { it.optString("op") == "contact" && it.optString("username") == "z-good" })
        assertTrue(repo.session.value.connected)
    }

    @Test fun `turtle pause frees the slot and resume all continues an interrupted download`() = runBlocking {
        login(); destination()
        repo.setTurtle(true)
        val started = mutableListOf<String>()
        transport.onCommand = { command -> if (command.optString("op") == "download") started += command.getString("id") }
        repo.download(SoulseekSearchResult("maya", "Music\\Night Drive\\01.flac", 1000)).join()
        repo.download(SoulseekSearchResult("maya", "Music\\Night Drive\\02.flac", 1000)).join()
        await("only the first download starts in turtle mode") { started.size == 1 }
        val first = started.single()
        repo.pause(first).join()
        await("the next queued download starts after the slot is free") { started.size == 2 }
        val interrupted = db.soulseek().transfer(first)!!
        db.soulseek().putTransfer(interrupted.copy(state = "PAUSED", error = "Interrupted. Sign in and resume to continue."))
        repo.setTurtle(false)
        repo.resumeAll().join()
        await("interrupted download is resumed") { transport.commands.count { it.optString("op") == "download" && it.optString("id") == first } >= 2 }
        assertNull(db.soulseek().transfer(first)!!.error)
        repo.setTurtle(false)
    }

    /** Downloads complete at once with the fixture of the same name. */
    private fun completeDownloads() {
        transport.onCommand = { command ->
            if (command.optString("op") == "download") {
                val name = command.getString("path").replace('\\', '/').substringAfterLast('/')
                val bytes = Fixtures.bytes("Music/Vela Park - Night Drive (2023)/$name")
                val stage = File(command.getString("localDirectory"), name).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
                transport.emit(JSONObject().put("type", "transfer").put("id", command.getString("id")).put("state", "completed")
                    .put("bytes", stage.length()).put("path", stage.absolutePath).put("queuePosition", JSONObject.NULL).put("error", JSONObject.NULL))
            }
        }
    }

    @Test fun `a missing track from another peer joins the album already on the shelf, in its folder`() = runBlocking {
        login(); val folder = destination(); completeDownloads()
        val opening = Fixtures.bytes("Music/Vela Park - Night Drive (2023)/01 Opening.flac")
        val lowlight = Fixtures.bytes("Music/Vela Park - Night Drive (2023)/02 Lowlight.flac")
        repo.download(SoulseekSearchResult("maya", "Music\\Night Drive\\01 Opening.flac", opening.size.toLong())).join()
        await("first track on the shelf") { repo.downloadAlbums.value.singleOrNull()?.tracks?.size == 1 }
        val album = repo.downloadAlbums.value.single()
        // The same album shared by someone else: just the missing track, aimed at the album we have.
        repo.downloadTracks(listOf(SoulseekSearchResult("zoe", "Shares/Night Drive/02 Lowlight.flac", lowlight.size.toLong())), album.id).join()
        await("second track joins it") { repo.downloadAlbums.value.singleOrNull()?.tracks?.size == 2 }
        assertEquals(album.id, repo.downloadAlbums.value.single().id)
        val saved = folder.walkTopDown().filter { it.isFile && it.name.endsWith(".flac") }.toList()
        assertEquals(2, saved.size)
        assertEquals("both in one album folder", 1, saved.map { it.parentFile }.distinct().size)
        // The record still names its first source, peer and folder together, for a record card.
        val kept = repo.downloadAlbums.value.single()
        assertEquals("maya" to "Music/Night Drive", kept.from to kept.remoteFolder)
    }

    @Test fun `a download keeps the peer's folder it came from`() = runBlocking {
        login(); destination(); completeDownloads()
        val opening = Fixtures.bytes("Music/Vela Park - Night Drive (2023)/01 Opening.flac")
        repo.download(SoulseekSearchResult("maya", "Shares\\Vela Park\\Night Drive\\01 Opening.flac", opening.size.toLong())).join()
        await("on the shelf") { repo.downloadAlbums.value.singleOrNull()?.tracks?.size == 1 }
        val album = repo.downloadAlbums.value.single()
        assertEquals("Shares/Vela Park/Night Drive", album.remoteFolder)
        // It survives the JSON round trip, and older JSON without it still loads.
        val json = app.runout.library.AlbumJson.toJson(album)
        assertEquals("Shares/Vela Park/Night Drive", app.runout.library.AlbumJson.fromJson(json).remoteFolder)
        json.remove("remoteFolder")
        assertNull(app.runout.library.AlbumJson.fromJson(json).remoteFolder)
    }

    @Test fun `a record card's folder opens by peer and folder name`() = runBlocking {
        login()
        repo.previewFolder("maya", "Artist/Album").join()
        val browse = transport.commands.last { it.optString("op") == "browse" }
        assertEquals("maya" to "Artist/Album", browse.getString("username") to browse.getString("directory"))
        val files = JSONArray().put(JSONObject().put("path", "Artist\\Album\\01 First.flac").put("size", 100))
        transport.emit(JSONObject().put("type", "browse_result").put("id", browse.getString("id")).put("username", "maya").put("directory", "Artist\\Album").put("files", files))
        await("folder listed") { repo.folderPreview.value?.loading == false }
        assertEquals(1, repo.folderPreview.value!!.files.size)
        // Gone or offline: the preview says so (and offers a search instead).
        repo.previewFolder("maya", "Artist/Gone").join()
        val again = transport.commands.last { it.optString("op") == "browse" }.getString("id")
        transport.emit(JSONObject().put("type", "error").put("id", again).put("error", "Peer is offline"))
        await("folder error") { repo.folderPreview.value?.error == "Peer is offline" }
    }

    @Test fun `the sign-in prompt goes once you're signed in again`() = runBlocking {
        login()
        transport.emit(JSONObject().put("type", "session").put("state", "error").put("reasonCode", "AUTH_REJECTED").put("reason", "Signed in elsewhere"))
        await("signed out") { !repo.session.value.signedIn }
        repo.search("Vela Park").join()
        assertEquals("Sign in with your Soulseek account to download and keep music.", repo.error.value)
        repo.reconnect().join()
        await("signed in again") { repo.session.value.signedIn }
        await("prompt cleared") { repo.error.value == null }
    }

    @Test fun `a completed track whose file is gone downloads again, a present one does not`() = runBlocking {
        login(); val folder = destination(); completeDownloads()
        val opening = Fixtures.bytes("Music/Vela Park - Night Drive (2023)/01 Opening.flac")
        val file = SoulseekSearchResult("maya", "Music\\Night Drive\\01 Opening.flac", opening.size.toLong())
        repo.download(file).join()
        await("published") { repo.transfers.value.singleOrNull()?.state == SoulseekTransferState.COMPLETED }
        val downloads = { transport.commands.count { it.optString("op") == "download" } }
        repo.download(file).join()
        assertEquals("still in the folder: not fetched again", 1, downloads())
        folder.walkTopDown().filter { it.isFile && it.name.endsWith(".flac") }.forEach { it.delete() }
        repo.download(file).join()
        await("fetched again") { downloads() == 2 }
        // The old COMPLETED can still be showing: wait for the file itself, then the state.
        await("saved again") { folder.walkTopDown().count { it.isFile && it.name.endsWith(".flac") } == 1 }
        await("published again") { repo.transfers.value.singleOrNull()?.state == SoulseekTransferState.COMPLETED }
    }

    @Test fun `remove takes a download off the list and remove all failed clears the failures`() = runBlocking {
        login(); destination()
        repo.download(SoulseekSearchResult("maya", "Music\\Night Drive\\01.flac", 1000)).join()
        await("queued") { repo.transfers.value.size == 1 }
        val id = repo.transfers.value.single().id
        repo.remove(id).join()
        await("off the list") { repo.transfers.value.isEmpty() }
        assertTrue("the running download was stopped", transport.commands.any { it.optString("op") == "cancel" && it.optString("id") == id })
        val owner = repo.session.value.username!!
        listOf("a", "b").forEach { db.soulseek().putTransfer(TransferRow("failed-$it", owner, "maya", "x/$it.flac", 10, state = "FAILED", error = "peer left")) }
        db.soulseek().putTransfer(TransferRow("done", owner, "maya", "x/c.flac", 10, state = "COMPLETED"))
        await("listed") { repo.transfers.value.size == 3 }
        repo.removeFailed().join()
        await("failures cleared") { repo.transfers.value.map { it.id } == listOf("done") }
    }

    @Test fun `a download without a cover asks MusicBrainz only when online covers are on`() = runBlocking {
        login(); val folder = destination()
        val bare = flacWithoutPicture(Fixtures.bytes("Music/Vela Park - Night Drive (2023)/01 Opening.flac"))
        transport.onCommand = { command ->
            if (command.optString("op") == "download") {
                val name = command.getString("path").replace('\\', '/').substringAfterLast('/')
                val stage = File(command.getString("localDirectory"), name).apply { parentFile!!.mkdirs(); writeBytes(bare) }
                transport.emit(JSONObject().put("type", "transfer").put("id", command.getString("id")).put("state", "completed")
                    .put("bytes", stage.length()).put("path", stage.absolutePath).put("queuePosition", JSONObject.NULL).put("error", JSONObject.NULL))
            }
        }
        repo.download(SoulseekSearchResult("maya", "Music\\Night Drive\\01 Opening.flac", bare.size.toLong())).join()
        await("published without asking anyone") { repo.downloadAlbums.value.singleOrNull()?.tracks?.size == 1 }
        assertTrue(coverHttp.calls.toString(), coverHttp.calls.isEmpty())
        coversOnline = true
        repo.download(SoulseekSearchResult("zoe", "Shares\\Night Drive\\01 Opening.flac", bare.size.toLong())).join()
        await("asked once it's on") { coverHttp.calls.any { "musicbrainz" in it } }
        assertTrue(folder.walkTopDown().any { it.isFile && it.name.endsWith(".flac") })
    }

    private fun sharedFiles() = transport.commands.filter { it.optString("op") == "shares" }.map { it.getJSONArray("files").length() }

    @Test fun `with wi-fi only, sharing waits on mobile data and comes back on wi-fi`() = runBlocking {
        login()
        val folder = destination()
        File(folder, "01 Opening.flac").writeBytes(flac)
        repo.setSharedFolder(FolderProvider.install(folder, allowWrite = true)).join()
        assertTrue("on by default", repo.preferences.wifiOnly("runout-test"))
        repo.onNetworkChanged(true, wifi = false).join()
        repo.setSharing(true).join()
        assertFalse(repo.sharing.value)
        assertTrue(repo.waitingForWifi.value)
        assertTrue("your choice to share is kept", repo.preferences.sharing("runout-test"))
        assertEquals("nothing offered on mobile data", 0, sharedFiles().last())
        repo.onNetworkChanged(true, wifi = true).join()
        await("shared again on wi-fi") { repo.sharing.value }
        assertFalse(repo.waitingForWifi.value)
        assertEquals(1, sharedFiles().last())
        repo.onNetworkChanged(true, wifi = false).join()
        await("held back again") { !repo.sharing.value && repo.waitingForWifi.value }
        assertEquals(0, sharedFiles().last())
        // Wi-Fi only off: mobile data shares too.
        repo.setWifiOnly(false).join()
        await("shared on mobile data") { repo.sharing.value }
        assertEquals(1, sharedFiles().last())
    }

    @Test fun `the upload limit reaches the client at sign-in and when you change it`() = runBlocking {
        login()
        val slots = { transport.commands.filter { it.optString("op") == "upload_slots" }.map { it.getInt("slots") } }
        await("default at sign-in") { slots() == listOf(SoulseekPreferences.DEFAULT_UPLOAD_SLOTS) }
        repo.setUploadSlots(5).join()
        assertEquals(listOf(SoulseekPreferences.DEFAULT_UPLOAD_SLOTS, 5), slots())
        assertEquals(5, repo.preferences.uploadSlots("runout-test"))
    }

    private fun transferEvent(id: String, state: String, error: String? = null) = JSONObject().put("type", "transfer").put("id", id)
        .put("state", state).put("bytes", 10).put("queuePosition", JSONObject.NULL).put("error", error ?: JSONObject.NULL)
    private fun downloads(id: String? = null) = transport.commands.filter { it.optString("op") == "download" && (id == null || it.optString("id") == id) }
    private fun repairSearches() = transport.commands.filter { it.optString("op") == "search" && it.optString("id").startsWith("repair-") }
    private fun answer(search: JSONObject, vararg files: SoulseekSearchResult) {
        val batch = JSONArray()
        files.forEach { batch.put(JSONObject().put("username", it.username).put("path", it.path).put("size", it.size).put("freeSlots", 1)) }
        transport.emit(JSONObject().put("type", "search_batch").put("id", search.getString("id")).put("files", batch))
        transport.emit(JSONObject().put("type", "search_done").put("id", search.getString("id")).put("cancelled", false))
    }

    @Test fun `a peer who went away is asked again later, resuming the same partial file`() = runBlocking {
        login(); destination()
        repo.download(SoulseekSearchResult("maya", "Music\\Night Drive\\01.flac", 1000)).join()
        await("started") { downloads().size == 1 }
        val id = downloads().single().getString("id")
        transport.emit(transferEvent(id, "failed", "The user went offline"))
        await("waiting to retry") { repo.transfers.value.singleOrNull()?.recovery == Recovery.RETRY }
        val waiting = repo.transfers.value.single()
        assertEquals(SoulseekTransferState.PAUSED, waiting.state)
        assertEquals(FailureKind.PEER_AWAY, waiting.failure)
        assertNotNull(waiting.retryAt)
        await("asked again by itself") { downloads(id).size == 2 }
        assertEquals("the same staging folder, so the part file resumes", downloads(id)[0].getString("localDirectory"), downloads(id)[1].getString("localDirectory"))
        // The row was cleared before the command went out; the list follows the database a beat later.
        await("no longer waiting") { repo.transfers.value.single().recovery == null }
        // Three times away in a row: then it looks for the file elsewhere.
        repeat(3) { n ->
            await("retry ${n + 2} running") { repo.transfers.value.singleOrNull()?.state == SoulseekTransferState.QUEUED }
            transport.emit(transferEvent(id, "failed", "The user went offline"))
            if (n < 2) await("asked again ${n + 2}") { downloads(id).size == n + 3 }
        }
        await("looking elsewhere") { repairSearches().isNotEmpty() }
        assertEquals(Recovery.SEEK, repo.transfers.value.single().recovery)
    }

    @Test fun `a refused file comes from someone sharing the identical copy, into the same album`() = runBlocking {
        login(); val folder = destination(); completeDownloads()
        val opening = Fixtures.bytes("Music/Vela Park - Night Drive (2023)/01 Opening.flac")
        val lowlight = Fixtures.bytes("Music/Vela Park - Night Drive (2023)/02 Lowlight.flac")
        repo.download(SoulseekSearchResult("maya", "Music\\Night Drive\\01 Opening.flac", opening.size.toLong())).join()
        await("first track on the shelf") { repo.downloadAlbums.value.singleOrNull()?.tracks?.size == 1 }
        val album = repo.downloadAlbums.value.single()
        // maya refuses the second track.
        val refuse = transport.onCommand
        transport.onCommand = { command ->
            if (command.optString("op") == "download" && command.getString("username") == "maya") transport.emit(transferEvent(command.getString("id"), "failed", "The upload failed on the other side"))
            else refuse(command)
        }
        repo.download(SoulseekSearchResult("maya", "Music\\Night Drive\\02 Lowlight.flac", lowlight.size.toLong())).join()
        await("searching for another copy") { repairSearches().isNotEmpty() }
        val search = repairSearches().first()
        assertTrue(search.getString("query"), search.getString("query").startsWith("lowlight"))
        answer(search,
            SoulseekSearchResult("maya", "Music\\Night Drive\\02 Lowlight.flac", lowlight.size.toLong()),
            SoulseekSearchResult("kai", "Rips/Night Drive/02 Lowlight.flac", lowlight.size.toLong() + 1),
            SoulseekSearchResult("zoe", "Shares/Vela Park/Night Drive (flac)/02 Lowlight.flac", lowlight.size.toLong()),
        )
        await("the copy joined the album") { repo.downloadAlbums.value.singleOrNull()?.tracks?.size == 2 }
        assertEquals(album.id, repo.downloadAlbums.value.single().id)
        val fixed = repo.transfers.value.single { it.username == "zoe" }
        assertEquals("maya", fixed.replaced)
        assertEquals(album.id, fixed.albumId)
        assertTrue("the failed row made way for it", repo.transfers.value.none { it.username == "maya" && it.fileName.startsWith("02") })
        assertTrue("kai's copy is a different file", downloads().none { it.getString("username") == "kai" })
        assertEquals(1, folder.walkTopDown().filter { it.isFile && it.name.endsWith(".flac") }.map { it.parentFile }.distinct().count())
    }

    @Test fun `when no one else shares the file it stays failed, and fetch missing looks again`() = runBlocking {
        login(); destination()
        repo.download(SoulseekSearchResult("maya", "Music\\Night Drive\\03 Afterglow.flac", 1000)).join()
        await("started") { downloads().size == 1 && repo.transfers.value.size == 1 }
        val transfer = repo.transfers.value.single()
        transport.emit(transferEvent(transfer.id, "failed", "The upload failed on the other side"))
        await("searched") { repairSearches().size == 1 }
        answer(repairSearches()[0])
        await("second query") { repairSearches().size == 2 }
        answer(repairSearches()[1])
        await("failed") { repo.transfers.value.single().state == SoulseekTransferState.FAILED }
        val failed = repo.transfers.value.single()
        assertEquals(FailureKind.REFUSED, failed.failure)
        assertNull(failed.recovery)
        assertTrue(failed.error!!, failed.error!!.contains("No one else shares this exact file"))
        repo.fetchMissing(failed.albumId!!).join()
        await("looked again") { repairSearches().size == 3 }
        assertEquals(Recovery.SEEK, repo.transfers.value.single().recovery)
    }

    @Test fun `the search for another copy waits for yours`() = runBlocking {
        login(); destination()
        repo.search("Vela Park").join()
        await("your search runs") { repo.searching.value }
        repo.download(SoulseekSearchResult("maya", "Music\\Night Drive\\04 Tail Lights.flac", 1000)).join()
        await("started") { downloads().size == 1 && repo.transfers.value.size == 1 }
        transport.emit(transferEvent(repo.transfers.value.single().id, "failed", "The upload failed on the other side"))
        await("looking") { repo.transfers.value.single().recovery == Recovery.SEEK }
        Thread.sleep(300)
        assertTrue("not over your search", repairSearches().isEmpty())
        val yours = transport.commands.last { it.optString("op") == "search" }.getString("id")
        transport.emit(JSONObject().put("type", "search_done").put("id", yours).put("cancelled", false))
        await("then it searches") { repairSearches().isNotEmpty() }
        assertEquals("your results are untouched", SearchPhase.COMPLETE, repo.searchStatus.value.phase)
    }

    @Test fun `download progress is written at most once a second, a change of state at once`() = runBlocking {
        login(); destination()
        repo.download(SoulseekSearchResult("maya", "Music\\Night Drive\\01.flac", 10_000)).join()
        await("started") { downloads().size == 1 }
        val id = downloads().single().getString("id")
        fun progress(state: String, bytes: Long) = JSONObject().put("type", "transfer").put("id", id).put("state", state)
            .put("bytes", bytes).put("speed", 100.0).put("queuePosition", JSONObject.NULL).put("error", JSONObject.NULL)
        fun row() = runBlocking { db.soulseek().transfer(id)!! }
        transport.emit(progress("downloading", 100))
        await("a new state is written at once") { row().state == "DOWNLOADING" && row().bytes == 100L }
        listOf(200L, 300L, 400L).forEach { transport.emit(progress("downloading", it)) }
        Thread.sleep(300)
        assertEquals("progress within the second is held back", 100L, row().bytes)
        await("then the newest progress follows") { row().bytes == 400L }
        val written = System.nanoTime()
        transport.emit(progress("downloading", 500))
        transport.emit(progress("queued", 500))
        await("a change of state does not wait") { row().state == "QUEUED" }
        assertTrue("written within the second", System.nanoTime() - written < 900_000_000L)
        Thread.sleep(1_200)
        assertEquals("held progress never overwrites a newer state", "QUEUED", row().state)
    }

    @Test fun `pause all also stops downloads waiting to retry, and none of them comes back`() = runBlocking {
        login()
        val retryAt = System.currentTimeMillis() + 400
        db.soulseek().putTransfer(TransferRow("runout-test:retry", "runout-test", "maya", "Music\\Night Drive\\01.flac", 1000,
            state = "PAUSED", failure = FailureKind.PEER_AWAY.name, recovery = Recovery.RETRY.name, retryAt = retryAt, retries = 1))
        db.soulseek().putTransfer(TransferRow("runout-test:queued", "runout-test", "maya", "Music\\Night Drive\\02.flac", 1000, state = "QUEUED"))
        repo.onForegroundChanged(true).join()
        await("both rows observed and the retry timer set") { repo.transfers.value.size == 2 && repo.pendingRetries == 1 }
        repo.pauseAll().join()
        await("both paused for good") {
            runBlocking { listOf("runout-test:retry", "runout-test:queued").all { db.soulseek().transfer(it)!!.let { r -> r.state == "PAUSED" && r.recovery == null } } }
        }
        assertEquals(0, repo.pendingRetries)
        Thread.sleep(retryAt - System.currentTimeMillis() + 600)
        assertTrue("nothing restarted", transport.commands.none { it.optString("op") == "download" })
        assertEquals("PAUSED", db.soulseek().transfer("runout-test:retry")!!.state)
    }

    @Test fun `resume all starts every paused download even when one of them can't start`() = runBlocking {
        login(); destination()
        db.soulseek().putTransfer(TransferRow("runout-test:a", "runout-test", "maya", "Music\\Night Drive\\01.flac", 1000, state = "PAUSED"))
        db.soulseek().putTransfer(TransferRow("runout-test:b", "runout-test", "maya", "Music\\Night Drive\\02.flac", 1000, state = "PAUSED"))
        transport.onCommand = { c -> if (c.optString("op") == "download" && c.optString("id") == "runout-test:a") error("peer refused") }
        repo.resumeAll().join()
        assertTrue("the second still starts", transport.commands.any { it.optString("op") == "download" && it.optString("id") == "runout-test:b" })
        assertNotNull("and the failure is still told", repo.error.value)
    }

    @Test fun `a download waiting to retry has one timer however often you come back`() = runBlocking {
        login()
        db.soulseek().putTransfer(TransferRow("runout-test:wait", "runout-test", "maya", "Music\\Night Drive\\01.flac", 1000,
            state = "PAUSED", failure = FailureKind.PEER_AWAY.name, recovery = Recovery.RETRY.name, retryAt = System.currentTimeMillis() + 60_000, retries = 1))
        repeat(3) { repo.onForegroundChanged(false).join(); repo.onForegroundChanged(true).join() }
        await("timer scheduled") { repo.pendingRetries == 1 }
        Thread.sleep(200)
        assertEquals(1, repo.pendingRetries)
    }

    private class FakeTransport : SoulseekTransport {
        val commands = CopyOnWriteArrayList<JSONObject>()
        private val events = ConcurrentLinkedQueue<JSONObject>()
        var onCommand: (JSONObject) -> Unit = {}
        var failMessage = false
        var failContact: String? = null
        var closed = false
        fun emit(event: JSONObject) { events += event }
        override fun command(request: String): String {
            val command = JSONObject(request); commands += command
            if (command.optString("op") == "login") emit(JSONObject().put("type", "session").put("state", "connected"))
            if (command.optString("op") == "message" && failMessage) return """{"ok":false,"error":"Connection closed"}"""
            if (command.optString("op") == "contact" && command.optString("username") == failContact) return """{"ok":false,"error":"Invalid contact fixture"}"""
            onCommand(command)
            return """{"ok":true}"""
        }
        override fun poll(): String {
            val array = JSONArray()
            while (true) array.put(events.poll() ?: break)
            return array.toString()
        }
        override fun close() { closed = true }
    }
}

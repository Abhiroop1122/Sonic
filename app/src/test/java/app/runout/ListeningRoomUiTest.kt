package app.runout

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import app.runout.ui.DeckSkin
import app.runout.ui.Look
import app.runout.ui.Room
import app.runout.ui.ThemeMode
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.click
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import app.runout.discovery.remoteAlbums

import app.runout.discovery.SearchSource

import app.runout.discovery.DiscoveryState

import app.runout.discovery.DiscoveryOptions

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.longClick
import app.runout.drops.DropItem
import app.runout.drops.DropState
import app.runout.drops.DropWeek
import app.runout.library.ScanReport
import app.runout.model.Album
import app.runout.model.AudioQuality
import app.runout.model.DiscStyle
import app.runout.model.SideSplitter
import app.runout.model.Source
import app.runout.model.Track
import app.runout.playback.DeckState
import app.runout.social.Friend
import app.runout.social.Profile
import app.runout.social.ShelfItem
import app.runout.sound.OutputStatus
import app.runout.sound.SoundSettings
import app.runout.ui.CoverCache
import app.runout.ui.Panel
import app.runout.ui.RoomScreen
import app.runout.ui.RoomState
import app.runout.ui.settings.SettingsSection
import app.runout.soulseek.*
import app.runout.sharing.VinylPackage
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import android.graphics.Color as AColor

/**
 * Suite 10 — the listening room on screen: landscape and portrait layouts, the four panels, pulling and
 * sending a record, and the empty state that explains a scan. Renders real frames (Robolectric native
 * graphics) and saves them as screenshots in app/build/outputs/roborazzi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w892dp-h412dp-land-xhdpi", application = android.app.Application::class)
class ListeningRoomUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun portrait() = RuntimeEnvironment.setQualifiers("w412dp-h892dp-port-xhdpi")

    private fun cover(name: String, draw: (Canvas, Int) -> Unit): String {
        val dir = File(Fixtures.context.cacheDir, "ui-covers").apply { mkdirs() }
        val size = 600
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), size)
        val file = File(dir, "$name.jpg")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        runBlocking { CoverCache.load(file.absolutePath) }
        return file.absolutePath
    }

    private fun tracks(id: String, vararg minutes: Double) = minutes.mapIndexed { i, m ->
        Track("$id-$i", "content://test/$id/$i", listOf("Opening", "Lowlight", "Glass Hours", "Signal", "Tidewater", "Afterimage", "Northbound", "Late Bloom", "Coda")[i % 9], i + 1, 1, (m * 60_000).toLong())
    }

    private val albums: List<Album> by lazy {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val night = cover("night") { c, s ->
            p.shader = LinearGradient(0f, 0f, 0f, s.toFloat(), AColor.rgb(28, 34, 92), AColor.rgb(206, 92, 160), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
            p.shader = RadialGradient(s * 0.5f, s * 0.62f, s * 0.22f, AColor.rgb(255, 214, 120), AColor.rgb(255, 120, 80), Shader.TileMode.CLAMP)
            c.drawCircle(s * 0.5f, s * 0.62f, s * 0.22f, p)
            p.shader = null; p.color = AColor.rgb(20, 20, 40)
            c.drawRect(0f, s * 0.72f, s.toFloat(), s.toFloat(), p)
        }
        val monsoon = cover("monsoon") { c, s ->
            c.drawColor(AColor.rgb(18, 92, 96)); p.shader = null
            for (i in 0 until 14) { p.color = if (i % 2 == 0) AColor.rgb(40, 140, 140) else AColor.rgb(230, 240, 232); c.drawRect(0f, i * s / 14f, s.toFloat(), i * s / 14f + s / 40f, p) }
        }
        val paper = cover("paper") { c, s ->
            c.drawColor(AColor.rgb(242, 234, 218)); p.shader = null; p.color = AColor.rgb(238, 98, 52)
            c.drawCircle(s * 0.35f, s * 0.4f, s * 0.2f, p); p.color = AColor.rgb(30, 30, 30); c.drawCircle(s * 0.62f, s * 0.58f, s * 0.12f, p)
        }
        val bloom = cover("bloom") { c, s ->
            p.shader = LinearGradient(0f, 0f, s.toFloat(), s.toFloat(), AColor.rgb(255, 200, 220), AColor.rgb(170, 190, 255), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p); p.shader = null; p.color = AColor.WHITE
            for (i in 0 until 6) c.drawCircle(s * (0.2f + i * 0.12f), s * (0.3f + (i % 3) * 0.18f), s * 0.05f, p)
        }
        listOf(
            Album("a1", "Monsoon Sessions", "Tidal Room", 2019, "", tracks("a1", 5.0, 6.2, 4.8, 7.1, 5.5, 6.0), AudioQuality("FLAC", 16, 44_100), monsoon, AColor.rgb(30, 120, 120)),
            Album("a2", "Night Drive", "Vela Park", 2023, "", tracks("a2", 4.2, 3.9, 5.1, 4.4, 3.6, 4.8, 4.0, 3.7, 5.3), AudioQuality("FLAC", 24, 96_000), night, AColor.rgb(180, 80, 160), license = "https://creativecommons.org/licenses/by/4.0/"),
            Album("a3", "Paper Moons", "Ines Varga", 2021, "", tracks("a3", 3.2, 3.5, 4.1, 2.9, 3.8, 4.4), AudioQuality("FLAC", 16, 44_100), paper, AColor.rgb(230, 110, 60), source = Source.RECEIVED, from = "Maya"),
            Album("a4", "Static Bloom", "Halcyon Dial", 2024, "", tracks("a4", 4.0, 4.0, 4.0, 4.0), AudioQuality("FLAC", 24, 192_000), bloom, AColor.rgb(200, 170, 240)),
            Album("a5", "Summer Tape", "Abhi", 2026, "", tracks("a5", 3.0, 4.0, 3.5), AudioQuality("FLAC", 16, 44_100), null, null, source = Source.PRESSING, disc = DiscStyle.SPLATTER, labelColor = 0xFFE0569B.toInt()),
        )
    }

    private val maya = Friend(
        Profile("m", "Maya", Profile.ACCENTS[1]),
        listOf(ShelfItem("Night Drive", "Vela Park", 2023, "FLAC", null), ShelfItem("Blue Hour", "Kite", 2019, "MP3", null)),
        0L,
    )

    private val week = DropWeek(
        2911, LocalDate.parse("2026-10-16"), LocalDate.parse("2026-10-23"),
        listOf(
            DropItem("NS050", "Another Day, Another Way", "No-Source Netlabel", 2012, "http://creativecommons.org/licenses/by-nc-sa/4.0/"),
            DropItem("DWK123", "Curses From Past Times (LP)", "ProleteR", 2012, "http://creativecommons.org/licenses/by-nc-nd/3.0/"),
            DropItem("badpanda018", "Brick City Love Song", "Riding Alone For Thousands Of Miles", null, "http://creativecommons.org/licenses/by-nc-sa/3.0/"),
        ),
    )

    private fun state(
        albums: List<Album> = this.albums,
        deck: DeckState = DeckState(),
        drops: DropState = DropState.Ready(week),
        sound: SoundSettings = SoundSettings(),
        message: String? = null,
        session: SoulseekSession = SoulseekSession(SoulseekConnection.CONNECTED, "abhi"),
        results: List<SoulseekSearchResult> = emptyList(),
        contacts: List<SoulseekContact> = emptyList(),
    ) = RoomState(
        albums = albums, hasFolder = true, scan = null, deck = deck, crackleOn = true, clock = "3:21 PM",
        sound = sound, output = OutputStatus(24, 96_000, floatPath = true, processing = true),
        profile = Profile("me", "Abhi", Profile.ACCENTS[0]), friends = listOf(maya), drops = drops,
        claimed = setOf("DWK123"), message = message, soulseekSession = session,
        soulseekResults = results, soulseekContacts = contacts, downloadFolder = "Music",
    )

    private fun playing(): DeckState {
        val album = albums[1]
        val sides = SideSplitter.split(album.tracks)
        return DeckState(album = album, sides = sides, needleDown = true, isPlaying = true, positionMs = (sides[0].durationMs * 0.38).toLong())
    }

    /** Renders a still frame: the platter keeps spinning, so time is stepped by hand. */
    private fun still(name: String, s: RoomState, pulledId: String? = null, crateIndex: Int = 0, panel: Panel? = null) {
        compose.mainClock.autoAdvance = false
        compose.setContent { RoomScreen(s, RecordingActions(), initialPulledId = pulledId, initialCrateIndex = crateIndex, initialPanel = panel) }
        repeat(40) { compose.mainClock.advanceTimeBy(100) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun `sideways with an empty platter the crate takes the tabs' room`() {
        still("01c_landscape_empty", state(), crateIndex = 1)
        val sleeve = compose.onNodeWithTag("sleeve:Night Drive").getBoundsInRoot()
        assertTrue("a big sleeve: ${sleeve.right - sleeve.left}", (sleeve.right - sleeve.left).value > 150f)
    }

    @Test
    fun `landscape room, playing and pulled, renders`() {
        still("01_landscape_playing", state(deck = playing()), crateIndex = 3)
        // Sideways the room is for listening: the places wait for the phone to be upright.
        compose.onNodeWithTag("landscape-note").assertIsDisplayed()
        // And nothing printed round the deck: no model label, no speed.
        compose.onNodeWithText("rg-1 · white / teal").assertDoesNotExist()
        compose.onNodeWithText("33⅓ rpm").assertDoesNotExist()
        compose.onNodeWithTag("nav:DROPS").assertDoesNotExist()
    }

    @Test
    fun `new downloads pop up a note with the way to the list, ones already saved don't`() {
        val saved = SoulseekTransfer("old", "maya", "a/old.flac", 10, state = SoulseekTransferState.PAUSED)
        var room by mutableStateOf(state().copy(soulseekTransfers = listOf(saved)))
        compose.mainClock.autoAdvance = false
        compose.setContent { RoomScreen(room, RecordingActions(), initialCrateIndex = 1) }
        repeat(25) { compose.mainClock.advanceTimeBy(100) }
        compose.onNodeWithTag("downloads-added").assertDoesNotExist()
        room = room.copy(soulseekTransfers = room.soulseekTransfers + listOf(
            SoulseekTransfer("a", "maya", "a/01.flac", 10), SoulseekTransfer("b", "maya", "a/02.flac", 10),
        ))
        compose.waitForIdle()
        repeat(6) { compose.mainClock.advanceTimeBy(100) }
        compose.onNodeWithText("2 tracks added to downloads").assertIsDisplayed()
        repeat(40) { compose.mainClock.advanceTimeBy(100) }
        compose.onNodeWithTag("downloads-added").assertDoesNotExist()
    }

    @Test
    fun `a paused record with the needle down reads as stopped, not spinning`() {
        still("01b_stopped", state(deck = playing().copy(isPlaying = false)))
        // The line above the tabs is gone; the deck's LED and the top bar say it.
        compose.onNodeWithTag("now-line").assertDoesNotExist()
        compose.onNodeWithText("now spinning", substring = true).assertDoesNotExist()
    }

    @Test
    fun `pulling a record, then a long press opens its details, with no send in sight`() {
        val actions = RecordingActions()
        compose.setContent { RoomScreen(state(), actions, initialCrateIndex = 1) }
        compose.onNodeWithTag("sleeve:Night Drive").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("pulled-disc").assertIsDisplayed()
        compose.onNodeWithTag("pulled-disc").performTouchInput { longClick() }
        // Straight to the details: no menu in between, and nothing offers to send the record.
        compose.onNodeWithTag("record-details").assertIsDisplayed()
        compose.onNodeWithTag("record-menu").assertDoesNotExist()
        compose.onNodeWithText("send", substring = true).assertDoesNotExist()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/02_landscape_record_details.png")
        // The record is out of its sleeve, so the details can put it back.
        compose.onNodeWithTag("details:put-back").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("record-details").assertDoesNotExist()
        compose.onNodeWithTag("pulled-disc").assertDoesNotExist()
        assertTrue(actions.log.none { it.startsWith("share") })
    }

    @Test
    fun `a record's details hold the licence the short caption leaves out`() {
        compose.setContent { RoomScreen(state(), RecordingActions(), initialCrateIndex = 1) }
        // The caption under the crate.
        compose.onNodeWithTag("caption-artist").assertIsDisplayed().assertTextContains("vela park", substring = true)
        compose.onNodeWithText("cc by 4.0").assertDoesNotExist()
        compose.onNodeWithTag("sleeve:Night Drive").performTouchInput { longClick() }
        compose.onNodeWithTag("record-details").assertIsDisplayed()
        compose.onAllNodesWithText("cc by 4.0", substring = true).onFirst().assertExists()
    }

    @Test
    fun `a record's details end with the whole record's running time`() {
        portrait()
        compose.setContent { RoomScreen(state(), RecordingActions(), initialCrateIndex = 1) }
        compose.onNodeWithTag("sleeve:Night Drive").performTouchInput { longClick() }
        // 4.2 + 3.9 + 5.1 + 4.4 + 3.6 + 4.8 + 4.0 + 3.7 + 5.3 minutes.
        compose.onNodeWithTag("record-total").assertTextContains("total").assertTextContains("39:00")
        // A record from the music folder stays on the shelf: no remove link for it.
        compose.onNodeWithTag("details:remove").assertDoesNotExist()
        compose.onNodeWithTag("details:put-back").assertDoesNotExist()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/record_details.png")
        assertEquals("4:22", app.runout.ui.runningTime(262_000))
        assertEquals("1:02:33", app.runout.ui.runningTime(3_753_000))
    }

    @Test
    fun `a received record's details can remove it, after asking`() {
        portrait()
        compose.setContent { RoomScreen(state(), RecordingActions(), initialCrateIndex = 2) }
        compose.onNodeWithTag("sleeve:Paper Moons").performTouchInput { longClick() }
        compose.onNodeWithTag("details:remove").assertTextContains("remove record").performClick()
        compose.onNodeWithTag("record-details").assertDoesNotExist()
        compose.onNodeWithTag("confirm").assertIsDisplayed()
        compose.onNodeWithText("REMOVE RECORD?").assertIsDisplayed()
    }

    @Test
    fun `the share key in the details sends the sheet as a picture`() {
        portrait()
        var shared: Pair<File, String>? = null
        val actions = object : RecordingActions() { override fun shareImage(file: File, title: String) { shared = file to title } }
        compose.setContent { RoomScreen(state(), actions, initialCrateIndex = 1) }
        compose.onNodeWithTag("sleeve:Night Drive").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Share as a picture").assertIsDisplayed()
        compose.onNodeWithTag("details:share").performClick()
        // Robolectric only draws the window when asked to: a screenshot draws the sheet into the picture.
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/record_details_sharing.png")
        compose.waitUntil(10_000) { shared != null }
        val (file, title) = shared!!
        assertEquals("Night Drive", title)
        assertTrue(file.path, file.exists() && file.length() > 0)
        assertEquals(listOf(0x89, 'P'.code, 'N'.code, 'G'.code), file.readBytes().take(4).map { it.toInt() and 0xFF })
        // The whole sheet, every track, on white: taller than the card's capped list, and its corner is white.
        val picture = android.graphics.BitmapFactory.decodeFile(file.path)
        assertTrue("${picture.width} x ${picture.height}", picture.width > 300 && picture.height > picture.width)
        assertEquals(AColor.WHITE, picture.getPixel(picture.width / 2, 40))
        file.copyTo(File("build/outputs/roborazzi/record_details_picture.png"), overwrite = true)
        // Back to the card once the picture is made.
        compose.onNodeWithTag("record-details").assertIsDisplayed()
    }

    @Test
    fun `tapping the open place again goes to its own page, not out to the room`() {
        portrait()
        compose.setContent {
            RoomScreen(state(contacts = listOf(SoulseekContact("maya", true))), RecordingActions(), initialPanel = Panel.SETTINGS, initialSettings = SettingsSection.SOUND)
        }
        compose.onNodeWithTag("panel:sound").assertExists()
        compose.onNodeWithTag("nav:SETTINGS").performClick()
        compose.onNodeWithTag("settings:SOUND").assertExists()
        compose.onNodeWithTag("nav:SETTINGS").performClick()
        compose.onNodeWithTag("panel:settings").assertExists()
        // Friends: from a chat back to the chats.
        compose.onNodeWithTag("nav:FRIENDS").performClick()
        compose.onNodeWithTag("friend:maya").performClick()
        compose.onNodeWithTag("field:Private message").assertExists()
        compose.onNodeWithTag("nav:FRIENDS").performClick()
        compose.onNodeWithTag("field:Private message").assertDoesNotExist()
        compose.onNodeWithTag("new-chat").assertExists()
        // Drops: from a drop back to the week.
        compose.onNodeWithTag("nav:DROPS").performClick()
        compose.onNodeWithTag("drop:NS050").performScrollTo().performClick()
        compose.onNodeWithTag("drop-preview").assertExists()
        compose.onNodeWithTag("nav:DROPS").performClick()
        compose.onNodeWithTag("drop-preview").assertDoesNotExist()
        compose.onNodeWithTag("drop:NS050").assertExists()
    }

    @Test
    fun `the friends tab leaves an open chat even while the room still reports it`() {
        portrait()
        // The closed chat reaches the room a frame late; here it never does.
        val s = state(contacts = listOf(SoulseekContact("maya", true))).copy(soulseekConversation = "maya")
        compose.setContent { RoomScreen(s, RecordingActions(), initialPanel = Panel.FRIENDS) }
        compose.onNodeWithTag("field:Private message").assertExists()
        compose.onNodeWithTag("nav:FRIENDS").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("field:Private message").assertDoesNotExist()
        compose.onNodeWithTag("new-chat").assertExists()
    }

    @Test
    fun `settings has flags for auto-flip, auto-play and sleep mode`() {
        portrait()
        val calls = mutableListOf<String>()
        val actions = object : RecordingActions() {
            override fun setAutoFlip(on: Boolean) { calls += "flip:$on" }
            override fun setAutoPlay(on: Boolean) { calls += "play:$on" }
            override fun setSleepMode(on: Boolean) { calls += "sleep:$on" }
        }
        compose.setContent { RoomScreen(state(), actions, initialPanel = Panel.SETTINGS) }
        compose.onNodeWithTag("settings:FLAGS").performScrollTo().performClick()
        compose.onNodeWithTag("panel:flags").assertExists()
        listOf("Auto-flip", "Auto-play", "Sleep mode").forEach { compose.onNodeWithTag("toggle:$it").performClick() }
        assertEquals(listOf("flip:true", "play:true", "sleep:true"), calls)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/settings_flags.png")
    }

    @Test
    fun `sleep mode puts a moon in the top bar that sets the sleep timer`() {
        portrait()
        val set = mutableListOf<Int?>()
        val actions = object : RecordingActions() { override fun setSleepTimer(minutes: Int?) { set += minutes } }
        var s by mutableStateOf(state())
        compose.setContent { RoomScreen(s, actions) }
        compose.onNodeWithTag("sleep-moon").assertDoesNotExist()
        s = s.copy(deckFlags = app.runout.playback.DeckFlags(sleepMode = true))
        compose.onNodeWithTag("sleep-moon").performClick()
        compose.onNodeWithTag("sleep-timer").assertExists()
        compose.onNodeWithTag("sleep:40").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/sleep_timer.png")
        compose.onNodeWithTag("sleep-start").performClick()
        assertEquals(listOf<Int?>(40), set)
        // Running: the moon turns orange with the minutes left, and the card can turn it off. The
        // countdown keeps a quarter-minute tick, so the clock only moves when told to from here.
        compose.mainClock.autoAdvance = false
        s = s.copy(sleepAt = System.currentTimeMillis() + 23 * 60_000L)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithContentDescription("minutes left", substring = true).assertExists()
        compose.onNodeWithTag("sleep-moon").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("sleep-off").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(200)
        assertEquals(listOf<Int?>(40, null), set)
    }

    @Test
    fun `leaving dig forgets its search a while later`() {
        portrait()
        val cleared = java.util.concurrent.atomic.AtomicInteger()
        val actions = object : RecordingActions() { override fun clearSearch() { cleared.incrementAndGet() } }
        compose.setContent { RoomScreen(state(), actions, initialPanel = Panel.DIG, digForgetMs = 200) }
        compose.waitForIdle()
        assertEquals("not while you're in dig", 0, cleared.get())
        compose.onNodeWithTag("nav:DROPS").performClick()
        compose.waitForIdle()
        // The wait is a plain delay on the main looper: run the looper's clock past it.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        compose.waitUntil(5_000) { cleared.get() == 1 }
    }

    @Test
    fun `sideways a place closes to the room, and downloads still open`() {
        compose.setContent { RoomScreen(state(), RecordingActions(), initialPanel = Panel.DIG) }
        // Dig was open: turned sideways, the room is home and only the nav's note is left.
        compose.onAllNodesWithTag("landscape-note").assertCountEquals(1)
        compose.onNodeWithTag("screen:search").assertDoesNotExist()
        compose.onNodeWithTag("nav:ROOM").assertDoesNotExist()
        compose.onNodeWithTag("open-downloads").performClick()
        compose.onNodeWithTag("panel:transfers").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/landscape_downloads.png")
    }

    @Test
    fun `soulseek settings hold wi-fi only and the upload limit`() {
        portrait()
        val calls = mutableListOf<String>()
        val actions = object : RecordingActions() {
            override fun setSoulseekWifiOnly(enabled: Boolean) { calls += "wifi:$enabled" }
            override fun setSoulseekUploadSlots(slots: Int) { calls += "slots:$slots" }
        }
        val s = state().copy(soulseekSession = SoulseekSession(SoulseekConnection.CONNECTED, "abhi", authenticated = true), soulseekShareConsent = true, soulseekWaitingForWifi = true)
        compose.setContent { RoomScreen(s, actions, initialPanel = Panel.SETTINGS, initialSettings = SettingsSection.ACCOUNT) }
        compose.onNodeWithTag("toggle:Share on Wi-Fi only").performScrollTo().performClick()
        compose.onNodeWithTag("upload-slots:3").performScrollTo().performClick()
        assertEquals(listOf("wifi:false", "slots:3"), calls)
        compose.onNodeWithTag("sharing-waiting-wifi").performScrollTo().assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/soulseek_upload_settings.png")
    }

    @Test
    fun `settings run sound, flags, library, soulseek, about, and library keeps the folders and covers`() {
        portrait()
        val calls = mutableListOf<String>()
        val actions = object : RecordingActions() {
            override fun setCoverLookupEnabled(enabled: Boolean) { calls += "covers:$enabled" }
            override fun pickDownloadFolder() { calls += "pick-download" }
        }
        compose.setContent { RoomScreen(state(), actions, initialPanel = Panel.SETTINGS) }
        val rows = compose.onAllNodes(SemanticsMatcher("a settings row") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("settings:") == true })
            .fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }
        assertEquals(listOf("SOUND", "FLAGS", "LIBRARY", "ACCOUNT", "ABOUT").map { "settings:$it" }, rows)
        compose.onNodeWithTag("settings:LIBRARY").performScrollTo().performClick()
        compose.onNodeWithText("02 download folder").assertExists()
        compose.onNodeWithTag("download-folder").performScrollTo().performClick()
        compose.onNodeWithTag("toggle:Find missing covers online").performScrollTo().performClick()
        assertEquals(listOf("pick-download", "covers:true"), calls)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/settings_library.png")
    }

    @Test
    fun `the look follows the phone and the theme until pinned`() {
        val look = Look()
        assertEquals(true, look.dark(phoneDark = true))
        assertEquals(DeckSkin.NEON, look.deckFor(dark = true))
        assertEquals(DeckSkin.WHITE_TEAL, look.deckFor(dark = false))
        // Neon is for a dark room: pinned, a light room still gets white / teal.
        assertEquals(DeckSkin.WHITE_TEAL, look.copy(deck = DeckSkin.NEON).deckFor(dark = false))
        assertEquals(DeckSkin.DARK, look.copy(deck = DeckSkin.DARK).deckFor(dark = true))
        // Its room is unlit unless the lights are asked for; every other deck is always lit.
        assertEquals(false, look.lit(dark = true))
        assertEquals(true, look.copy(deckLights = true).lit(dark = true))
        assertEquals(true, look.lit(dark = false))
        assertEquals(false, look.copy(theme = ThemeMode.LIGHT).dark(phoneDark = true))
        assertEquals(true, look.copy(theme = ThemeMode.DARK).dark(phoneDark = false))
        assertEquals(DeckSkin.MONO_WHITE, look.copy(deck = DeckSkin.MONO_WHITE).deckFor(dark = true))
        // Each pinned colourway is a model that ships.
        DeckSkin.entries.mapNotNull { it.asset }.forEach { Fixtures.context.assets.open(it).close() }
    }

    @Test
    fun `Flags sets the theme and the deck, and the dark room is true black`() {
        portrait()
        val calls = mutableListOf<String>()
        val actions = object : RecordingActions() {
            override fun setTheme(theme: ThemeMode) { calls += "theme:$theme" }
            override fun setDeckSkin(deck: DeckSkin) { calls += "deck:$deck" }
        }
        try {
            Room.dark = true
            assertEquals(androidx.compose.ui.graphics.Color.Black, Room.Background)
            assertEquals(androidx.compose.ui.graphics.Color.White, Room.Ink)
            compose.setContent { RoomScreen(state().copy(look = Look(ThemeMode.DARK)), actions, initialPanel = Panel.SETTINGS, initialSettings = SettingsSection.FLAGS) }
            compose.onNodeWithTag("theme:LIGHT").performScrollTo().performClick()
            compose.onNodeWithTag("deck:MONO_WHITE").performScrollTo().performClick()
            assertEquals(listOf("theme:LIGHT", "deck:MONO_WHITE"), calls)
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/settings_flags_dark.png")
        } finally {
            Room.dark = false
        }
    }

    @Test
    fun `with intro on launch the needle drop plays over the room, and a tap gives the room back`() {
        portrait()
        compose.mainClock.autoAdvance = false
        compose.setContent { RoomScreen(state().copy(look = Look(introOnLaunch = true)), RecordingActions()) }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithTag("onboarding-intro").assertExists().performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("onboarding-intro").assertDoesNotExist()
        // No setup steps after it: straight to the room.
        compose.onNodeWithTag("screen:setup").assertDoesNotExist()
        compose.onNodeWithTag("turntable").assertExists()
    }

    @Test
    fun `without the flag the room opens straight away, and Flags turns the intro on`() {
        portrait()
        val calls = mutableListOf<String>()
        val actions = object : RecordingActions() { override fun setIntroOnLaunch(on: Boolean) { calls += "intro:$on" } }
        compose.setContent { RoomScreen(state(), actions, initialPanel = Panel.SETTINGS, initialSettings = SettingsSection.FLAGS) }
        compose.onNodeWithTag("onboarding-intro").assertDoesNotExist()
        compose.onNodeWithTag("toggle:Intro on launch").performScrollTo().performClick()
        assertEquals(listOf("intro:true"), calls)
    }

    @Test
    fun `vinyl only puts the room out sideways, and a tap anywhere brings it back until it's left alone`() {
        compose.mainClock.autoAdvance = false
        val calls = mutableListOf<String>()
        val actions = object : RecordingActions() { override fun startStop() { calls += "startStop" } }
        compose.setContent { RoomScreen(state().copy(look = Look(vinylOnly = true)), actions) }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithTag("turntable").assertExists()
        // The first tap, even on the deck's start key, only wakes the room.
        compose.onNodeWithTag("vinyl-only-wake").assertExists()
        compose.onNodeWithTag("deck:startstop").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(emptyList<String>(), calls)
        compose.onNodeWithTag("vinyl-only-wake").assertDoesNotExist()
        // In use it stays up; ten quiet seconds put it out again.
        compose.mainClock.advanceTimeBy(8_000)
        compose.onNodeWithTag("vinyl-only-wake").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithTag("vinyl-only-wake").assertExists()
    }

    @Test
    fun `upright, vinyl only changes nothing`() {
        portrait()
        compose.setContent { RoomScreen(state().copy(look = Look(vinylOnly = true)), RecordingActions()) }
        compose.onNodeWithTag("vinyl-only-wake").assertDoesNotExist()
    }

    @Test
    fun `two fingers on Settings open the storage page, which measures and shows the folders`() {
        portrait()
        val asked = java.util.concurrent.atomic.AtomicInteger()
        val actions = object : RecordingActions() { override fun analyzeStorage() { asked.incrementAndGet() } }
        val usage = app.runout.library.FolderUsage(
            "/Music", 212_501_000, 7, 3, 2,
            mapOf(app.runout.library.StorageKind.FLAC_HI_RES to 120_000_000L, app.runout.library.StorageKind.FLAC_CD to 75_000_000L),
            listOf(app.runout.library.RecordUsage("Night Drive", "Vela Park", 128_000_000), app.runout.library.RecordUsage("Moon Safari", "Air", 75_000_000)),
        )
        val report = app.runout.library.StorageReport(
            app.runout.library.DeviceUsage(128_000_000_000, 41_000_000_000), usage, null, downloadsInMusic = true,
            app = app.runout.library.AppUsage(12_000_000, 3_000_000, 1_000_000), at = 0L,
        )
        var storage by androidx.compose.runtime.mutableStateOf<app.runout.library.StorageState>(app.runout.library.StorageState.Idle)
        compose.setContent { RoomScreen(state().copy(storage = storage), actions, initialPanel = Panel.SETTINGS) }
        // Not on the list.
        compose.onNodeWithTag("settings:STORAGE").assertDoesNotExist()
        compose.onNodeWithTag("panel:settings").performTouchInput {
            down(0, center); down(1, center + androidx.compose.ui.geometry.Offset(120f, 0f))
            repeat(8) { moveBy(0, androidx.compose.ui.geometry.Offset(0f, -40f)); moveBy(1, androidx.compose.ui.geometry.Offset(0f, -40f)) }
            up(0); up(1)
        }
        compose.onNodeWithTag("panel:storage").assertExists()
        assertEquals(1, asked.get())
        storage = app.runout.library.StorageState.Done(report)
        compose.onNodeWithTag("storage-music").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("night drive").assertExists()
        compose.onNodeWithText("downloads land inside your music folder, so they're counted above.").performScrollTo().assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/settings_storage.png")
        // Two fingers down: back to the list.
        compose.onNodeWithTag("storage-back-hint").assertExists()
        compose.onNodeWithTag("panel:settings").performTouchInput {
            down(0, center); down(1, center + androidx.compose.ui.geometry.Offset(120f, 0f))
            repeat(8) { moveBy(0, androidx.compose.ui.geometry.Offset(0f, 40f)); moveBy(1, androidx.compose.ui.geometry.Offset(0f, 40f)) }
            up(0); up(1)
        }
        compose.onNodeWithTag("panel:storage").assertDoesNotExist()
        compose.onNodeWithTag("settings:SOUND").assertExists()
    }

    @Test
    fun `a record's delete key warns in the other theme's colours, and only delete deletes`() {
        portrait()
        val deleted = mutableListOf<String>()
        val actions = object : RecordingActions() { override fun deleteRecordFolder(record: app.runout.library.RecordUsage) { deleted += record.title } }
        val night = app.runout.library.RecordUsage("Night Drive", "Vela Park", 128_000_000, "content://x/Night", "a-night", deletable = true)
        val usage = app.runout.library.FolderUsage("/Music", 128_000_000, 2, 2, 1, mapOf(app.runout.library.StorageKind.FLAC_HI_RES to 128_000_000L), listOf(night))
        val report = app.runout.library.StorageReport(null, usage, null, downloadsInMusic = true, app = app.runout.library.AppUsage(0, 0, 0), at = 0L)
        try {
            Room.dark = true
            compose.setContent { RoomScreen(state().copy(storage = app.runout.library.StorageState.Done(report)), actions, initialPanel = Panel.SETTINGS, initialSettings = SettingsSection.STORAGE) }
            compose.onNodeWithTag("delete:Night Drive").performScrollTo().performClick()
            compose.onNodeWithTag("delete-warning").assertExists()
            compose.onNodeWithText("your album will be deleted.").assertExists()
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/storage_delete_warning_dark.png")
            compose.onNodeWithTag("delete-keep").performClick()
            compose.onNodeWithTag("delete-warning").assertDoesNotExist()
            assertTrue("keeping it deletes nothing", deleted.isEmpty())
            compose.onNodeWithTag("delete:Night Drive").performScrollTo().performClick()
            compose.onNodeWithTag("delete-confirm").performClick()
            assertEquals(listOf("Night Drive"), deleted)
        } finally {
            Room.dark = false
        }
    }

    @Test
    fun `the record on the platter stays on the deck - its sleeve gives up no disc`() {
        portrait()
        val cued = DeckState(album = albums[1], sides = SideSplitter.split(albums[1].tracks))
        compose.setContent { RoomScreen(state(deck = cued), RecordingActions(), initialCrateIndex = 1) }
        compose.onNodeWithTag("sleeve:Night Drive").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("pulled-disc").assertDoesNotExist()
    }

    @Test
    fun `let go away from the platter and the record slides back into its sleeve`() {
        portrait()
        compose.setContent { RoomScreen(state(), RecordingActions(), initialPulledId = "a2", initialCrateIndex = 1) }
        compose.onNodeWithTag("pulled-disc").assertExists().performTouchInput {
            down(center); repeat(4) { moveBy(androidx.compose.ui.geometry.Offset(30f, -6f)) }; up()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("pulled-disc").assertDoesNotExist()
    }

    @Test
    fun `swiping the crate with a record out puts it back and digs on`() {
        portrait()
        compose.setContent { RoomScreen(state(), RecordingActions(), initialPulledId = "a2", initialCrateIndex = 1) }
        compose.onNodeWithText("NIGHT DRIVE").assertExists()
        compose.onNodeWithTag("sleeve:Night Drive").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("pulled-disc").assertDoesNotExist()
        compose.onNodeWithText("NIGHT DRIVE").assertDoesNotExist()
    }

    @Test
    fun `turning the phone keeps the crate on the record in front`() {
        portrait()
        val upright = mutableStateOf(true)
        compose.setContent {
            // The room turns by its own shape: wider than tall is sideways.
            Box(Modifier.size(if (upright.value) DpSize(412.dp, 892.dp) else DpSize(412.dp, 300.dp))) { RoomScreen(state(), RecordingActions(), initialCrateIndex = 1) }
        }
        compose.onNodeWithText("NIGHT DRIVE").assertExists()
        compose.onNodeWithTag("sleeve:Night Drive").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val dug = compose.onNodeWithTag("caption-artist").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
        upright.value = false
        compose.waitForIdle()
        compose.onNodeWithText("NIGHT DRIVE").assertDoesNotExist()
        compose.onNodeWithTag("caption-artist").assertTextContains(dug.substringBefore(" ·"), substring = true)
    }

    @Test
    fun `a newer release shows on settings, dots its tab and opens its page`() {
        portrait()
        val actions = RecordingActions()
        val release = app.runout.update.Release(app.runout.update.Version(1, 0, 1), "Sonic. 1.0.1", "https://github.com/Abhiroop1122/Sonic/releases/tag/v1.0.1")
        val s = state().copy(update = app.runout.update.UpdateState.Available(release))
        compose.setContent { RoomScreen(s, actions, initialPanel = Panel.SETTINGS) }
        compose.onNodeWithTag("settings-update").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings, new", substring = true).assertExists()
        compose.onNodeWithTag("settings-update-get").performClick()
        assertTrue(actions.log.toString(), "openUpdate:${release.page}" in actions.log)
        compose.onNodeWithTag("settings:ABOUT").performClick()
        compose.onNodeWithTag("about-update-get").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the first check asks which builds you want, then checks`() {
        portrait()
        val actions = RecordingActions()
        compose.setContent { RoomScreen(state().copy(update = app.runout.update.UpdateState.Idle), actions, initialPanel = Panel.SETTINGS, initialSettings = SettingsSection.ABOUT) }
        compose.onNodeWithTag("about-update-check").performScrollTo().performClick()
        compose.onNodeWithTag("channel-choose:DEVELOPMENT").assertIsDisplayed()
        compose.onNodeWithTag("channel-choose:STABLE").performClick()
        assertEquals(listOf("channel:STABLE", "checkUpdates"), actions.log.filter { it.startsWith("channel:") || it == "checkUpdates" })
        compose.onNodeWithTag("channel-choose:STABLE").assertDoesNotExist()
    }

    @Test
    fun `the track wheel is a picker, only the song under the bar plays, another rolls under it first`() {
        portrait()
        val actions = RecordingActions()
        compose.mainClock.autoAdvance = false
        compose.setContent { RoomScreen(state(deck = playing()), actions) }
        repeat(10) { compose.mainClock.advanceTimeBy(100) }
        // The song on is under the bar to begin with.
        compose.onNodeWithTag("wheel-playing", useUnmergedTree = true).assertExists()
        val tags = compose.onAllNodes(SemanticsMatcher("a wheel row") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("track:") == true })
            .fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }
        val row = SemanticsMatcher("a wheel row") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("track:") == true }
        val playingTag = compose.onNode(row and androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasTestTag("wheel-playing")), useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.TestTag]
        val other = tags.first { it != playingTag }
        // A tap on a song off the bar doesn't play it: it rolls under the bar.
        compose.onNodeWithTag(other).performTouchInput { click(center) }
        repeat(20) { compose.mainClock.advanceTimeBy(100) }
        assertTrue(actions.log.toString(), actions.log.none { it.startsWith("play:") })
        compose.onNodeWithTag("wheel-tap-to-play", useUnmergedTree = true).assertExists()
        // Under the bar now, a tap plays it.
        compose.onNodeWithTag(other).performTouchInput { click(center) }
        repeat(5) { compose.mainClock.advanceTimeBy(100) }
        val (side, track) = other.removePrefix("track:").let { (it[0] - 'A') to (it.drop(1).toInt() - 1) }
        assertTrue(actions.log.toString(), "play:$side:$track" in actions.log)
    }

    @Test
    fun `held over a playing deck a record swaps in, and the music carries on`() {
        portrait()
        val monsoon = albums[0]
        var deck by mutableStateOf(DeckState(album = monsoon, sides = SideSplitter.split(monsoon.tracks), needleDown = true, isPlaying = true))
        val actions = object : RecordingActions() {
            override fun load(album: Album) { super.load(album); deck = DeckState(album = album, sides = SideSplitter.split(album.tracks)) }
            override fun eject() { super.eject(); deck = DeckState() }
            override fun liftNeedle() { super.liftNeedle(); deck = deck.copy(needleDown = false, isPlaying = false) }
            override fun needleDrop(fraction: Float) { super.needleDrop(fraction); deck = deck.copy(needleDown = true, isPlaying = true) }
        }
        compose.mainClock.autoAdvance = false
        compose.setContent { RoomScreen(state(deck = deck), actions, initialPulledId = "a2", initialCrateIndex = 1) }
        repeat(20) { compose.mainClock.advanceTimeBy(100) }
        // Where the platter is: the deck's own camera over the turntable's box.
        val box = compose.onNodeWithTag("turntable").getBoundsInRoot()
        val d = compose.density.density
        val cam = app.runout.ui.deck.DeckCamera((box.right - box.left).value * d, (box.bottom - box.top).value * d)
        val c = cam.project(app.runout.ui.deck.V3(app.runout.ui.deck.DeckModel.platterCentre.x, app.runout.ui.deck.DeckModel.RECORD_Y, app.runout.ui.deck.DeckModel.platterCentre.z))
        val platter = androidx.compose.ui.geometry.Offset(box.left.value * d + c.x, box.top.value * d + c.y)
        val discBox = compose.onNodeWithTag("pulled-disc").getBoundsInRoot()
        val discAt = androidx.compose.ui.geometry.Offset((discBox.left.value + discBox.right.value) / 2f * d, (discBox.top.value + discBox.bottom.value) / 2f * d)
        val step = (platter - discAt) / 10f
        compose.onNodeWithTag("pulled-disc").performTouchInput { down(center); repeat(10) { moveBy(step) } }
        // Held there past the shakes: the swap runs with the finger still down.
        repeat(12) { compose.mainClock.advanceTimeBy(100) }
        compose.onRoot().performTouchInput { up() }
        repeat(40) { compose.mainClock.advanceTimeBy(100) }
        val log = actions.log.toList()
        assertTrue(log.toString(), log.indexOf("eject") in 0 until log.indexOf("load:Night Drive"))
        assertTrue("it plays on: $log", log.lastIndexOf("needleDrop") > log.indexOf("load:Night Drive"))
        compose.onNodeWithTag("pulled-disc").assertDoesNotExist()
    }

    @Test
    fun `soulseek settings grey out how you share until sharing is on`() {
        portrait()
        val calls = mutableListOf<String>()
        val actions = object : RecordingActions() {
            override fun setSoulseekWifiOnly(enabled: Boolean) { calls += "wifi:$enabled" }
            override fun setSoulseekUploadSlots(slots: Int) { calls += "slots:$slots" }
            override fun setTurtleMode(enabled: Boolean) { calls += "turtle:$enabled" }
        }
        val s = state().copy(soulseekSession = SoulseekSession(SoulseekConnection.CONNECTED, "abhi", authenticated = true), soulseekShareConsent = false)
        compose.setContent { RoomScreen(s, actions, initialPanel = Panel.SETTINGS, initialSettings = SettingsSection.ACCOUNT) }
        compose.onNodeWithText("03 sharing").assertExists()
        compose.onNodeWithText("04 connection").assertExists()
        compose.onNodeWithTag("toggle:Share on Wi-Fi only").performScrollTo().assertIsNotEnabled().performClick()
        compose.onNodeWithTag("upload-slots:3").performScrollTo().performClick()
        compose.onNodeWithTag("settings-turtle").performScrollTo().performClick()
        assertEquals(listOf("turtle:true"), calls)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/soulseek_settings.png")
    }

    private fun broken(id: String, user: String, name: String, state: SoulseekTransferState, error: String, kind: FailureKind, recovery: Recovery? = null, retryAt: Long? = null) =
        SoulseekTransfer(id, user, "Music/Vela Park - Night Drive (2023)/$name", 1_000, state = state, error = error, albumId = albums.first { it.title == "Night Drive" }.id, failure = kind, recovery = recovery, retryAt = retryAt)

    @Test
    fun `failed downloads say what runout is doing about them, and a lost one digs for its album`() {
        portrait()
        val transfers = listOf(
            broken("r", "maya", "01 Opening.flac", SoulseekTransferState.PAUSED, "The user went offline", FailureKind.PEER_AWAY, Recovery.RETRY, System.currentTimeMillis() + 4 * 60_000),
            broken("s", "kai", "02 Lowlight.flac", SoulseekTransferState.PAUSED, "The upload failed on the other side", FailureKind.REFUSED, Recovery.SEEK),
            broken("f", "lee", "03 Afterglow.flac", SoulseekTransferState.FAILED, "The upload failed on the other side. No one else shares this exact file right now.", FailureKind.REFUSED),
        )
        val actions = RecordingActions()
        compose.setContent { RoomScreen(state().copy(soulseekTransfers = transfers), actions, initialPanel = Panel.DOWNLOADS) }
        // In the queue: one waiting to ask maya again, one looking elsewhere, both naming their album.
        compose.onNodeWithText("maya · retrying in 4 min", substring = true).assertExists()
        compose.onNodeWithText("kai · looking for another copy", substring = true).assertExists()
        compose.onNodeWithTag("transfer-album:r").assertTextContains("from night drive")
        compose.onNodeWithTag("retry-now:r").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/downloads_recovering.png")
        compose.onNodeWithTag("downloads-tab:failed").performClick()
        compose.onNodeWithText("no one else shares this exact file", substring = true).assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/downloads_failed.png")
        compose.onNodeWithTag("find-copy:f").performClick()
        assertTrue(actions.log.toString(), "soulseekSearch:Vela Park Night Drive" in actions.log)
        compose.onNodeWithTag("panel:transfers").assertDoesNotExist()
        // Dig, reached from Downloads: "← back" returns there; Downloads itself only closes.
        compose.onNodeWithTag("action:Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("panel:transfers").assertExists()
        compose.onNodeWithTag("action:Back").assertDoesNotExist()
        compose.onNodeWithTag("action:Close").assertExists()
    }

    @Test
    fun `a record missing a track says so on its sleeve, and its details fetch it`() {
        portrait()
        val transfers = listOf(broken("f", "lee", "02 Lowlight.flac", SoulseekTransferState.FAILED, "The user went offline", FailureKind.PEER_AWAY))
        val fetched = mutableListOf<String>()
        val actions = object : RecordingActions() { override fun fetchMissingTracks(albumId: String) { fetched += albumId } }
        compose.setContent { RoomScreen(state().copy(soulseekTransfers = transfers), actions, initialCrateIndex = 1) }
        compose.onNodeWithTag("missing-tag", useUnmergedTree = true).assertExists().assertTextEquals("1 MISSING")
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/sleeve_missing.png")
        compose.onNodeWithTag("sleeve:Night Drive").performTouchInput { longClick() }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/details_fetch_missing.png")
        compose.onNodeWithTag("details:fetch-missing").assertTextContains("fetch missing track").performClick()
        assertEquals(listOf(albums.first { it.title == "Night Drive" }.id), fetched)
        compose.onNodeWithTag("panel:transfers").assertExists()
    }

    // ---------------------------------------------------------------------------------------
    // README screenshots: realistic (but made-up) data, rendered to build/outputs/roborazzi/readme/.

    private fun readme(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/readme/$name.png")

    private fun flac(user: String, path: String, mb: Int, bits: Int = 16, rate: Int = 44_100, free: Int = 1, queue: Long = 0, speed: Long = 4_000_000) =
        SoulseekSearchResult(user, path, mb * 1_048_576L, bitDepth = bits, sampleRate = rate, freeSlots = free, queueLength = queue, uploadSpeed = speed)

    private val moonSafari = listOf(
        "La femme d'argent", "Sexy Boy", "All I Need", "Kelly Watch the Stars", "Talisman",
        "Remember", "You Make It Easy", "Ce matin-là", "New Star in the Sky", "Le voyage de Pénélope",
    )

    @Test
    fun `readme shots of the room`() {
        portrait()
        still("readme-tmp", state(deck = playing()), crateIndex = 1)
        readme("home")
    }

    @Test
    fun `readme shot of a record's details, opened by a long press`() {
        portrait()
        compose.setContent { RoomScreen(state(), RecordingActions(), initialCrateIndex = 1) }
        compose.onNodeWithTag("sleeve:Night Drive").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("pulled-disc").performTouchInput { longClick() }
        readme("record-menu")
    }

    @Test
    fun `readme shots of Dig`() {
        portrait()
        val results = moonSafari.mapIndexed { i, t -> flac("jules", "Music/Air - Moon Safari (1998)/${(i + 1).toString().padStart(2, '0')} $t.flac", 30, 24, 96_000, speed = 9_600_000) } +
            moonSafari.take(10).mapIndexed { i, t -> flac("blinkacre", "Shares/Air/Moon Safari/Air - Moon Safari - ${(i + 1).toString().padStart(2, '0')} $t.flac", 27, queue = 182, speed = 2_500_000) } +
            listOf(flac("orbit", "FLAC/Air - Premiers Symptomes/03 Brakes On.flac", 22, free = 0, queue = 40, speed = 600_000)) +
            moonSafari.take(4).mapIndexed { i, t -> flac("lowlight", "Music/Air - 1998 - Moon Safari [WEB]/0${i + 1} $t.flac", 41, 24, 48_000, speed = 7_000_000) }
        val options = DiscoveryOptions(source = SearchSource.SOULSEEK)
        val discovery = DiscoveryState("moon safari", options, remote = remoteAlbums("moon safari", results, options), online = SoulseekSearchStatus("r", "moon safari", SearchPhase.COMPLETE))
        compose.setContent { RoomScreen(state().copy(discovery = discovery), RecordingActions(), initialPanel = Panel.DIG) }
        compose.waitForIdle()
        readme("dig")
    }

    @Test
    fun `Dig spins the dotted vinyl until the first folders arrive`() {
        portrait()
        val options = DiscoveryOptions(source = SearchSource.SOULSEEK)
        val searching = SoulseekSearchStatus("r", "moon safari", SearchPhase.SEARCHING)
        var s by mutableStateOf(state().copy(discovery = DiscoveryState("moon safari", options, online = searching)))
        compose.setContent { RoomScreen(s, RecordingActions(), initialPanel = Panel.DIG) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Searching").assertIsDisplayed()
        compose.onNodeWithText("digging soulseek for “moon safari”…").assertIsDisplayed()
        compose.onNodeWithTag("stop-search").assertIsDisplayed()
        compose.onNodeWithTag("search-status").assertTextContains("listening for peers", substring = true)
        // The first folders replace the vinyl; the status keeps saying the search is still on.
        val results = moonSafari.take(3).mapIndexed { i, t -> flac("jules", "Music/Air - Moon Safari (1998)/0${i + 1} $t.flac", 30) }
        s = s.copy(discovery = s.discovery.copy(remote = remoteAlbums("moon safari", results, options)))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Searching").assertDoesNotExist()
        compose.onNodeWithTag("remote-results").assertIsDisplayed()
        compose.onNodeWithTag("search-status").assertTextContains("listening for peers", substring = true)
    }

    @Test
    fun `readme shot of a folder preview`() {
        portrait()
        val files = moonSafari.mapIndexed { i, t -> flac("jules", "Music/Air - Moon Safari (1998)/Air - Moon Safari - ${(i + 1).toString().padStart(2, '0')} $t.flac", 30, 24, 96_000) }
        val preview = SoulseekFolderPreview("r", "jules", "Music/Air - Moon Safari (1998)", files, loading = false)
        compose.setContent { RoomScreen(state().copy(folderPreview = preview), RecordingActions(), initialPanel = Panel.DIG) }
        compose.waitForIdle()
        readme("dig-preview")
    }

    @Test
    fun `readme shot of a chat with a record card`() {
        portrait()
        val now = 1_792_540_000_000L
        val night = albums.first { it.title == "Night Drive" }
        val messages = listOf(
            SoulseekMessage("1", "maya", "found anything good this week?", now - 600_000, outgoing = false, state = SoulseekMessageState.RECEIVED, read = true),
            SoulseekMessage("2", "maya", "this one has been on the platter all day", now - 540_000, outgoing = true, state = SoulseekMessageState.SENT),
            SoulseekMessage("3", "maya", app.runout.ui.RecordCard.of(night).encode(), now - 530_000, outgoing = true, state = SoulseekMessageState.SENT),
            SoulseekMessage("4", "maya", app.runout.ui.RecordCard("Air", "Moon Safari", "FLAC · 24-bit · 96 kHz").encode(), now - 120_000, outgoing = false, state = SoulseekMessageState.RECEIVED, read = true),
        )
        val s = state(contacts = listOf(SoulseekContact("maya", true), SoulseekContact("ro", false))).copy(
            soulseekConversations = listOf(SoulseekConversation("maya", messages, 0)),
            soulseekConversation = "maya",
        )
        compose.setContent { RoomScreen(s, RecordingActions(), initialPanel = Panel.FRIENDS) }
        compose.waitForIdle()
        readme("friends-chat")
    }

    @Test
    fun `readme shots of Settings and Drops`() {
        portrait()
        compose.setContent { RoomScreen(state(sound = SoundSettings(eqEnabled = true)), RecordingActions(), initialPanel = Panel.SETTINGS, initialSettings = SettingsSection.SOUND) }
        compose.waitForIdle()
        readme("settings-sound")
    }

    @Test
    fun `readme shot of Friday Drops`() {
        portrait()
        // Netlabel-ish placeholder sleeves: a two-tone gradient, a sun and a few bands.
        fun sleeve(id: String): String {
            val seed = id.hashCode() and 0x7fffffff
            val hue = (seed % 360).toFloat()
            val bmp = android.graphics.Bitmap.createBitmap(600, 600, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            val top = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.95f))
            val bottom = android.graphics.Color.HSVToColor(floatArrayOf((hue + 50f) % 360f, 0.7f, 0.45f))
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, 600f, top, bottom, android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, 600f, 600f, paint)
            paint.shader = null; paint.color = android.graphics.Color.argb(230, 255, 214, 160)
            c.drawCircle(200f + seed % 200, 260f, 110f, paint)
            paint.color = android.graphics.Color.argb(90, 255, 255, 255)
            for (i in 0 until 5) c.drawRect(0f, 420f + i * 28f, 600f, 432f + i * 28f, paint)
            val f = java.io.File(Fixtures.context.cacheDir, "readme-sleeves/$id.png").apply { parentFile?.mkdirs() }
            java.io.FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            return f.absolutePath
        }
        val actions = object : RecordingActions() {
            override suspend fun dropCover(item: app.runout.drops.DropItem): String = sleeve(item.identifier)
        }
        compose.setContent { RoomScreen(state(), actions, initialPanel = Panel.DROPS) }
        compose.waitForIdle()
        // The real cover fades in over the dotted placeholder, which then goes.
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("drop-placeholder:NS050", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Another Day, Another Way").assertExists()
        readme("drops")
    }

    @Test
    fun `portrait puts the turntable above the crate`() {
        portrait()
        still("03_portrait_playing", state(deck = playing()), crateIndex = 1)
        val deck = compose.onNodeWithTag("turntable").getBoundsInRoot()
        val sleeve = compose.onNodeWithTag("sleeve:Night Drive").getBoundsInRoot()
        assertTrue("turntable ${deck.bottom} above crate ${sleeve.top}", deck.bottom <= sleeve.top)
        assertTrue((deck.right - deck.left) > (deck.bottom - deck.top))
        // The platter never idles here, so check the pill strip without scrolling it.
        compose.onNodeWithTag("nav:DIG").assertIsDisplayed()
        compose.onNodeWithTag("nav:SETTINGS").assertExists()
    }

    @Test
    fun `Settings opens on Sound, which controls Pure mode, hi-res and the EQ`() {
        portrait()
        val actions = object : RecordingActions() {
            var sound by mutableStateOf(SoundSettings())
            override fun updateSound(transform: (SoundSettings) -> SoundSettings) { sound = transform(sound) }
        }
        compose.setContent { RoomScreen(state(sound = actions.sound), actions) }
        compose.onNodeWithTag("nav:SETTINGS").performClick()
        compose.onNodeWithTag("panel-title").assertTextContains("SETTINGS")
        // A plain list first; Sound is one row of it, and the storage page is a two-finger swipe away.
        compose.onNodeWithTag("storage-hint").assertExists()
        compose.onNodeWithTag("settings:SOUND").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings:SOUND").performClick()
        compose.onNodeWithTag("panel:sound").assertIsDisplayed()

        compose.onNodeWithTag("fader:1k").performScrollTo().performTouchInput {
            swipe(center, center - Offset(0f, 140f), durationMillis = 300)
        }
        compose.waitForIdle()
        assertTrue("1 kHz boosted to ${actions.sound.graphic[5]}", actions.sound.graphic[5] > 1f)
        assertEquals("Custom", actions.sound.presetName)
        assertTrue(actions.sound.eqEnabled)

        compose.onNodeWithText("Warm vinyl", ignoreCase = true).performScrollTo().performClick()
        assertTrue(actions.log.contains("preset:Warm vinyl"))

        compose.onNodeWithTag("toggle:Hi-res output").performScrollTo().performClick()
        assertEquals(false, actions.sound.hiResOutput)
        compose.onNodeWithTag("toggle:Pure").performScrollTo().performClick()
        assertEquals(true, actions.sound.pure)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/04_sound_panel.png")

        // Inside a section the head's key goes back to the list, which closes as before.
        compose.onNodeWithTag("action:Close").assertDoesNotExist()
        compose.onNodeWithTag("action:Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("panel:sound").assertDoesNotExist()
        compose.onNodeWithTag("settings:SOUND").assertExists()
        compose.onNodeWithTag("action:Close").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("panel:settings").assertDoesNotExist()
    }

    @Test
    fun `Friday Drops shows the week and claims a record`() {
        portrait()
        val actions = RecordingActions()
        compose.setContent { RoomScreen(state(), actions, initialPanel = Panel.DROPS) }
        compose.onNodeWithText("Week of 16 October", ignoreCase = true).assertIsDisplayed()
        // Claims left are three TE lights; the drops stand on a shelf as sleeves.
        compose.onNodeWithContentDescription("2 of 3 claims left").assertIsDisplayed()
        compose.onNodeWithText("In your crate", ignoreCase = true).assertIsDisplayed() // already claimed
        compose.onNodeWithTag("drop:NS050").assertIsDisplayed()
        // No cover yet: a dotted record on grey with the drop's number stands in for it.
        compose.onNodeWithTag("drop-placeholder:NS050", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodes(hasText("Claim")).assertCountEquals(0)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/05_drops_panel.png")
        // A tap opens the drop: its licence, its tracks, and Claim inside.
        compose.onNodeWithTag("drop:NS050").performClick()
        compose.onNodeWithTag("drop-preview").assertIsDisplayed()
        compose.onNodeWithTag("drop-placeholder:NS050", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("CC BY-NC-SA 4.0", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithTag("claim:NS050").performScrollTo().performClick()
        assertTrue(actions.log.toString(), actions.log.contains("claim:NS050"))
    }

    @Test
    fun `an unloaded drop asks for this week's records`() {
        portrait()
        val actions = RecordingActions()
        compose.setContent { RoomScreen(state(drops = DropState.Idle), actions, initialPanel = Panel.DROPS) }
        compose.waitForIdle()
        assertTrue(actions.log.contains("refreshDrops"))
        compose.onNodeWithText("Digging through the archive…", ignoreCase = true).assertIsDisplayed()
    }

    @Test
    fun `private messages open by Soulseek username and send through the action`() {
        portrait()
        val actions = RecordingActions()
        compose.setContent { RoomScreen(state(contacts = listOf(SoulseekContact("maya", true))), actions, initialPanel = Panel.FRIENDS) }
        compose.onNodeWithTag("panel:messages").assertIsDisplayed()
        compose.onNodeWithText("maya").assertIsDisplayed()
        compose.onNodeWithTag("new-chat").assertIsDisplayed()
        compose.onNodeWithTag("friend:maya").performClick()
        assertTrue(actions.log.contains("soulseekConversation:maya"))
        compose.onNodeWithTag("field:Private message").performTextInput("Hi Maya")
        compose.onNodeWithTag("send-message").performClick()
        assertTrue(actions.log.contains("soulseekMessage:maya:Hi Maya"))
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/06_messages_panel.png")
    }

    @Test
    fun `Friends says messages live only on this phone, on its home and not inside a chat`() {
        portrait()
        compose.setContent { RoomScreen(state(contacts = listOf(SoulseekContact("maya", true))), RecordingActions(), initialPanel = Panel.FRIENDS) }
        compose.onNodeWithTag("messages-local-note").assertIsDisplayed()
        compose.onNodeWithText("messages stay on this phone; uninstalling sonic deletes them.").assertIsDisplayed()
        compose.onNodeWithTag("friend:maya").performClick()
        compose.onNodeWithTag("messages-local-note").assertDoesNotExist()
    }

    @Test
    fun `only Soulseek downloads can be sent, and their card opens that exact folder`() {
        portrait()
        val actions = object : RecordingActions() {
            override fun openSoulseekFolder(username: String, directory: String) { log += "openFolder:$username:$directory" }
        }
        // Nothing from Soulseek yet: the key is off and says why.
        var s by mutableStateOf(state(contacts = listOf(SoulseekContact("maya", true))).copy(soulseekConversation = "maya"))
        compose.setContent { RoomScreen(s, actions, initialPanel = Panel.FRIENDS) }
        compose.onNodeWithTag("share-record").assertIsNotEnabled()
        compose.onNodeWithTag("send-record-rule").assertIsDisplayed()
        // One download from ro's share: the picker offers just that, and the card carries its folder.
        val download = albums[0].copy(id = "slsk-1", source = Source.SOULSEEK, from = "ro", remoteFolder = "Music/Air/Moon Safari")
        s = s.copy(albums = albums + download)
        compose.waitForIdle()
        compose.onNodeWithTag("send-record-rule").assertDoesNotExist()
        compose.onNodeWithTag("share-record").performClick()
        compose.onAllNodesWithTag("pick-record:slsk-1").assertCountEquals(1)
        compose.onAllNodesWithTag("pick-record:${albums[0].id}").assertCountEquals(0)
        compose.onNodeWithTag("send-record").performClick()
        val sent = actions.log.single { it.startsWith("soulseekMessage:maya:") }.removePrefix("soulseekMessage:maya:")
        val card = app.runout.ui.RecordCard.parse(sent)!!
        assertEquals("ro" to "Music/Air/Moon Safari", card.peer to card.folder)
        // A card sent to you names the folder: "open folder" lists it, then Dig shows it.
        val received = app.runout.ui.RecordCard("Air", "Moon Safari", null, "ro", "Music/Air/Moon Safari").encode()
        s = s.copy(albums = albums, soulseekConversations = listOf(SoulseekConversation("maya", listOf(SoulseekMessage("9", "maya", received, 1_792_540_000_000L, outgoing = false, state = SoulseekMessageState.RECEIVED, read = true)), 0)))
        compose.waitForIdle()
        compose.onNodeWithTag("open-record-folder").performClick()
        assertTrue(actions.log.toString(), actions.log.contains("openFolder:ro:Music/Air/Moon Safari"))
        assertTrue(actions.log.none { it.startsWith("soulseekSearch:") })
    }

    @Test
    fun `Settings sends you to the one sign-in, which checks the ID and always keeps you signed in`() {
        portrait()
        val actions = RecordingActions()
        compose.setContent { RoomScreen(state(session = SoulseekSession()), actions, initialPanel = Panel.SETTINGS, initialSettings = SettingsSection.ACCOUNT) }
        // No second sign-in form and no "Remember me": Settings opens the same login as onboarding.
        compose.onNodeWithText("Remember my sign-in").assertDoesNotExist()
        compose.onNodeWithTag("account-sign-in").performScrollTo().performClick()
        compose.onNodeWithTag("screen:setup").assertIsDisplayed()
        compose.onNodeWithTag("field:Soulseek ID").performTextInput("अ")
        compose.onNodeWithTag("field:Password").performTextInput("test password")
        compose.onNodeWithTag("submit-soulseek").assertIsNotEnabled()
        compose.onNodeWithTag("field:Soulseek ID").performTextClearance()
        compose.onNodeWithTag("field:Soulseek ID").performTextInput("Abhi_99")
        compose.onNodeWithTag("field:Password").performTextInput("test password")
        // In landscape the collage stays and the page scrolls down to the key.
        compose.onNodeWithTag("submit-soulseek").performScrollTo().performClick()
        assertTrue(actions.log.toString(), actions.log.contains("soulseekSignIn:Abhi_99:true"))
        // The ⓘ explains that there is no sign-up page.
        compose.onNodeWithTag("soulseek-id-info").performScrollTo().performClick()
        compose.onNodeWithText("Soulseek has no sign-up page", substring = true, ignoreCase = true).assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/12_login.png")
        // The TE help panel closes with its "got it" key.
        compose.onNodeWithText("got it").performClick()
        compose.onNodeWithTag("setup-close").performClick()
        compose.onNodeWithTag("screen:setup").assertDoesNotExist()
    }

    @Test
    fun `Internet Archive claim actions are disabled without Soulseek sign in`() {
        portrait()
        val actions = RecordingActions()
        compose.setContent { RoomScreen(state(session = SoulseekSession()), actions, initialPanel = Panel.DROPS) }
        compose.onNodeWithText("Sign in with Soulseek", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithTag("drop:NS050").performScrollTo().performClick()
        compose.onNodeWithTag("claim:NS050").performScrollTo().performClick()
        assertTrue(actions.log.none { it.startsWith("claim:") })
    }

    @Test
    fun `the deck's keys and screen work, and it has no speed controls`() {
        val actions = RecordingActions()
        // Needle up, so the platter is still and the test can wait for the screen to settle.
        compose.setContent { RoomScreen(state(deck = playing().copy(isPlaying = false, needleDown = false)), actions) }
        compose.onNodeWithTag("deck:startstop").performClick()
        compose.onNodeWithTag("deck:next").performClick()
        compose.onNodeWithTag("deck:prev").performClick()
        compose.onNodeWithTag("deck:eject").performClick()
        assertEquals(listOf("startStop", "next", "prev", "eject"), actions.log.filter { it in setOf("startStop", "next", "prev", "eject") })
        compose.onNodeWithTag("deck:33").assertDoesNotExist()
        compose.onNodeWithTag("deck:45").assertDoesNotExist()
        compose.onNodeWithTag("deck:pitch").assertDoesNotExist()
        compose.onNodeWithTag("deck:screen").assertContentDescriptionContains("Lowlight", substring = true)
    }

    @Test
    fun `the footer is four icon tabs and the Pressing Plant is gone`() {
        portrait()
        compose.setContent { RoomScreen(state(), RecordingActions()) }
        listOf("DIG", "DROPS", "FRIENDS", "SETTINGS").forEach { compose.onNodeWithTag("nav:$it").assertIsDisplayed() }
        compose.onNodeWithTag("nav:PRESSING").assertDoesNotExist()
        compose.onNodeWithText("Pressing Plant").assertDoesNotExist()
        // The top bar (TE · Home): no clock, no folder keys (they live in Settings › Library); the count stays.
        compose.onNodeWithText("3:21 PM", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Change folder").assertDoesNotExist()
        compose.onNodeWithTag("action:Rescan").assertDoesNotExist()
        compose.onNodeWithText("5 rec").assertIsDisplayed()
    }

    @Test
    fun `places open as full pages under the nav in portrait`() {
        portrait()
        still("08_portrait_settings", state(), panel = Panel.SETTINGS)
        // TE · Settings: the 44/700 title at the top of the page, the nav bar still at the foot.
        val title = compose.onNodeWithTag("panel-title").getBoundsInRoot()
        assertTrue("the page's title sits at the top", title.top.value < 892 * 0.2f)
        compose.onNodeWithTag("nav:SETTINGS").assertIsDisplayed()
    }

    @Test
    fun `an empty folder explains what the scan found`() {
        val report = ScanReport(folders = 3, audioFiles = 0, unreadable = 0, albums = 0, otherFiles = mapOf("m4p" to 12))
        compose.setContent {
            RoomScreen(state(albums = emptyList()).copy(report = report), RecordingActions())
        }
        compose.onNodeWithTag("empty-title", useUnmergedTree = true).assertTextContains("NO ALBUMS FOUND")
        compose.onNodeWithTag("empty-detail", useUnmergedTree = true).assertTextContains("looked in 3 folders and found no music files (found .m4p ×12)", substring = true)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/09_empty_with_report.png")
    }

    @Test
    fun `messages appear and can be dismissed`() {
        var message by mutableStateOf<String?>("“Night Drive” from Maya is in your crate")
        val actions = object : RecordingActions() {
            override fun dismissMessage() { message = null }
        }
        compose.setContent { RoomScreen(state(message = message), actions) }
        compose.onNodeWithTag("message").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("message").assertDoesNotExist()
    }

    @Test
    fun `a Soulseek problem shows as the room's glass note and stays until tapped away`() {
        var problem by mutableStateOf<String?>("This Soulseek ID connected in another client. Reconnect when you are ready.")
        val actions = object : RecordingActions() {
            override fun clearSoulseekError() { problem = null }
        }
        compose.setContent { RoomScreen(state().copy(soulseekError = problem), actions) }
        compose.onNodeWithTag("message").assertIsDisplayed()
        compose.onNodeWithText("another client", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("message").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("message").assertDoesNotExist()
    }

    @Test
    @Config(application = RunoutApp::class)
    fun `the real app opens and takes a friend card shared from another app`() {
        val runout = androidx.test.core.app.ApplicationProvider.getApplicationContext<RunoutApp>()
        // A friend card made on someone else's phone, opened from a chat app.
        val card = File(runout.cacheDir, "Maya on Runout.vinyl")
        FileOutputStream(card).use {
            VinylPackage.write(
                it, VinylPackage.Kind.FRIEND, Profile("maya-id", "Maya", Profile.ACCENTS[1]), null,
                listOf(ShelfItem("Night Drive", "Vela Park", 2023, "FLAC", null)),
            )
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.fromFile(card))
            .setClass(runout, MainActivity::class.java)
        androidx.test.core.app.ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val deadline = System.currentTimeMillis() + 15_000
            while (runout.graph.social.friends.value.isEmpty() && System.currentTimeMillis() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(25)
            }
            assertEquals(listOf("Maya"), runout.graph.social.friends.value.map { it.profile.name })
            scenario.onActivity { assertTrue(it.window.decorView.isAttachedToWindow) }
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
        }
    }
}

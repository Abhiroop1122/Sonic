package app.runout

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.runout.discovery.DiscoveryOptions
import app.runout.discovery.DiscoveryState
import app.runout.discovery.SearchSource
import app.runout.library.ScanReport
import app.runout.library.UnreadableFile
import app.runout.playback.DeckState
import app.runout.soulseek.SoulseekConnection
import app.runout.soulseek.SoulseekPolicy
import app.runout.soulseek.SoulseekSession
import app.runout.sound.BitPerfect
import app.runout.sound.OutputDevice
import app.runout.sound.OutputKind
import app.runout.sound.OutputStatus
import app.runout.sound.SoundSettings
import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.onNodeWithContentDescription
import app.runout.ui.BrandText
import app.runout.ui.Room
import app.runout.ui.Wordmark
import app.runout.ui.brandMark
import app.runout.ui.Panel
import app.runout.ui.RoomScreen
import app.runout.ui.RoomState
import app.runout.ui.settings.SettingsSection
import app.runout.ui.signalPath
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The audio-quality chain, Crackle as a switch, Repair local albums, and page-wide sheets. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xhdpi", application = Application::class)
class SignalAndSheetsTest {
    @get:Rule val compose = createComposeRule()

    private val speaker48 = OutputDevice(OutputKind.SPEAKER, mixerRate = 48_000)
    private val playing = OutputStatus(24, 96_000, floatPath = true, processing = true, device = speaker48)
    private val room = RoomState(listOf(Fixtures.nightDrive()), true, null, DeckState(), true, "12:00")
        .copy(output = playing, soulseekSession = SoulseekSession(SoulseekConnection.CONNECTED, "listener"))

    @Test fun `a hi-res file on the phone speaker reads as lossless, resampled by Android's mixer`() {
        val path = signalPath(playing, SoundSettings())!!
        assertEquals("24-bit · 96 kHz", path.track.spec)
        assertEquals("32-bit float · 96 kHz", path.runout.spec)
        assertEquals("Mixer · 48 kHz", path.output.spec)
        assertEquals("Lossless, resampled to 48 kHz", path.verdict)
        assertEquals("24/96 → Phone speaker", path.short)
        assertTrue(path.note!!.contains("USB DAC"))
    }

    @Test fun `Pure to a bit-perfect USB DAC keeps the file's own format all the way`() {
        val dac = playing.copy(processing = false, bitPerfect = BitPerfect.ON, device = OutputDevice(OutputKind.USB, "FiiO KA3", 48_000))
        val path = signalPath(dac, SoundSettings(pure = true))!!
        assertEquals("Bit-perfect", path.verdict)
        assertEquals("Pure", path.runout.title)
        assertEquals("24-bit · 96 kHz", path.output.spec)
        assertEquals("USB DAC · FiiO KA3", path.output.title)
        assertNull(path.note)
    }

    @Test fun `Bluetooth is lossy, and Hi-res off cuts 24-bit to 16`() {
        val bt = signalPath(playing.copy(device = OutputDevice(OutputKind.BLUETOOTH, "Buds")), SoundSettings())!!
        assertEquals("Lossy over Bluetooth", bt.verdict)
        assertFalse(bt.output.ok)
        val folded = signalPath(playing.copy(floatPath = false), SoundSettings(hiResOutput = false))!!
        assertEquals("Cut to 16-bit", folded.verdict)
        assertFalse(folded.runout.ok)
        assertNull(signalPath(OutputStatus(), SoundSettings()))
    }

    @Test fun `a long press on the deck's screen opens the Audio quality sheet`() {
        compose.setContent { RoomScreen(room, RecordingActions()) }
        compose.onNodeWithTag("signal-chip").assertDoesNotExist()
        compose.onNodeWithTag("deck:screen").performTouchInput { longClick() }
        compose.onNodeWithTag("panel:signal").assertIsDisplayed()
        compose.onNodeWithTag("signal-verdict").assertTextEquals("lossless, resampled to 48 khz")
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/30_audio_quality.png")
    }

    @Test fun `Crackle is a switch in Settings › Sound, not on the home screen`() {
        val actions = RecordingActions()
        compose.setContent { RoomScreen(room, actions, initialPanel = Panel.SETTINGS, initialSettings = SettingsSection.SOUND) }
        compose.onNodeWithTag("toggle-crackle").assertDoesNotExist()
        compose.onNodeWithTag("toggle:Crackle").performScrollTo().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Switch))
        compose.onNodeWithTag("toggle:Crackle").performClick()
        assertTrue(actions.log.contains("crackle"))
    }

    @Test fun `the signal chain is what the LED opens, with its verdict and the track's format`() {
        compose.setContent { RoomScreen(room, RecordingActions(), initialPanel = Panel.SIGNAL) }
        compose.onNodeWithTag("signal-path").assertExists()
        compose.onNodeWithTag("signal-verdict").assertTextEquals("lossless, resampled to 48 khz")
        compose.onNodeWithTag("spec:Track").assertExists()
        // Only the station that changes the music says why: here the output resamples, the file doesn't.
        compose.onNodeWithTag("why:Output", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("why:Track", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun `Settings is the rows and the way to storage, and Sound starts at output with crackle first`() {
        val actions = RecordingActions()
        compose.setContent { RoomScreen(room, actions, initialPanel = Panel.SETTINGS) }
        // No signal chain here any more (the LED opens it): the rows, then the two-finger hint under them.
        compose.onNodeWithTag("signal-path").assertDoesNotExist()
        assertTrue(compose.onNodeWithTag("settings:ABOUT").getBoundsInRoot().bottom <= compose.onNodeWithTag("storage-hint").getBoundsInRoot().top)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/35_settings_list.png")
        compose.onNodeWithTag("settings:SOUND").performScrollTo().performClick()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/36_sound_output.png")
        compose.onNodeWithTag("panel:sound").assertExists()
        // The chain heads Settings › Sound: how what's playing reaches you, before what shapes it.
        compose.onNodeWithTag("signal-path").assertExists()
        compose.onNodeWithText("01 signal").assertExists()
        compose.onNodeWithText("02 output").assertExists()
        compose.onNodeWithText("03 equaliser").assertExists()
        compose.onNodeWithText("04 loudness").assertExists()
        compose.onNodeWithText("05 the deck").assertDoesNotExist()
        assertTrue(compose.onNodeWithTag("toggle:Crackle").getBoundsInRoot().top < compose.onNodeWithTag("toggle:Pure").getBoundsInRoot().top)
        // "import autoeq…" is an outlined key now.
        compose.onNodeWithTag("import-autoeq").performScrollTo().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button)).performClick()
        assertTrue(actions.log.contains("autoeq"))
    }

    @Test fun `Settings › Soulseek has no review-setup key`() {
        compose.setContent { RoomScreen(room, RecordingActions(), initialPanel = Panel.SETTINGS, initialSettings = SettingsSection.ACCOUNT) }
        compose.onNodeWithTag("settings-downloads").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings-setup").assertDoesNotExist()
        compose.onNodeWithText("review soulseek setup").assertDoesNotExist()
    }

    @Test fun `the brand's full stop is a round signal-orange dot`() {
        compose.setContent {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Color.White)) {
                Wordmark(96.sp)
                BrandText(brandMark() + androidx.compose.ui.text.AnnotatedString(" 0.7"), Room.Display.copy(fontSize = 66.sp))
            }
        }
        compose.onNodeWithContentDescription("Sonic").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/brand_dot.png")
    }

    @Test fun `About credits fuze2k and 0xs0m, whose name opens somm·tf`() {
        compose.setContent { RoomScreen(room, RecordingActions(), initialPanel = Panel.SETTINGS, initialSettings = SettingsSection.ABOUT) }
        compose.onNodeWithText("credits").assertExists()
        compose.onNodeWithText("fuze2k and ").assertExists()
        compose.onNodeWithText("records").assertExists() // the other facts stay
        compose.onNodeWithTag("credits-link").assertTextEquals("0xs0m").performScrollTo().performClick()
        val started = org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_VIEW, started.action)
        assertEquals("https://somm.tf", started.dataString)
    }

    @Test fun `after a folder change, Settings › Library asks whether sharing should follow`() {
        val answers = mutableListOf<Boolean>()
        val actions = object : RecordingActions() { override fun answerShareSwap(change: Boolean) { answers += change } }
        compose.setContent { RoomScreen(room.copy(offerShareSwap = true, sharedFolder = "Music"), actions, initialPanel = Panel.SETTINGS, initialSettings = SettingsSection.LIBRARY) }
        compose.onNodeWithTag("share-swap").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("share the new folder too?").assertIsDisplayed()
        compose.onNodeWithText("choose new shared folder").assertIsDisplayed()
        compose.onNodeWithTag("share-swap-change").performClick()
        compose.onNodeWithTag("share-swap-keep").performClick()
        assertEquals(listOf(true, false), answers)
    }

    @Test fun `the share question is never shown unless offered`() {
        compose.setContent { RoomScreen(room.copy(sharedFolder = "Music"), RecordingActions(), initialPanel = Panel.SETTINGS, initialSettings = SettingsSection.LIBRARY) }
        compose.onNodeWithTag("settings-change-folder").assertExists()
        compose.onNodeWithTag("share-swap").assertDoesNotExist()
    }

    @Test fun `the share question comes only after a real change while signed in, with something shared`() {
        val a = "content://docs/tree/primary%3AMusic"; val b = "content://docs/tree/primary%3AFLAC"
        assertTrue(offersShareSwap(a, b, signedIn = true, shared = a))
        assertFalse("offline or no account: never ask", offersShareSwap(a, b, signedIn = false, shared = a))
        assertFalse("the first folder isn't a change", offersShareSwap(null, b, signedIn = true, shared = a))
        assertFalse("the same folder again isn't a change", offersShareSwap(a, a, signedIn = true, shared = a))
        assertFalse("nothing shared, nothing to follow", offersShareSwap(a, b, signedIn = true, shared = null))
    }

    @Test fun `Settings › Library repairs local albums and lists the files it couldn't read`() {
        val actions = RecordingActions()
        val report = ScanReport(4, 12, 2, 3, emptyMap(), unreadableFiles = listOf(
            UnreadableFile("Music/Air/Moon Safari/03 All I Need.flac", "An MP3 (or other format) renamed to .flac. Only real FLAC plays in Runout."),
            UnreadableFile("Music/Air/Moon Safari/04 Kelly.flac", "Damaged FLAC: the header or the first audio frame is broken, or the download was cut off."),
        ))
        compose.setContent { RoomScreen(room.copy(report = report), actions, initialPanel = Panel.SETTINGS, initialSettings = SettingsSection.LIBRARY) }
        compose.onNodeWithTag("settings-repair").performScrollTo().performClick()
        assertTrue(actions.log.contains("repair"))
        compose.onNodeWithTag("repair-report").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2 files couldn't be read").assertExists()
        compose.onNodeWithText("Music/Air/Moon Safari/03 All I Need.flac", ignoreCase = true).assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/31_repair_library.png")
    }

    @Test fun `Dig searches with a round key, and its filters sheet shades the whole page`() {
        val actions = RecordingActions()
        val dig = room.copy(discovery = DiscoveryState("moon safari", DiscoveryOptions(source = SearchSource.SOULSEEK)))
        compose.setContent { RoomScreen(dig, actions, initialPanel = Panel.DIG) }
        compose.onNodeWithText("Search Soulseek").assertDoesNotExist()
        compose.onNodeWithTag("submit-search").assertIsDisplayed().performClick()
        assertTrue(actions.log.contains("soulseekSearch:moon safari"))

        compose.onNodeWithTag("search-filters").performClick()
        compose.onNodeWithTag("search-filter-card").assertIsDisplayed()
        val root = compose.onRoot().getBoundsInRoot()
        val shade = compose.onNodeWithTag("sheet-shade").getBoundsInRoot()
        assertEquals(root, shade)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/32_dig_filters_sheet.png")
        compose.onNodeWithTag("sheet-shade").performTouchInput { click(topCenter + androidx.compose.ui.geometry.Offset(0f, 40f)) }
        compose.onNodeWithTag("search-filter-card").assertDoesNotExist()
    }

    @Test fun `the new chat sheet shades the whole Friends page`() {
        compose.setContent { RoomScreen(room, RecordingActions(), initialPanel = Panel.FRIENDS) }
        compose.onNodeWithTag("new-chat").performClick()
        compose.onNodeWithTag("new-chat-card").assertIsDisplayed()
        assertEquals(compose.onRoot().getBoundsInRoot(), compose.onNodeWithTag("sheet-shade").getBoundsInRoot())
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/33_new_chat_sheet.png")
    }

    @Test fun `chat bubbles are solid, mine black and theirs orange, over an outlined composer`() {
        val chat = room.copy(
            soulseekConversation = "maya",
            soulseekConversations = listOf(app.runout.soulseek.SoulseekConversation("maya", listOf(
                app.runout.soulseek.SoulseekMessage("1", "maya", "hello", 1L, false, app.runout.soulseek.SoulseekMessageState.SENT),
                app.runout.soulseek.SoulseekMessage("2", "maya", "hi back", 2L, true, app.runout.soulseek.SoulseekMessageState.SENT),
            ), 0)),
        )
        compose.setContent { RoomScreen(chat, RecordingActions(), initialPanel = Panel.FRIENDS) }
        compose.onNodeWithTag("bubble:theirs").assertTextEquals("hello")
        compose.onNodeWithTag("bubble:mine").assertTextEquals("hi back")
        // Theirs sits at the left, yours at the right.
        assertTrue(compose.onNodeWithTag("bubble:theirs").getBoundsInRoot().left < compose.onNodeWithTag("bubble:mine").getBoundsInRoot().left)
        // The composer stands clear of the body's left edge.
        assertTrue(compose.onNodeWithTag("field:Private message").getBoundsInRoot().left > compose.onNodeWithTag("bubble:theirs").getBoundsInRoot().left)
        // The composer's key opens their shelf.
        compose.onNodeWithContentDescription("Browse their shelf").assertIsEnabled()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/34_chat.png")
    }

    @Test fun `an interrupted read is recognised as Android pausing the app`() {
        assertTrue(SoulseekPolicy.isInterruption("Stream read error: Interrupted system call (os error 4)"))
        assertFalse(SoulseekPolicy.isInterruption("Stream read error: Connection reset by peer (os error 104)"))
        assertFalse(SoulseekPolicy.isInterruption(null))
    }
}

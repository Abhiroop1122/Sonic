package app.runout

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.runout.model.Album
import app.runout.model.AudioQuality
import app.runout.model.Source
import app.runout.model.Track
import app.runout.playback.DeckState
import app.runout.ui.RoomScreen
import app.runout.ui.RoomState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w892dp-h412dp-land-xhdpi", application = Application::class)
class Slice0RemovalTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private fun open(source: Source): MutableList<String> {
        val removed = mutableListOf<String>()
        val album = Album("record", "Keep record", "Artist", null, "", listOf(Track("track", "content://test/track.flac", "Track", 1, 1, 60000)), AudioQuality("FLAC"), source = source)
        val actions = object : RecordingActions() {
            override fun removeRecord(album: Album) { removed += album.id }
        }
        compose.setContent { RoomScreen(RoomState(listOf(album), true, null, DeckState(), false, "12:00"), actions, initialPulledId = album.id) }
        askToRemove()
        return removed
    }

    /** Remove lives in the record's details, opened by a long press. */
    private fun askToRemove() {
        compose.onNodeWithTag("pulled-disc").performTouchInput { longClick() }
        compose.onNodeWithTag("details:remove").performScrollTo().performClick()
    }

    @Test fun `received record is not removed until confirmed`() {
        val removed = open(Source.RECEIVED)
        assertEquals(emptyList<String>(), removed)
        compose.onNodeWithText("Remove record?").assertIsDisplayed()
        compose.onNodeWithText("“keep record” and its received audio will be deleted from this device.").assertIsDisplayed()
        compose.onNodeWithText("keep it").performClick()
        assertEquals(emptyList<String>(), removed)
        askToRemove()
        compose.onNodeWithText("remove record").performClick()
        assertEquals(listOf("record"), removed)
    }

    @Test fun `pressed record is not removed until confirmed`() {
        val removed = open(Source.PRESSING)
        assertEquals(emptyList<String>(), removed)
        compose.onNodeWithText("Remove record?").assertIsDisplayed()
        compose.onNodeWithText("remove record").performClick()
        assertEquals(listOf("record"), removed)
    }

    @Test fun `Back dismisses removal without deleting received audio`() {
        val removed = open(Source.RECEIVED)
        compose.onNodeWithText("Remove record?").assertIsDisplayed()
        // The glass confirm lives in the room, so Back reaches it through the activity dispatcher.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Remove record?").assertDoesNotExist()
        assertEquals(emptyList<String>(), removed)
    }
}

package app.runout.ui.settings

import app.runout.update.UpdateChannel
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.runout.drops.DropItem
import app.runout.drops.DropState
import app.runout.drops.DropsRepository
import app.runout.model.Album
import app.runout.model.Licences
import app.runout.model.Source
import app.runout.sound.Presets
import app.runout.sound.ReplayGainMode
import app.runout.sound.SoundSettings
import app.runout.ui.*
import kotlin.math.abs
import kotlin.math.roundToInt

private val HelpAutoFlip = TeHelp("help · flags", "auto-flip", listOf(
    "when a side runs out, the record turns over and the next side plays from its first song.",
    "after the last side it rests, the way a real deck would.",
))

private val HelpAutoPlay = TeHelp("help · flags", "auto-play", listOf(
    "putting a record on the platter lowers the needle by itself.",
    "turn it off to put records on first and start them from the deck.",
))

private val HelpIntro = TeHelp("help · flags", "intro on launch", listOf(
    "each time sonic opens, the sleeve slides in and its record drops onto the deck, as on the very first day. no sign-in or setup steps after it.",
    "tap anywhere to skip it. it plays in light or dark, on the deck you're using.",
))

private val HelpUpdates = TeHelp("help · flags", "check for updates", listOf(
    "once a day, when sonic opens, it asks github for the latest release of sonic. nothing about you or your music is sent.",
    "when there's a newer one, settings shows it with a link to its page, where the apk is. you install it yourself; sonic never downloads it.",
    "off, it only checks when you ask in settings › about.",
    "stable offers tested releases. development also offers pre-releases, the newest features first. both are the same app and update each other.",
))

private val HelpDeckLights = TeHelp("help · flags", "deck lights", listOf(
    "the neon deck stands in the dark: its edges glow on their own, and only a dim light falls on the record.",
    "on, the room's studio lights come up, and the black deck shows its shape and shine.",
))

private val HelpVinylOnly = TeHelp("help · flags", "vinyl only", listOf(
    "turn the phone sideways and everything but the deck goes black: the top bar, the crate and the track list. the deck moves to the middle.",
    "on an oled screen black pixels are off, so a phone left playing uses less battery. tap anywhere to bring the room back; it goes dark again after ten quiet seconds.",
))

private val HelpSleep = TeHelp("help · flags", "sleep mode", listOf(
    "puts a moon in the room's top bar. tap it to stop the music in 15 to 50 minutes.",
    "when the time is up the music fades out over a few seconds and the needle lifts.",
))

/**
 * Settings › Flags: 01 the deck's habits, each a switch; 02 the theme (the phone's, or light or
 * dark pinned); 03 which RG-1 stands in the room (the theme's, or one of the three pinned);
 * 04 updates: the daily check and which builds it looks for.
 */
@Composable
internal fun FlagsPage(state: RoomState, actions: RoomActions) {
    var help by remember { mutableStateOf<TeHelp?>(null) }
    val f = state.deckFlags
    Column(Modifier.fillMaxWidth().testTag("panel:flags"), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        PageSection("01 habits") {
            Column(Modifier.fillMaxWidth()) {
                HelpToggle("Auto-flip", "Side A over, side B plays.", f.autoFlip, actions::setAutoFlip) { help = HelpAutoFlip }
                HelpToggle("Auto-play", "A record on the platter starts playing.", f.autoPlay, actions::setAutoPlay) { help = HelpAutoPlay }
                HelpToggle("Sleep mode", "A moon in the room for the sleep timer.", f.sleepMode, actions::setSleepMode) { help = HelpSleep }
                HelpToggle("Intro on launch", "The needle drop, each time sonic opens.", state.look.introOnLaunch, actions::setIntroOnLaunch) { help = HelpIntro }
            }
        }
        PageSection("02 theme") {
            BasicText("phone follows your phone's dark mode. dark is true black, easy on an oled screen.", style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp))
            Segments(ThemeMode.entries, state.look.theme, { it.label }, tag = { "theme:${it.name}" }, onSelect = actions::setTheme)
        }
        PageSection("03 deck") {
            BasicText("theme puts the neon deck in a dark room and white / teal in a light one. neon is for dark rooms only.", style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp))
            Segments(DeckSkin.entries.filter { !it.darkOnly || Room.dark }, state.look.deck, { it.label }, tag = { "deck:${it.name}" }, onSelect = actions::setDeckSkin)
            Column(Modifier.fillMaxWidth()) {
                if (state.look.deckFor(Room.dark) == DeckSkin.NEON) {
                    HelpToggle("Deck lights", "Off, only the deck's edges and the record glow.", state.look.deckLights, actions::setDeckLights) { help = HelpDeckLights }
                }
                HelpToggle("Vinyl only", "In landscape, everything but the deck goes dark.", state.look.vinylOnly, actions::setVinylOnly) { help = HelpVinylOnly }
            }
        }
        // F-Droid builds leave the updates to F-Droid.
        if (app.runout.BuildConfig.UPDATE_CHECK) PageSection("04 updates") {
            Column(Modifier.fillMaxWidth()) {
                HelpToggle("Check for updates", "Once a day, sonic asks github for a newer release.", state.autoUpdates, actions::setAutoUpdates) { help = HelpUpdates }
            }
            BasicText("stable gets tested releases. development gets the newest features first, with rougher edges.", style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp))
            Segments(
                UpdateChannel.entries,
                state.updateChannel ?: UpdateChannel.STABLE,
                { it.label },
                tag = { "channel:${it.name}" },
                onSelect = actions::setUpdateChannel,
            )
        }
    }
    TeHelpSheet(help) { help = null }
}

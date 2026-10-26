package app.runout.ui.settings

import app.runout.update.UpdateState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.runout.BuildConfig
import app.runout.R
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

/** The sections of the Settings sheet. */
enum class SettingsSection(val label: String) {
    SOUND("Sound"), FLAGS("Flags"), LIBRARY("Library"), ACCOUNT("Soulseek"), CONNECTION("Connection"), ABOUT("About"),
    /** Not in the list: two fingers swiped up or down on Settings open it. */
    STORAGE("Storage"),
}

/** The sections the Settings list shows, in order. */
internal val LISTED_SECTIONS = SettingsSection.entries.filter { it != SettingsSection.STORAGE }

/** "0.6", as the boards write it: the version without a trailing ".0". */
private val VERSION = BuildConfig.VERSION_NAME.removeSuffix(".0")

/** Where the credits' "0xs0m" goes. */
internal const val CREDITS_URL = "https://somm.tf"

/**
 * Settings, as the TE · Settings boards. [section] null is the list: the numbered rows, a quiet line
 * with two fingers saying the storage page is a swipe away, and the faint SONIC. wordmark at the foot.
 * Each row opens its own page (the head shows its name), which starts with "← settings". A page
 * rises from below into place; going back it drops away and the list settles in again.
 */
@Composable
fun SettingsPanel(
    state: RoomState,
    actions: RoomActions,
    section: SettingsSection?,
    onSection: (SettingsSection?) -> Unit,
    onDownloads: () -> Unit,
    onSetup: () -> Unit,
    onOffline: () -> Unit,
) {
    AnimatedContent(
        targetState = section,
        transitionSpec = {
            if (initialState == null && targetState != null) {
                (slideInVertically(tween(340, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(240))) togetherWith
                    (slideOutVertically(tween(340, easing = FastOutSlowInEasing)) { -it / 8 } + fadeOut(tween(180)))
            } else {
                (slideInVertically(tween(300, easing = FastOutSlowInEasing)) { -it / 8 } + fadeIn(tween(240))) togetherWith
                    (slideOutVertically(tween(300, easing = FastOutSlowInEasing)) { it / 3 } + fadeOut(tween(180)))
            }
        },
        label = "settings-page",
    ) { shown ->
        if (shown == null) SettingsHome(state, actions, onSection)
        else SettingsPage(state, actions, shown, onSection, onDownloads, onSetup, onOffline)
    }
}

/**
 * The list: a newer release when there is one, the five rows, the way to the storage page, and the
 * wordmark set faint at the foot.
 */
@Composable
private fun SettingsHome(state: RoomState, actions: RoomActions, onSection: (SettingsSection?) -> Unit) {
    val found = rememberTick(HapticFeedbackType.GestureThresholdActivate)
    Column(
        Modifier
            .fillMaxWidth()
            .fillPageHeight()
            // Two fingers up the page: the storage page, kept off the list.
            .twoFingerSwipe(up = true) { found(); onSection(SettingsSection.STORAGE) }
            .testTag("panel:settings"),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        (state.update as? UpdateState.Available)?.let { UpdateNotice(it.release, actions) }
        Column(Modifier.fillMaxWidth()) {
            LISTED_SECTIONS.forEachIndexed { i, s ->
                TeRow("%02d".format(i + 1), s.label.lowercase(), summary(s, state), { onSection(s) }, Modifier.testTag("settings:${s.name}"))
            }
        }
        StorageHint()
        Spacer(Modifier.weight(1f))
        // The wordmark set huge and faint underneath, like a manual's back page.
        BrandText(brandMark(Room.HairlineSoft), Room.Display.copy(fontSize = 66.sp, color = Room.HairlineSoft), Modifier.clearAndSetSemantics {})
    }
}

/** Two fingers rising and settling, and what they open. */
@Composable
private fun StorageHint() {
    val bob = rememberInfiniteTransition(label = "storage-hint")
    val lift by bob.animateFloat(0f, -1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "lift")
    Row(
        Modifier.fillMaxWidth().testTag("storage-hint").semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Canvas(Modifier.size(20.dp)) { drawTwoFingerSwipe(Room.Quiet, lift) }
        BasicText("swipe up with two fingers to see what your music takes up", style = Room.LabelQuiet.copy(lineHeight = 15.sp))
    }
}

/** A section's own page, under "← settings". */
@Composable
private fun SettingsPage(
    state: RoomState,
    actions: RoomActions,
    section: SettingsSection,
    onSection: (SettingsSection?) -> Unit,
    onDownloads: () -> Unit,
    onSetup: () -> Unit,
    onOffline: () -> Unit,
) {
    BackHandler { onSection(null) }
    PageStep { onSection(null) }
    val back = rememberTick(HapticFeedbackType.GestureThresholdActivate)
    Column(
        Modifier
            .fillMaxWidth()
            // Storage too, so two fingers anywhere below its last line still take it away.
            .then(if (section == SettingsSection.ABOUT || section == SettingsSection.STORAGE) Modifier.fillPageHeight() else Modifier)
            // Storage came up with two fingers; two fingers down take it away again.
            .then(if (section == SettingsSection.STORAGE) Modifier.twoFingerSwipe(up = false) { back(); onSection(null) } else Modifier)
            .testTag("panel:settings"),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        PageBackLink("← settings", "settings-back") { onSection(null) }
        when (section) {
            SettingsSection.SOUND -> SoundPanel(state.sound, state.crackleOn, actions, state.output, state)
            SettingsSection.ACCOUNT -> {
                ConnectionNotice(state, actions, onSetup)
                SoulseekAccountPanel(state, actions, onOffline, onSignIn = onSetup, onDownloads = onDownloads)
            }
            SettingsSection.CONNECTION -> ConnectionPage(state)
            SettingsSection.LIBRARY -> LibraryPage(state, actions)
            SettingsSection.FLAGS -> FlagsPage(state, actions)
            SettingsSection.ABOUT -> AboutPage(state, actions)
            SettingsSection.STORAGE -> StoragePage(state, actions)
        }
    }
}

private fun summary(s: SettingsSection, state: RoomState): String = when (s) {
    SettingsSection.SOUND -> {
        val eq = when {
            !state.sound.eqEnabled -> "eq off"
            state.sound.useParametric -> "autoeq"
            else -> "eq ${state.sound.presetName.lowercase()}"
        }
        "$eq · pure ${if (state.sound.pure) "on" else "off"}"
    }
    SettingsSection.ACCOUNT ->
        if (state.soulseekSession.hasAccount) "${state.soulseekSession.username} · ${connectionLabel(state.soulseekSession)}".lowercase() else "not signed in"
    SettingsSection.CONNECTION -> connectionSummary(state)
    SettingsSection.LIBRARY -> "${recordCount(state.albums.size)} · ${if (state.hasFolder) "1 folder" else "no folder"}"
    SettingsSection.FLAGS -> listOfNotNull(
        "auto-flip".takeIf { state.deckFlags.autoFlip }, "auto-play".takeIf { state.deckFlags.autoPlay }, "sleep".takeIf { state.deckFlags.sleepMode },
        "intro".takeIf { state.look.introOnLaunch },
    ).joinToString(" · ").ifEmpty { "all off" } + (if (state.look.theme != ThemeMode.PHONE) " · ${state.look.theme.label}" else "")
    SettingsSection.ABOUT -> "sonic $VERSION · " +
        ((state.update as? UpdateState.Available)?.let { "${it.release.version} is out" } ?: "rg-1")
    SettingsSection.STORAGE -> ""
}

private val HelpCovers = TeHelp(
    "help · library › covers", "find missing covers online",
    listOf(
        "when an album has no cover, sonic sends its artist and title to musicbrainz, the cover art archive and the internet archive.",
        "nothing else leaves your phone: no files, no listening history.",
        "off: albums without art get a plain sleeve.",
    ),
)

/**
 * Settings › Library: 01 music folder (what a record is, the scan in an ink-ruled stats strip, change
 * folder and rescan, and after a change while signed in, whether the shared folder should follow),
 * 02 download folder (where Soulseek and Drops downloads land), 03 covers (the online lookup) and
 * 04 repair local albums (the black repair key and, after a repair, the files that couldn't be read
 * in a red-ruled box, each with its reason).
 */
@Composable
private fun LibraryPage(state: RoomState, actions: RoomActions) {
    var help by remember { mutableStateOf<TeHelp?>(null) }
    PageSection("01 music folder") {
        BasicText(
            if (state.hasFolder) "each album folder is a record in the crate." else "no music folder yet. each album folder in it becomes a record in the crate.",
            style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp),
        )
        state.report?.let { r ->
            if (r.error != null) BasicText(r.summary.lowercase(), style = Room.Label.copy(color = Room.Danger, lineHeight = 15.sp))
            else LibraryStats(r.albums, r.audioFiles, r.folders, r.unreadable)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            // Change folder lives here now, not on the home screen.
            if (state.hasFolder) {
                TeButton("change folder", actions::pickFolder, Modifier.weight(1f).testTag("settings-change-folder"), kind = KeyKind.OUTLINE)
                TeButton("rescan", actions::rescan, Modifier.weight(1f), kind = KeyKind.OUTLINE)
            } else {
                TeButton("choose folder", actions::pickFolder, Modifier.testTag("settings-change-folder"))
            }
        }
        if (state.offerShareSwap) ShareSwapNote(state.sharedFolder, actions)
    }
    PageSection("02 download folder") {
        BasicText(
            if (state.downloadFolder == null) "choose a folder before downloading. flac files in it stay readable by other players and stay after you uninstall sonic."
            else "soulseek and drops downloads land here. they stay after you uninstall sonic.",
            style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp),
        )
        FolderRow(state.downloadFolder, actions::pickDownloadFolder, "download-folder")
    }
    PageSection("03 covers") {
        HelpToggle(
            "Find missing covers online",
            "Sends artist and album title to MusicBrainz, the Cover Art Archive and the Internet Archive. Off unless you opt in.",
            state.coverLookupEnabled, actions::setCoverLookupEnabled,
        ) { help = HelpCovers }
    }
    if (state.hasFolder) RepairLibrary(state, actions)
    TeHelpSheet(help) { help = null }
}

/**
 * Asked once after the music folder changes while signed in to Soulseek: a grey tile with an orange
 * lamp, "share the new folder too?", what is shared now, and two keys. The new shared folder goes
 * through its own picker, so Android asks for its permission separately.
 */
@Composable
private fun ShareSwapNote(shared: String?, actions: RoomActions) {
    Column(
        Modifier.fillMaxWidth().background(Room.Surface).padding(16.dp).testTag("share-swap"),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(Room.Signal))
            BasicText("share the new folder too?", style = Room.Strong)
        }
        BasicText(
            "your music folder changed. soulseek still shares ${shared?.let { "“${it.lowercase()}”" } ?: "your old folder"}. " +
                "choose a new shared folder and android asks for it on its own.",
            style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp),
        )
        TeButton("choose new shared folder", { actions.answerShareSwap(true) }, Modifier.fillMaxWidth().testTag("share-swap-change"))
        TeButton("keep sharing the old one", { actions.answerShareSwap(false) }, Modifier.fillMaxWidth().testTag("share-swap-keep"), kind = KeyKind.OUTLINE)
    }
}

/** The scan as a TE stats strip, ruled in ink above and below; unreadable files count in red. */
@Composable
private fun LibraryStats(albums: Int, files: Int, folders: Int, unreadable: Int) {
    Row(Modifier.fillMaxWidth().hairlineTop(Room.Ink).hairlineBottom(Room.Ink)) {
        listOf(
            Triple("$albums", "albums", Room.Ink),
            Triple("$files", "music files", Room.Ink),
            Triple("$folders", "folders", Room.Ink),
            Triple("$unreadable", "unreadable", if (unreadable > 0) Room.Danger else Room.Ink),
        ).forEach { (value, label, colour) ->
            Column(Modifier.weight(1f).padding(vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(value, style = Room.Title.copy(color = colour, lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified))
                BasicText(label, style = Room.LabelQuiet)
            }
        }
    }
}

/**
 * Repair local albums: rebuilds the shelf from the folder alone and lists each .flac file that
 * couldn't be read, with the reason. The folder is opened read-only, so the files themselves
 * aren't rewritten; the reasons say what to do with them.
 */
@Composable
private fun RepairLibrary(state: RoomState, actions: RoomActions) {
    PageSection("04 repair local albums") {
        BasicText(
            "albums missing or vanishing? rebuild the shelf from your folder. sonic forgets its saved list, reads every album again, " +
                "and shows each file it couldn't play and why. your music files are never changed.",
            style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp),
        )
        val scanning = state.scan != null
        TeButton(
            if (scanning) "reading your folder…" else "repair local albums",
            { if (!scanning) actions.repairLibrary() },
            Modifier.testTag("settings-repair"),
            kind = if (scanning) KeyKind.OUTLINE else KeyKind.FILLED,
        )
        val broken = state.report?.unreadableFiles.orEmpty()
        if (broken.isEmpty()) return@PageSection
        val total = state.report?.unreadable ?: broken.size
        Column(Modifier.fillMaxWidth().border(1.dp, Room.Danger).testTag("repair-report")) {
            BasicText(
                "$total ${if (total == 1) "file" else "files"} couldn't be read",
                style = Room.Label.copy(color = Color.White),
                modifier = Modifier.fillMaxWidth().background(Room.Danger).padding(11.dp),
            )
            broken.take(SHOWN_BROKEN).forEach { f ->
                Column(Modifier.fillMaxWidth().hairlineTop().padding(11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicText(f.path.lowercase(), style = Room.Label.copy(lineHeight = 15.sp), maxLines = 2)
                    BasicText(f.reason.lowercase(), style = Room.LabelQuiet.copy(lineHeight = 15.sp))
                }
            }
            if (total > SHOWN_BROKEN) BasicText("and ${total - SHOWN_BROKEN} more", style = Room.LabelQuiet, modifier = Modifier.fillMaxWidth().hairlineTop().padding(11.dp))
        }
        BasicText(
            "to fix one: re-download or re-rip it as real flac, or check it with flac -t on a computer. then tap repair again.",
            style = Room.LabelQuiet.copy(lineHeight = 15.sp),
        )
    }
}

private const val SHOWN_BROKEN = 50

/**
 * Settings › About: the facts at the foot of the page, one per hairline row (an 88 dp grey key and
 * the 10/400 text), the credits last, and under an ink rule the version in 33/700 and the build line.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.AboutPage(state: RoomState, actions: RoomActions) {
    Spacer(Modifier.weight(1f))
    val fonts = (if (TeType.isArray) "array by indian type foundry, via fontshare: itf free font license. " else "") +
        "inter and silkscreen: sil open font license."
    Column(Modifier.fillMaxWidth()) {
        listOf(
            "records" to "your flac records play offline, with no account.",
            "soulseek" to "optional. finds and downloads flac and carries private messages. its protocol does not encrypt sign-in or messages.",
            "drops" to "friday drops come from the internet archive under their own licences.",
            "fonts" to fonts,
            "libraries" to "backdrop, filament, media3, androidx: apache 2.0. soulseek core: mit.",
        ).forEach { (key, value) ->
            Row(Modifier.fillMaxWidth().hairlineTop().padding(vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BasicText(key, style = Room.LabelQuiet, modifier = Modifier.width(88.dp))
                BasicText(value, style = Room.Label.copy(lineHeight = 15.sp), modifier = Modifier.weight(1f))
            }
        }
        // Who made it: "0xs0m" in blue opens somm.tf in the browser.
        val context = LocalContext.current
        Row(Modifier.fillMaxWidth().hairlineTop().padding(vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BasicText("credits", style = Room.LabelQuiet, modifier = Modifier.width(88.dp))
            Row(Modifier.weight(1f)) {
                BasicText("fuze2k and ", style = Room.Label.copy(lineHeight = 15.sp))
                BasicText(
                    "0xs0m",
                    style = Room.Label.copy(color = Room.Accent, lineHeight = 15.sp),
                    modifier = Modifier.testTag("credits-link").semantics { contentDescription = "0xs0m, somm.tf" }.overhangTap(min = 32.dp) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CREDITS_URL))) }
                    },
                )
            }
        }
    }
    Column(Modifier.fillMaxWidth().hairlineTop(Room.Ink).padding(top = 11.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        BrandText(brandMark() + androidx.compose.ui.text.AnnotatedString(" $VERSION"), Room.Display.copy(fontSize = 33.sp))
        BasicText(if (BuildConfig.DEBUG) "development build · rg-1 v3" else "rg-1 v3", style = Room.LabelQuiet)
        UpdateLine(state, actions)
        TeLink("what's new in this version", actions::showWhatsNew, tag = "about-whats-new")
    }
}

/** A newer release, inverted at the top of Settings: its version, and a link to its page. */
@Composable
private fun UpdateNotice(release: app.runout.update.Release, actions: RoomActions) {
    Row(
        Modifier.fillMaxWidth().background(Room.Ink).padding(horizontal = 16.dp, vertical = 14.dp).testTag("settings-update"),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("sonic ${release.version} is out.", style = Room.Text.copy(color = Room.OnInk), modifier = Modifier.weight(1f))
        TeLink("get it", { actions.openUpdate(release.page) }, tag = "settings-update-get", color = Room.Signal)
    }
}

/**
 * Under the version in About: where the last check left things, and a way to check again. The
 * first check asks which builds you want (UpdateChannelSheet). F-Droid builds leave it to F-Droid.
 */
@Composable
private fun UpdateLine(state: RoomState, actions: RoomActions) {
    val update = state.update
    var choosing by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().testTag("about-update"), horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!BuildConfig.UPDATE_CHECK) {
            BasicText("updates come through f-droid.", style = Room.LabelQuiet, modifier = Modifier.weight(1f))
            return@Row
        }
        val channel = state.updateChannel?.let { "${it.label} · " }.orEmpty()
        val (text, colour) = when (update) {
            UpdateState.Idle -> "${channel}not checked yet." to Room.Quiet
            UpdateState.Checking -> "asking github…" to Room.Quiet
            is UpdateState.UpToDate -> "${channel}up to date. checked ${checkedWhen(update.at)}." to Room.Quiet
            is UpdateState.Available -> "${update.release.version} is out." to Room.Signal
            is UpdateState.Failed -> update.reason to Room.Danger
        }
        BasicText(text, style = Room.Label.copy(color = colour, lineHeight = 15.sp), modifier = Modifier.weight(1f))
        if (update is UpdateState.Available) TeLink("get it", { actions.openUpdate(update.release.page) }, tag = "about-update-get", color = Room.Signal)
        else if (update != UpdateState.Checking) TeLink("check", { if (state.updateChannel == null) choosing = true else actions.checkForUpdates() }, tag = "about-update-check")
    }
    if (choosing) UpdateChannelSheet(
        onDismiss = { choosing = false },
        onChoose = { channel ->
            choosing = false
            actions.setUpdateChannel(channel)
            actions.checkForUpdates()
        },
    )
}

/** Asked once, at the first check: stable releases, or development builds too. Changeable in Flags. */
@Composable
private fun UpdateChannelSheet(onDismiss: () -> Unit, onChoose: (app.runout.update.UpdateChannel) -> Unit) {
    PageSheet("update-channel", onDismiss, title = "which builds?") {
        BasicText("you can change this later in settings › flags.", style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp))
        listOf(
            app.runout.update.UpdateChannel.STABLE to "tested releases. the one to pick if you just want to listen.",
            app.runout.update.UpdateChannel.DEVELOPMENT to "the newest features first, as pre-releases. rougher edges; you can go back to stable anytime.",
        ).forEach { (channel, line) ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Room.Ink)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onChoose(channel) }
                    .padding(16.dp)
                    .testTag("channel-choose:${channel.name}"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BasicText(channel.label, style = Room.Strong)
                BasicText(line, style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp))
            }
        }
    }
}

/** "today", "yesterday", "3 days ago". */
private fun checkedWhen(at: Long, now: Long = System.currentTimeMillis()): String =
    when (val days = ((now - at).coerceAtLeast(0) / (24 * 60 * 60 * 1000L)).toInt()) {
        0 -> "today"
        1 -> "yesterday"
        else -> "$days days ago"
    }

/**
 * TE Toggle Row with its TE Help: a hairline above, 16 dp above and below, the 12/400 title and the
 * "?" 8 dp after it, the 10/400 grey line 6 dp under, and the switch. The "?" opens a help panel.
 */
@Composable
internal fun HelpToggle(
    title: String,
    detail: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    tag: String = "toggle:$title",
    onHelp: (() -> Unit)? = null,
) {
    val toggled = rememberTick(if (checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .hairlineTop()
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Switch) { toggled(); onChange(!checked) }
            .semantics { stateDescription = if (checked) "On" else "Off" }
            .testTag(tag)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(title.lowercase(), style = Room.Text)
                if (onHelp != null) HelpSquare(title, onHelp)
            }
            if (detail != null) BasicText(detail.lowercase(), style = Room.LabelQuiet)
        }
        Switch(checked)
    }
}

/**
 * Two fingers dragged [up] (or down) past 56 dp: [onSwipe], once per gesture. Watches on the way
 * in without taking anything, so one finger still scrolls the page and taps still land.
 */
internal fun Modifier.twoFingerSwipe(up: Boolean, onSwipe: () -> Unit): Modifier = pointerInput(up) {
    val reach = 56.dp.toPx()
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
        var start: Float? = null
        var fired = false
        while (true) {
            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size >= 2) {
                val y = pressed.take(2).map { it.position.y }.average().toFloat()
                if (start == null) start = y
                val travel = start!! - y
                if (!fired && (if (up) travel > reach else -travel > reach)) {
                    fired = true
                    event.changes.forEach { it.consume() }
                    onSwipe()
                }
            } else start = null
        }
    }
}

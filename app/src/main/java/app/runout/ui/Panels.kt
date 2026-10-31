package app.runout.ui

import app.runout.ui.settings.*
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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The room's places. Those [inStrip] get a tab; Downloads opens from the ↓ count. [fullPage] ones
 * take the whole screen; the rest slide up as glass sheets.
 */
/**
 * The room's places. Each is a full page under the nav bar, headed by its name; Audio quality is a
 * panel over the room.
 */
enum class Panel(val title: String, val nav: String, val inStrip: Boolean = true, val fullPage: Boolean = true) {
    DIG("Dig", "Dig"),
    DROPS("Drops", "Drops"),
    FRIENDS("Friends", "Friends"),
    SETTINGS("Settings", "Settings"),
    DOWNLOADS("Downloads", "Downloads", inStrip = false),
    SIGNAL("Audio quality", "Audio", inStrip = false, fullPage = false),
}

// ---------------------------------------------------------------------------------------------
// Page parts shared by Settings and Drops

/**
 * Clickable with a touch target at least [min] tall that doesn't add to the layout: the element
 * keeps the height drawn on the boards and the rest of the target overhangs it, centred.
 */
internal fun Modifier.overhangTap(role: Role = Role.Button, enabled: Boolean = true, min: Dp = 48.dp, onClick: () -> Unit): Modifier = composed {
    val tick = rememberTick()
    val natural = remember { IntArray(1) }
    this
        .layout { m, c ->
            val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            val h = natural[0].coerceAtMost(p.height)
            layout(p.width, h) { p.place(0, -(p.height - h) / 2) }
        }
        .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, role = role) { tick(); onClick() }
        .layout { m, c ->
            val p = m.measure(c.copy(minHeight = 0))
            natural[0] = p.height
            val h = maxOf(p.height, min.roundToPx())
            layout(p.width, h) { p.place(0, (h - p.height) / 2) }
        }
}

/**
 * At least the height left in the page under the head, so a body can stand its rows at the foot
 * (Settings, About) inside the page's scroll. Takes the unscrolled top: the lowest one seen.
 */
internal fun Modifier.fillPageHeight(): Modifier = composed {
    val density = LocalDensity.current
    // The keyboard or the nav bar, whichever is taller (the nav bar sits behind the keyboard).
    val bottomInset = pageBottom().getBottom(density)
    var top by remember { mutableIntStateOf(-1) }
    var rootHeight by remember { mutableIntStateOf(0) }
    val min = if (top < 0) 0.dp else with(density) {
        (rootHeight - top - bottomInset - 24.dp.roundToPx()).coerceAtLeast(0).toDp()
    }
    this
        .onGloballyPositioned { c ->
            val y = c.positionInRoot().y.roundToInt()
            if (y > top) top = y
            rootHeight = c.findRootCoordinates().size.height
        }
        .heightIn(min = min)
}

/** "← settings", "← all drops": 12/400 ink at the top of a body. */
@Composable
internal fun PageBackLink(text: String, tag: String, onClick: () -> Unit) {
    BasicText(text, style = Room.Text, modifier = Modifier.testTag(tag).semantics { contentDescription = text.removePrefix("← ") }.overhangTap(onClick = onClick))
}

/** A TE Link that keeps its drawn height: 12/400 text and an arrow, 8 apart. */
@Composable
internal fun FlatLink(text: String, onClick: () -> Unit, tag: String = "link:$text", enabled: Boolean = true) {
    Row(
        Modifier.alpha(if (enabled) 1f else 0.4f).testTag(tag).semantics(mergeDescendants = true) {}.overhangTap(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text, style = Room.Text)
        BasicText("→", style = Room.Text)
    }
}

/** TE Help: the 16 dp outlined "?" square, drawn at its own size (the touch target overhangs). */
@Composable
internal fun HelpSquare(about: String, onClick: () -> Unit) {
    Box(
        Modifier
            .testTag("info:$about")
            .semantics { contentDescription = "More about $about" }
            .overhangTap(min = 32.dp, onClick = onClick)
            .size(16.dp)
            .border(1.dp, Room.Ink),
        contentAlignment = Alignment.Center,
    ) {
        BasicText("?", style = Room.Label)
    }
}

/** A numbered section of a page, "01 signal": the 10/400 grey label, then its parts 11 dp apart. */
@Composable
internal fun PageSection(label: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText(label, style = Room.LabelQuiet)
        content()
    }
}

// ---------------------------------------------------------------------------------------------
// Friends: see People.kt

// ---------------------------------------------------------------------------------------------
// Friday Drops

private val CLAIMS_HELP = TeHelp("help · drops", "claims", listOf(
    "you get three claims a week. each one adds a drop to your crate for good.",
    "everyone sees the same ten albums each friday.",
    "listening to the 30 s previews never uses a claim.",
))

/**
 * Friday Drops, as the TE · Drops boards: the week and claims left, the line about drops, then the
 * ten albums as grey tiles two to a row. A tile opens the drop's preview. Signed out, a note and a
 * key to sign in come first; loading and errors stand in for the shelf.
 */
@Composable
fun DropsPanel(
    drops: DropState,
    claimed: Set<String>,
    claiming: Map<String, Float>,
    actions: RoomActions,
    signedIn: Boolean = false,
    onAccount: () -> Unit = {},
    preview: PreviewState? = null,
) {
    // Every time Drops opens: this week's ten come from the phone once seen, and a new Friday
    // while the app stayed open brings the new week instead of last week's.
    LaunchedEffect(Unit) { actions.refreshDrops() }
    val left = (DropsRepository.CLAIMS_PER_WEEK - claimed.size).coerceAtLeast(0)
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var help by remember { mutableStateOf<TeHelp?>(null) }
    Column(Modifier.fillMaxWidth().testTag("panel:drops"), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        val ready = drops as? DropState.Ready
        val item = ready?.week?.items?.firstOrNull { it.identifier == open }
        if (item != null) {
            DropPreview(ready.week.items.indexOf(item) + 1, item, isClaimed =item.identifier in claimed, progress = claiming[item.identifier], left = left, canClaim = left > 0 && signedIn, actions = actions, preview = preview) { open = null }
            return@Column
        }
        if (!signedIn) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BasicText(
                    "sign in with soulseek to download from the internet archive. local flac playback is always available without an account.",
                    style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp),
                )
                TeButton("sign in with soulseek", onAccount, Modifier.testTag("drops-sign-in"))
            }
        }
        when (drops) {
            DropState.Idle, DropState.Loading -> WaitingMark("digging through the archive…", "Loading drops")
            is DropState.Failed -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BasicText(drops.reason.lowercase(), style = Room.Label.copy(color = Room.Danger, lineHeight = 15.sp))
                TeButton("try again", actions::refreshDrops, kind = KeyKind.OUTLINE)
            }
            is DropState.Ready -> {
                val w = drops.week
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        BasicText("week of ${w.opens.dayOfMonth} ${w.opens.month.name.lowercase()}", style = Room.LabelQuiet, modifier = Modifier.weight(1f))
                        ClaimLights(left) { help = CLAIMS_HELP }
                    }
                    if (left == 0) BasicText("all claimed this week · next drop friday ${w.closes.dayOfMonth}", style = Room.LabelQuiet)
                }
            }
        }
        BasicText(
            "ten creative commons albums from the internet archive every friday, the same for everyone. claim three a week.",
            style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp),
        )
        if (ready != null) {
            // The shelf: the ten drops as tiles, two to a row, 22 dp across and 16 dp down.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ready.week.items.withIndex().chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        pair.forEach { (i, d) ->
                            DropSleeve(i + 1, d, d.identifier in claimed, claiming[d.identifier], canClaim = left > 0, actions, Modifier.weight(1f).fillMaxHeight()) { open = d.identifier }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
    TeHelpSheet(help) { help = null }
}

/**
 * Something on its way from the network: the 132 dp dotted vinyl spinning over one quiet line, the
 * same mark Dig shows while it searches. [description] names the wait for a screen reader.
 */
@Composable
internal fun WaitingMark(text: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DotVinylSpinner(Modifier.size(132.dp), description = description)
        BasicText(text, style = Room.LabelQuiet, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Claims left this week: "2 claims left" in orange, a 10 dp square for each claim (solid once
 * spent, outlined while still free) and the TE Help "?".
 */
@Composable
private fun ClaimLights(left: Int, onHelp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.semantics(mergeDescendants = true) { contentDescription = "$left of ${DropsRepository.CLAIMS_PER_WEEK} claims left" }.testTag("claim-lights"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(if (left == 1) "1 claim left" else "$left claims left", style = Room.Label.copy(color = Room.Signal))
            val spent = DropsRepository.CLAIMS_PER_WEEK - left
            repeat(DropsRepository.CLAIMS_PER_WEEK) { i ->
                Box(Modifier.size(10.dp).then(if (i < spent) Modifier.background(Room.Signal) else Modifier.border(1.dp, Room.Signal)))
            }
        }
        HelpSquare("claims", onHelp)
    }
}

/** A licence for a tile: "cc by", without its version. */
private fun shortLicence(label: String) = label.lowercase().replace(Regex(" \\d+(\\.\\d+)*$"), "")

/**
 * One drop on the shelf: a white tile in a 2 dp ink outline, the sleeve centred, 16 dp in and 11 between parts: the sleeve, the number and
 * licence (blue), the 10/400 title and grey artist · year, and what you can do (green once it's in
 * your crate, the claiming percentage while it downloads, "claim →" otherwise).
 */
@Composable
private fun DropSleeve(number: Int, item: DropItem, isClaimed: Boolean, progress: Float?, canClaim: Boolean, actions: RoomActions, modifier: Modifier, onOpen: () -> Unit) {
    val cover by produceState<ImageBitmap?>(null, item.identifier) {
        value = actions.dropCover(item)?.let { CoverCache.load(it) }
    }
    val tick = rememberTick()
    Column(
        modifier
            .testTag("drop:${item.identifier}")
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { tick(); onOpen() }
            .background(Room.Background)
            .border(2.dp, Room.Ink)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 140 of the tile's 151 dp on the 412 dp board, square.
        DropCover(number, item, cover, Modifier.fillMaxWidth(140f / 151f).aspectRatio(1f).align(Alignment.CenterHorizontally)) {
            if (progress != null) ProgressLine(progress, Modifier.align(Alignment.BottomCenter).padding(8.dp))
        }
        Row(Modifier.fillMaxWidth()) {
            BasicText("%02d".format(number), style = Room.LabelQuiet, modifier = Modifier.weight(1f))
            Licences.label(item.license)?.let { BasicText(shortLicence(it), style = Room.Label.copy(color = Room.Accent), maxLines = 1) }
        }
        BasicText(item.title.lowercase(), style = Room.Label.copy(lineHeight = 13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        BasicText(listOfNotNull(item.creator, item.year?.toString()).joinToString(" · ").lowercase(), style = Room.LabelQuiet.copy(lineHeight = 13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        // The action sits at the foot, level across the row.
        Box(Modifier.weight(1f), contentAlignment = Alignment.BottomStart) { when {
            isClaimed -> BasicText("in your crate", style = Room.Label.copy(color = Room.Online))
            progress != null -> BasicText("claiming · ${(progress * 100).roundToInt()}%", style = Room.Label.copy(color = Room.Signal))
            canClaim -> BasicText("claim →", style = Room.Label)
            else -> BasicText("listen →", style = Room.Label)
        } }
    }
}

/**
 * A drop up close, as the TE · Drops · preview board: "← all drops", the 132 dp sleeve beside the
 * 16/700 title, artist, blue licence tag and the black claim key; then the tracks, each with a 30 s
 * listen, under #E5E5E5 hairlines; then the note that listening uses no claim.
 */
@Composable
private fun DropPreview(number: Int, item: DropItem, isClaimed: Boolean, progress: Float?, left: Int, canClaim: Boolean, actions: RoomActions, preview: PreviewState?, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler { actions.listen(null); onBack() }
    PageStep { actions.listen(null); onBack() }
    DisposableEffect(item.identifier) { onDispose { actions.listen(null) } }
    val cover by produceState<ImageBitmap?>(null, item.identifier) { value = actions.dropCover(item)?.let { CoverCache.load(it) } }
    // null while loading; then the list, or null again if the archive couldn't be reached.
    var loaded by remember(item.identifier) { mutableStateOf(false) }
    val tracks by produceState<List<app.runout.drops.DropTrack>?>(null, item.identifier) { value = actions.dropTracks(item); loaded = true }
    val noFlac = loaded && tracks?.isEmpty() == true
    Column(Modifier.fillMaxWidth().testTag("drop-preview"), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        PageBackLink("← all drops", "drops-back") { actions.listen(null); onBack() }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DropCover(number, item, cover, Modifier.size(132.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BasicText(item.title.lowercase(), style = Room.Heading.copy(lineHeight = 19.sp), maxLines = 4, overflow = TextOverflow.Ellipsis)
                listOfNotNull(item.creator, item.year?.toString()).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    BasicText(it.lowercase(), style = Room.Label.copy(color = Room.Body))
                }
                Licences.label(item.license)?.let { TeTag(it.lowercase(), color = Room.Accent) }
                when {
                    isClaimed -> BasicText("in your crate", style = Room.Label.copy(color = Room.Online))
                    progress != null -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BasicText("claiming · ${(progress * 100).roundToInt()}%", style = Room.Label.copy(color = Room.Signal))
                        ProgressLine(progress)
                    }
                    // An album with no FLAC can't be kept, so there's nothing to claim.
                    else -> TeButton(
                        when {
                            noFlac -> "no flac to claim"
                            canClaim -> "claim · 1 of $left"
                            else -> "claim"
                        },
                        { actions.listen(null); actions.claim(item) },
                        Modifier.testTag("claim:${item.identifier}"),
                        enabled = canClaim && !noFlac,
                    )
                }
            }
        }
        BasicText("tracks · tap to hear 30 s", style = Room.LabelQuiet)
        val list = tracks
        when {
            list == null && !loaded -> WaitingMark("listing the tracks…", "Loading tracks")
            list == null -> BasicText("couldn't reach the archive for the track list. you can still claim it.", style = Room.LabelQuiet.copy(lineHeight = 15.sp))
            list.isEmpty() -> BasicText("this album has no flac files, so it can't be claimed. it uses no allowance.", style = Room.LabelQuiet.copy(lineHeight = 15.sp))
            else -> Column(Modifier.fillMaxWidth()) {
                list.forEachIndexed { i, t ->
                    // What the player says, not what was tapped: it clears itself when the listen
                    // ends for any reason, so the stop square never outlives the sound.
                    val phase = preview?.takeIf { it.url == t.url }?.phase
                    val on = phase != null
                    val tick = rememberTick()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .hairlineTop(Room.HairlineSoft)
                            .testTag("listen:$i")
                            .semantics(mergeDescendants = true) {
                                contentDescription = when (phase) {
                                    PreviewPhase.BUFFERING -> "Loading ${t.title}. Tap to stop"
                                    PreviewPhase.PLAYING -> "Stop listening"
                                    null -> "Listen to ${t.title}"
                                }
                            }
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                                tick()
                                actions.listen(if (on) null else t.url)
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        BasicText((i + 1).toString().padStart(2, '0'), style = Room.LabelQuiet)
                        BasicText(t.title.lowercase(), style = Room.Label.copy(color = if (on) Room.Signal else Room.Ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        // Lucide play; the dotted vinyl turning while the start of the track loads;
                        // an orange stop square once it plays.
                        if (phase == PreviewPhase.BUFFERING) DotVinylSpinner(Modifier.size(18.dp), description = "Loading preview")
                        else Canvas(Modifier.size(16.dp)) {
                            val w = size.width
                            val line = 1.dp.toPx()
                            if (on) drawRect(Room.Signal, Offset(line / 2, line / 2), Size(w - line, w - line), style = Stroke(line))
                            else drawPath(Path().apply { moveTo(w * 0.17f, w * 0.08f); lineTo(w * 0.92f, w * 0.5f); lineTo(w * 0.17f, w * 0.92f); close() }, Room.Ink, style = Stroke(line, join = StrokeJoin.Round))
                        }
                    }
                }
            }
        }
        BasicText("listening plays thirty seconds from the archive. it doesn't use a claim.", style = Room.LabelQuiet.copy(lineHeight = 15.sp))
    }
}

/**
 * A drop's sleeve: while its cover loads, or when it has none, a dot-matrix record on the #F5F5F5
 * tile with the drop's number in its corner; the real cover fades in over it when it arrives.
 */
@Composable
private fun DropCover(number: Int, item: DropItem, cover: ImageBitmap?, modifier: Modifier, overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}) {
    val shown by androidx.compose.animation.core.animateFloatAsState(
        if (cover != null) 1f else 0f, androidx.compose.animation.core.tween(400), label = "drop-cover",
    )
    Box(modifier.background(Room.Surface)) {
        if (shown < 1f) Box(Modifier.fillMaxSize().testTag("drop-placeholder:${item.identifier}")) {
            DotVinyl(Modifier.fillMaxSize().padding(12.dp))
            BasicText("%02d".format(number), style = Room.Label, modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        cover?.let { Image(it, item.title, Modifier.fillMaxSize().alpha(shown), contentScale = ContentScale.Crop) }
        overlay()
    }
}

fun formatLength(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Where a record came from, for the crate caption. */
fun provenance(album: Album): String? = when (album.source) {
    Source.RECEIVED -> "Sent by ${album.from ?: "a friend"}"
    Source.DROP -> listOfNotNull("Friday Drop", Licences.label(album.license)).joinToString(" · ")
    Source.SOULSEEK -> "Soulseek · ${album.from ?: "FLAC download"}"
    Source.PRESSING -> "Your pressing" + (album.disc?.let { " · ${it.label.lowercase()} vinyl" } ?: "")
    Source.FOLDER -> Licences.label(album.license)
}

/*
 * THE VINYL PLAYER — the turntable in the room.
 *
 * It's a 3D model (assets/deck/turntable_<colourway>.glb, the RG-1 v3 in white / teal, mono white or dark), drawn by Filament
 * in ui/deck/DeckRenderer.kt. This file is the Compose side: the deck's physics (platter spin-up and
 * wind-down, the tonearm's travel), touch (drag the arm, tap the record to flip it, hold it to slow
 * it, the keys on the right: prev, next, START/STOP and eject), what the deck shows (the red LED
 * title and time windows, the status lamp, the LED strip's progress).
 *
 * Map:
 *   DeckActions          what a press on the deck asks the player to do
 *   RPM_33_DEG           how fast the platter turns on screen
 *   screenLines()        the track and status, as TalkBack reads the LED windows
 *   ledLines()           what the deck's LED windows show
 *   Turntable()          the composable: physics, the 3D view (or its flat stand-in), touch, keys
 *   recordFace()         paints the record's face (RecordArt.drawRecord) into the 3D disc's texture
 *
 * Where the parts are in the model, the tonearm's arc and the camera: ui/deck/DeckModel.kt, from
 * docs/design/deck-v3-spec.json. Rendering, lights, the screen and LEDs: ui/deck/DeckRenderer.kt. The
 * record's look: RecordArt.kt, its grooves and track gaps in 3D: ui/deck/RecordMaps.kt. To change
 * the model: scripts/deck-v3 (Blender), scripts/prepare-deck-glb.py <source.glb>, then update
 * DeckModel from the new spec.
 */
package app.runout.ui

import app.runout.model.AudioQuality
import app.runout.model.Track
import app.runout.ui.deck.DeckCapture
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.runout.model.Album
import app.runout.playback.DeckState
import app.runout.ui.deck.DeckCamera
import app.runout.ui.deck.DeckKey
import app.runout.ui.deck.DeckModel
import app.runout.ui.deck.DeckRenderer
import app.runout.ui.deck.RecordMaps
import app.runout.ui.deck.V3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Callbacks from the turntable to the deck. */
interface DeckActions {
    fun needleDrop(fraction: Float)
    fun liftNeedle()
    fun holdPlatter()
    fun releasePlatter()
    fun flip()
    fun startStop() {}
    fun nextTrack() {}
    fun previousTrack() {}
    /** Lift the record off the platter and clear the deck. */
    fun eject() {}
}

/**
 * How fast the platter turns on screen, in degrees a second: one turn every 3.6 s. A true 33⅓
 * (200°/s) reads as racing on a phone-sized platter, so the picture turns at half that; the music
 * always plays at the speed it was cut.
 */
const val RPM_33_DEG = 100f

/** Width to height of the 3D view. */
private const val VIEW_ASPECT = 1.72f

/** What's on the deck, in two parts: for TalkBack, read over the LED windows. */
fun screenLines(deck: DeckState): Pair<String, String> {
    val album = deck.album ?: return "SONIC. DROP A RECORD ON THE PLATTER" to "NO RECORD"
    val track = deck.currentTrack
    val title = "${deck.trackCode} ${track?.title ?: album.title} · ${album.artist}"
    val s = deck.trackPositionMs / 1000
    val time = "%d:%02d".format(s / 60, s % 60)
    val status = when {
        deck.sideFinished -> "SIDE ${deck.side?.letter} END"
        !deck.needleDown -> "CUE ${deck.trackCode}"
        deck.held -> "STOP $time"
        else -> time
    }
    return title to status
}

/** What the deck's LED windows show: [title] across the long one, [code] and [time] in the short one (null leaves them dark). */
data class DeckLeds(val title: String, val code: String?, val time: String?)

fun ledLines(deck: DeckState): DeckLeds {
    val album = deck.album ?: return DeckLeds("DROP A RECORD", null, null)
    val name = deck.currentTrack?.title ?: album.title
    val s = deck.trackPositionMs / 1000
    val time = "%d:%02d".format(s / 60, s % 60)
    val title = when {
        deck.sideFinished -> "SIDE ${deck.side?.letter} END"
        !deck.needleDown -> "CUE  $name"
        else -> name
    }
    return DeckLeds(title, deck.trackCode, time)
}

/**
 * What the room needs to know about the deck to fly a 3D record onto it: the deck's camera, where
 * its view sits in the room, and how far the platter has turned (so a landing record lines up).
 */
class DeckLink {
    var camera by mutableStateOf<DeckCamera?>(null)
    var origin by mutableStateOf(Offset.Zero)
    /** Read on landing only, so a plain field: no recomposition per frame. */
    var platterDegrees = 0f
}

@Composable
fun Turntable(
    deck: DeckState,
    dropTargetActive: Boolean,
    onPlatterPlaced: (center: Offset, radius: Float) -> Unit,
    actions: DeckActions,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.CenterStart,
    link: DeckLink? = null,
    /** Long-press on the LED screen: what's reaching your ears (the Audio quality panel). */
    onScreenLongPress: (() -> Unit)? = null,
    /** A different record is already on: the ring turns red and the disc won't land. */
    dropBlocked: Boolean = false,
    /** Words for the LED title window for a moment (EJECT while a record flies home). */
    ledOverride: String? = null,
    /** The record on the platter was dragged, as if to take it off: the room points to Eject. */
    onRecordDragged: (() -> Unit)? = null,
    /** The record being taken off the platter for another, 0 to 1: it rises out of the deck. */
    liftOff: () -> Float = { 0f },
    /** Which RG-1 stands here (Settings › Flags › deck), already resolved from the theme. */
    skin: DeckSkin = DeckSkin.WHITE_TEAL,
    /** Whether the studio lights are on (Settings › Flags: the neon deck stands in the dark by default). */
    lit: Boolean = true,
) {
    val context = LocalContext.current
    val textMeasurer = rememberTextMeasurer()
    val cover by rememberCover(deck.album)
    // The 3D deck needs Filament and a working GPU; if either fails the flat deck stands in.
    var use3d by remember { mutableStateOf(DeckRenderer.available) }
    var renderer by remember { mutableStateOf<DeckRenderer?>(null) }

    // Platter: spins up when the needle goes down, winds down when stopped or lifted. A heavy
    // platter takes its time: about a second and a half to reach speed, longer to coast to a stop.
    var rotation by remember { mutableFloatStateOf(0f) }
    var omega by remember { mutableFloatStateOf(0f) }
    val target = if (deck.album != null && deck.needleDown && !deck.held) RPM_33_DEG else 0f
    val held = deck.held
    LaunchedEffect(target) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1e9f).coerceIn(0f, 0.05f)
            last = now
            val tau = when {
                target > omega -> 0.5f
                held -> 0.16f
                else -> 1.3f
            }
            omega += (target - omega) * (1f - exp(-dt / tau))
            rotation = (rotation + omega * dt) % 360f
            link?.platterDegrees = rotation
            // Straight to the renderer: no recomposition per frame.
            renderer?.setPlatter(rotation)
            if (target == 0f && omega < 0.4f) {
                omega = 0f
                break
            }
        }
    }

    // Tonearm: tracks the needle through the side, or follows the finger while dragged. The needle
    // drops into the groove a moment after the arm arrives, and lifts as soon as it's picked up.
    var dragAngle by remember { mutableStateOf<Float?>(null) }
    val playAngle = if (deck.album != null && deck.needleDown) DeckModel.angleForFraction(deck.fraction) else DeckModel.angleRest
    val animAngle by animateFloatAsState(playAngle, spring(dampingRatio = 1f, stiffness = Spring.StiffnessVeryLow), label = "tonearm")
    val armAngle = dragAngle ?: animAngle
    val drop by animateFloatAsState(if (deck.needleDown && dragAngle == null) 1f else 0f, tween(380), label = "needle")
    LaunchedEffect(renderer) {
        val r = renderer ?: return@LaunchedEffect
        r.setPlatter(rotation)
        snapshotFlow { (dragAngle ?: animAngle) to drop }.collect { (a, d) -> r.setArm(a, d) }
    }

    // Turning the record over (TE · flipping the record, ~0.9 s): in 3D it lifts, rolls towards you
    // and settles; its face changes to the new side when it's edge-on. The flat deck squashes the
    // disc to a line and opens it again. With animations off the side just changes.
    var faceSideIndex by remember(deck.album?.id) { mutableIntStateOf(deck.sideIndex) }
    val flipT = remember { Animatable(0f) }
    val thud = rememberTick(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
    // The hand on the deck: the arm lifts with a catch, ticks over each gap between songs and at the
    // record's edge as it's carried, lands in the groove with a thud and settles on its rest softly.
    // A press that slows the platter is felt as it takes hold; pulling the record off says no.
    val armLift = rememberTick(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate)
    val groove = rememberTick(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
    val needleDown = rememberTick(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
    val armRest = rememberTick(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureEnd)
    val platterHeld = rememberTick(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate)
    val pullRefused = rememberTick(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Reject)
    LaunchedEffect(deck.album?.id, deck.sideIndex) {
        if (deck.sideIndex == faceSideIndex) return@LaunchedEffect
        val animate = android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        if (!animate) { faceSideIndex = deck.sideIndex; return@LaunchedEffect }
        flipT.snapTo(0f)
        flipT.animateTo(0.5f, tween(400, easing = FastOutLinearInEasing))
        faceSideIndex = deck.sideIndex
        flipT.animateTo(1f, tween(500, easing = LinearOutSlowInEasing))
        thud()
        flipT.snapTo(0f)
    }
    LaunchedEffect(renderer) {
        val r = renderer ?: return@LaunchedEffect
        snapshotFlow { flipT.value }.collect { r.setFlip(it) }
    }
    LaunchedEffect(renderer) {
        val r = renderer ?: return@LaunchedEffect
        snapshotFlow { liftOff() }.collect { r.setLiftOff(it) }
    }

    // The record's face, painted once per record and side, then turned by the platter in 3D.
    val faceSide = deck.sides.getOrNull(faceSideIndex) ?: deck.side
    val boundaries = remember(faceSide) { faceSide?.trackBoundaries() ?: emptyList() }
    val currentBoundaries by rememberUpdatedState(boundaries)
    val face = remember(deck.album?.id, faceSide, cover) {
        deck.album?.let { if (use3d) recordFace(it, cover, boundaries, faceSide?.letter?.toString(), textMeasurer) else null }
    }
    LaunchedEffect(renderer) { if (renderer != null) withContext(Dispatchers.Default) { RecordMaps.warmUp() } }
    LaunchedEffect(renderer, face, deck.album == null) {
        val r = renderer ?: return@LaunchedEffect
        r.setRecord(if (deck.album == null) null else face?.asAndroidBitmap())
        if (deck.album == null || face == null) return@LaunchedEffect
        // Then the grooves and the smooth bands between this side's tracks, under the painted gaps.
        val maps = withContext(Dispatchers.Default) { RecordMaps.normal(boundaries) to RecordMaps.roughness(boundaries) }
        r.setRecordSurface(maps.first, maps.second)
    }

    // The view: the usual three-quarter view, or flat from straight above. Two fingers down lay it
    // flat, two fingers up stand it back; the deck follows the fingers and settles on one of the two.
    val viewPrefs = remember { context.getSharedPreferences("room", android.content.Context.MODE_PRIVATE) }
    var flatView by remember { mutableStateOf(viewPrefs.getBoolean(FLAT_VIEW_KEY, false)) }
    val tilt = remember { Animatable(if (flatView) DeckCamera.FLAT_DEG else DeckCamera.TILT_DEG) }
    val viewScope = rememberCoroutineScope()
    val settled = rememberTick(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
    fun settleView(flat: Boolean) {
        if (flat != flatView) settled()
        flatView = flat
        viewPrefs.edit().putBoolean(FLAT_VIEW_KEY, flat).apply()
        viewScope.launch { tilt.animateTo(if (flat) DeckCamera.FLAT_DEG else DeckCamera.TILT_DEG, tween(360, easing = FastOutSlowInEasing)) }
    }
    LaunchedEffect(renderer) {
        val r = renderer ?: return@LaunchedEffect
        snapshotFlow { tilt.value }.collect { r.setTilt(it) }
    }

    // Pause rendering while the app is in the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, renderer) {
        val r = renderer
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) r?.resume()
            if (e == Lifecycle.Event.ON_STOP) r?.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val currentDeck by rememberUpdatedState(deck)
    val currentArm by rememberUpdatedState(armAngle)
    // The keys go down under the finger, on the model itself.
    val keySources = remember { DeckModel.keys.associateWith { MutableInteractionSource() } }
    keySources.forEach { (key, source) ->
        val down by source.collectIsPressedAsState()
        val travel by animateFloatAsState(if (down) 1f else 0f, tween(70), label = key.node)
        LaunchedEffect(renderer, travel) { renderer?.setKey(key, travel) }
    }

    // The status lamp, the LED strip and the deck's own screen.
    val playing = deck.album != null && deck.needleDown && !deck.held
    LaunchedEffect(renderer, playing) { renderer?.setStatusLamp(playing) }
    val darkRoom = Room.dark
    LaunchedEffect(renderer, darkRoom) { renderer?.setGroundShadow(!darkRoom) }
    LaunchedEffect(renderer, lit) { renderer?.setLit(lit) }
    val stripStep = if (deck.album == null) 0 else (deck.fraction * 64).toInt()
    LaunchedEffect(renderer, stripStep, playing) { renderer?.setStrip(stripStep / 64f, playing) }
    // While the record turns over the LED reads FLIP (then the new side's code); EJECT while it flies home.
    val flipping by remember { derivedStateOf { flipT.value > 0f } }
    val leds = ledLines(deck).let {
        when {
            ledOverride != null -> DeckLeds(ledOverride, null, null)
            flipping -> DeckLeds("FLIP", it.code, null)
            else -> it
        }
    }
    LaunchedEffect(renderer, leds) { renderer?.setScreen(leds.title, leds.code, leds.time) }
    // A frame drawn by Filament shows once the room redraws around it.
    var frames by remember { mutableIntStateOf(0) }
    // Until the 3D deck's first frames are through, the flat deck stands in for it.
    // The flat deck stands in again while another colourway loads.
    var shown3d by remember(skin) { mutableStateOf(false) }
    // Debug builds: the intro's deck pictures, rendered from this deck on request (DeckCapture).
    var deckView by remember { mutableStateOf<TextureView?>(null) }
    if (app.runout.BuildConfig.DEBUG && DeckCapture.requested) LaunchedEffect(renderer, shown3d) {
        val r = renderer ?: return@LaunchedEffect
        val tv = deckView ?: return@LaunchedEffect
        if (!shown3d) return@LaunchedEffect
        val sample = Album(
            "capture", "Night Drive", "Vela Park", 2023, "",
            listOf(Track("t1", "", "Opening", 1, 1, 180_000), Track("t2", "", "Lowlight", 2, 1, 200_000)),
            AudioQuality("FLAC", 24, 96_000),
        )
        DeckCapture.run(context, r, tv, recordFace(sample, null, emptyList(), "A", textMeasurer).asAndroidBitmap(), link?.camera)
        r.setGroundShadow(!Room.dark)
    }
    LaunchedEffect(renderer) {
        renderer?.onFrame = { frames++ }
        renderer?.onReady = { shown3d = true }
        // Ready before anyone was listening: shown already.
        if (renderer?.ready == true) shown3d = true
    }

    BoxWithConstraints(modifier, contentAlignment = contentAlignment) {
        val w = min(maxWidth, maxHeight * VIEW_ASPECT)
        val h = w / VIEW_ASPECT
        val density = LocalDensity.current
        // Re-fitted to the same box at every tilt, so nothing around the deck moves.
        val cam = remember(w, h, density, tilt.value) { with(density) { DeckCamera(w.toPx(), h.toPx(), tilt.value) } }
        val boxOrigin = remember { arrayOfNulls<Offset>(1) }
        fun placed(origin: Offset) {
            val c = cam.project(V3(DeckModel.platterCentre.x, DeckModel.RECORD_Y, DeckModel.platterCentre.z))
            onPlatterPlaced(origin + c, cam.pixelsPerUnit(DeckModel.platterCentre) * DeckModel.RECORD_R)
            link?.let { it.camera = cam; it.origin = origin }
        }
        LaunchedEffect(cam) { boxOrigin[0]?.let { placed(it) } }
        Column(
            Modifier
                .width(w)
                .testTag("turntable")
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(if (flatView) "Angled view" else "Flat view") { settleView(!flatView); true },
                    )
                },
        ) {
            Box(
                Modifier
                    .size(w, h)
                    .onGloballyPositioned { coords ->
                        boxOrigin[0] = coords.positionInRoot()
                        placed(coords.positionInRoot())
                    }
                    // Two fingers anywhere on the deck tilt the view; the deck's own touch stands down.
                    .pointerInput(Unit) {
                        val reach = 140.dp.toPx()
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            var startY = Float.NaN
                            var from = tilt.value
                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.size >= 2) {
                                        val y = pressed.map { it.position.y }.average().toFloat()
                                        if (startY.isNaN()) { startY = y; from = tilt.value }
                                        event.changes.forEach { it.consume() }
                                        val t = (from - (y - startY) / reach * DeckCamera.TILT_DEG).coerceIn(DeckCamera.FLAT_DEG, DeckCamera.TILT_DEG)
                                        viewScope.launch { tilt.snapTo(t) }
                                    } else if (!startY.isNaN()) {
                                        // The tilt ends as a finger lifts; the deck doesn't see the rest.
                                        event.changes.forEach { it.consume() }
                                        break
                                    } else if (pressed.isEmpty()) {
                                        // A one-finger touch: left alone for the keys, arm and record.
                                        break
                                    }
                                }
                            } finally {
                                // On lift, or if the gesture is cancelled: never left half-tilted. A
                                // quarter of the way is enough to go; less and it springs back.
                                if (!startY.isNaN()) {
                                    val t = tilt.value
                                    val flat = if (from >= DeckCamera.TILT_DEG) t < DeckCamera.TILT_DEG * 0.75f else t < DeckCamera.TILT_DEG * 0.25f
                                    settleView(flat)
                                }
                            }
                        }
                    },
            ) {
                // Another colourway is another model: a fresh view and renderer, and the effects
                // keyed on [renderer] hand it the record, arm, keys and screen again.
                if (use3d) key(skin) {
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).also { tv ->
                                runCatching { DeckRenderer(ctx, tv, skin.asset ?: DeckSkin.WHITE_TEAL.asset!!, glow = skin == DeckSkin.NEON).also { it.resume() } }
                                    .onSuccess { tv.tag = it; renderer = it; deckView = tv }
                                    .onFailure { e ->
                                        android.util.Log.w("RunoutDeck", "3D deck unavailable, using the flat deck", e)
                                        use3d = false
                                    }
                            }
                        },
                        // Each view lets go of its own renderer only: on a change of colourway the
                        // new one may already be in place.
                        onRelease = { tv ->
                            val own = tv.tag as? DeckRenderer
                            own?.destroy()
                            if (renderer === own) renderer = null
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Without a GPU (the JVM tests, a phone Filament can't drive) a flat deck stands in, in
                // the same places. While the 3D deck starts, the dotted record turns where it will be.
                if (!use3d) FlatDeck(cam, deck, rotation, armAngle, cover, boundaries, textMeasurer, FlatColours.of(skin), flip = { flipT.value }, liftOff = liftOff)
                else if (!shown3d) Box(Modifier.fillMaxSize().testTag("deck-loading"), contentAlignment = Alignment.Center) {
                    DotVinylSpinner(Modifier.size(96.dp), description = "Setting up the deck")
                }
                // The drop target over the model. Reading [frames] here redraws the room each time
                // Filament finishes a frame, so the TextureView's newest picture is what shows.
                Canvas(Modifier.fillMaxSize()) {
                    frames
                    if (dropTargetActive) drawRecordOutline(cam, if (dropBlocked) Room.Danger else Room.Signal)
                }
                // Touch: the arm and the record.
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(cam) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val d = currentDeck
                                val p = cam.onPlane(down.position, DeckModel.RECORD_Y) ?: return@awaitEachGesture
                                val slop = 26.dp.toPx() / cam.pixelsPerUnit(DeckModel.platterCentre)
                                if (d.album != null && DeckModel.nearArm(p, currentArm, slop)) {
                                    // Pick up the tonearm.
                                    down.consume()
                                    val wasDown = d.needleDown
                                    val startAngle = currentArm
                                    if (wasDown) actions.liftNeedle()
                                    armLift()
                                    var moved = 0f
                                    var last = down.position
                                    var twoFingers = false
                                    dragAngle = currentArm
                                    // Where the stylus is over the record: off it (-1), or the song it's over.
                                    fun songUnder(angle: Float): Int {
                                        val s = DeckModel.stylus(angle)
                                        if (hypot(s.x - DeckModel.platterCentre.x, s.z - DeckModel.platterCentre.z) > DeckModel.RECORD_R) return -1
                                        val f = DeckModel.fractionForAngle(angle)
                                        return currentBoundaries.count { it < f }
                                    }
                                    var song = songUnder(currentArm)
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.changes.count { it.pressed } > 1) { twoFingers = true; break }
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        moved += (change.position - last).getDistance()
                                        last = change.position
                                        cam.onPlane(change.position, DeckModel.RECORD_Y)?.let { dragAngle = DeckModel.angleTowards(it) }
                                        dragAngle?.let { a -> songUnder(a).let { now -> if (now != song) { song = now; groove() } } }
                                        change.consume()
                                    }
                                    if (twoFingers) {
                                        // Tilting the view, not moving the arm: put it back where it was.
                                        dragAngle = null
                                        if (wasDown) actions.needleDrop(DeckModel.fractionForAngle(startAngle))
                                        return@awaitEachGesture
                                    }
                                    val a = dragAngle ?: DeckModel.angleRest
                                    dragAngle = null
                                    val s = DeckModel.stylus(a)
                                    val overRecord = hypot(s.x - DeckModel.platterCentre.x, s.z - DeckModel.platterCentre.z) <= DeckModel.RECORD_R
                                    if (moved < viewConfiguration.touchSlop) {
                                        if (!wasDown) { actions.needleDrop(0f); needleDown() } else armRest()
                                    } else if (overRecord) {
                                        actions.needleDrop(DeckModel.fractionForAngle(a))
                                        needleDown()
                                    } else armRest()
                                } else if (d.album != null && DeckModel.isOnRecord(p)) {
                                    // A quick tap flips the record; a press on a spinning record slows it
                                    // down; pulling it off the platter asks for Eject instead.
                                    val dragLimit = 24.dp.toPx()
                                    var pressed = false
                                    var holding = false
                                    var dragged = false
                                    while (true) {
                                        val event = withTimeoutOrNull(if (pressed) Long.MAX_VALUE else 170L) { awaitPointerEvent() }
                                        if (event == null) {
                                            pressed = true
                                            if (d.needleDown) { holding = true; actions.holdPlatter(); platterHeld() }
                                            continue
                                        }
                                        // A second finger: the view is being tilted, not the record touched.
                                        if (event.changes.count { it.pressed } > 1) break
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) {
                                            if (!pressed && !dragged && !d.needleDown) actions.flip()
                                            break
                                        }
                                        if (!dragged && (change.position - down.position).getDistance() > dragLimit) {
                                            dragged = true
                                            pullRefused()
                                            onRecordDragged?.invoke()
                                        }
                                    }
                                    if (holding) actions.releasePlatter()
                                }
                            }
                        },
                )
                // The deck's keys, with names for TalkBack.
                KeyArea(cam, DeckModel.startStop, "Start or stop", "deck:startstop", keySources.getValue(DeckModel.startStop), stateText = if (playing) "Playing" else "Stopped") { actions.startStop() }
                KeyArea(cam, DeckModel.prev, "Previous track", "deck:prev", keySources.getValue(DeckModel.prev)) { actions.previousTrack() }
                KeyArea(cam, DeckModel.next, "Next track", "deck:next", keySources.getValue(DeckModel.next)) { actions.nextTrack() }
                KeyArea(cam, DeckModel.eject, "Eject the record", "deck:eject", keySources.getValue(DeckModel.eject), enabled = deck.album != null) { actions.eject() }
                // The LED windows, for TalkBack and the tests: what they show, in words.
                val (l1, l2) = screenLines(deck)
                ScreenArea(cam, "$l1. $l2", onScreenLongPress)
            }
        }
    }
}

/** The two LED windows' outline on the screen, read out as [description]. */
@Composable
private fun BoxScope.ScreenArea(cam: DeckCamera, description: String, onLongPress: (() -> Unit)?) {
    val density = LocalDensity.current
    val (a, _) = DeckModel.titleWindow
    val (_, b) = DeckModel.timeWindow
    val corners = listOf(a.x, b.x).flatMap { x -> listOf(a.z, b.z).map { z -> cam.project(V3(x, a.y, z)) } }
    val left = corners.minOf { it.x }
    val top = corners.minOf { it.y }
    Box(
        Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(density) { (corners.maxOf { it.x } - left).toDp() }, with(density) { (corners.maxOf { it.y } - top).toDp() })
            .testTag("deck:screen")
            .semantics {
                contentDescription = description
                if (onLongPress != null) onLongClick("Audio quality") { onLongPress(); true }
            }
            .then(if (onLongPress != null) Modifier.pointerInput(onLongPress) { detectTapGestures(onLongPress = { onLongPress() }) } else Modifier),
    )
}

/** A key's touch zone (its share of the control panel), projected onto the screen, with a haptic click. */
@Composable
private fun BoxScope.KeyArea(cam: DeckCamera, key: DeckKey, label: String, tag: String, source: MutableInteractionSource, stateText: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val tick = rememberTick(androidx.compose.ui.hapticfeedback.HapticFeedbackType.VirtualKey)
    val density = LocalDensity.current
    val y = key.centre.y
    val corners = listOf(key.zoneMin.first, key.zoneMax.first).flatMap { x -> listOf(key.zoneMin.second, key.zoneMax.second).map { z -> cam.project(V3(x, y, z)) } }
    val left = corners.minOf { it.x }
    val top = corners.minOf { it.y }
    val right = corners.maxOf { it.x }
    val bottom = corners.maxOf { it.y }
    Box(
        Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(density) { (right - left).toDp() }, with(density) { (bottom - top).toDp() })
            .testTag(tag)
            .semantics { contentDescription = label; if (stateText != null) stateDescription = stateText }
            .clickable(enabled = enabled, interactionSource = source, indication = null, role = Role.Button) { tick(); onClick() },
    )
}

/** The record's rim on the platter, dashed, where a pulled disc will land. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRecordOutline(cam: DeckCamera, colour: Color) {
    val path = Path()
    for (i in 0..96) {
        val a = i / 96f * 2f * PI.toFloat()
        val p = cam.project(V3(DeckModel.platterCentre.x + cos(a) * DeckModel.RECORD_R * 1.02f, DeckModel.RECORD_Y, DeckModel.platterCentre.z + sin(a) * DeckModel.RECORD_R * 1.02f))
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    drawPath(path, colour.copy(alpha = 0.85f), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f))))
}

/** The flat deck's paint for each colourway, after the models' own materials. */
private class FlatColours(val plinth: Color, val panel: Color, val platter: Color, val pivot: Color, val arm: Color, val key: Color, val start: Color) {
    companion object {
        private val WhiteTeal = FlatColours(Color(0xFFEEF0F0), Color(0xFFE9ECEC), Color(0xFF3F5D5A), Color(0xFFDDE3E5), Color(0xFFB9C2C6), Color(0xFF86A6A1), Color(0xFFD0805F))
        private val MonoWhite = FlatColours(Color(0xFFEDECE6), Color(0xFFE6E5DE), Color(0xFF3D4244), Color(0xFFDDE0DE), Color(0xFFB9C2C6), Color(0xFFC4C7C3), Color(0xFFD0805F))
        private val Dark = FlatColours(Color(0xFF30353B), Color(0xFF2B3035), Color(0xFF1C2124), Color(0xFF6B7378), Color(0xFFAAB5B8), Color(0xFF555D64), Color(0xFFD0805F))
        private val Neon = FlatColours(Color(0xFF050506), Color(0xFF050506), Color(0xFF0A0A0B), Color(0xFF2A2A2C), Color(0xFFE6E6E8), Color(0xFF0C0C0D), Color(0xFF0C0C0D))
        fun of(skin: DeckSkin) = when (skin) {
            DeckSkin.MONO_WHITE -> MonoWhite
            DeckSkin.DARK -> Dark
            DeckSkin.NEON -> Neon
            else -> WhiteTeal
        }
    }
}

/** The room's preference for the flat (top-down) view of the deck. */
private const val FLAT_VIEW_KEY = "deck_flat"

/**
 * The deck drawn flat, for the JVM tests (Robolectric has no GPU for Filament) and while the 3D
 * deck starts: the plinth's top,
 * the platter, the record and the arm, each projected with the same camera as the 3D view, so
 * touch lands in the same places.
 */
@Composable
private fun FlatDeck(cam: DeckCamera, deck: DeckState, rotation: Float, armAngle: Float, cover: ImageBitmap?, boundaries: List<Float>, textMeasurer: TextMeasurer, colours: FlatColours, flip: () -> Float = { 0f }, liftOff: () -> Float = { 0f }) {
    val look = remember(deck.album) { discLookFor(deck.album) }
    val labelFallback = remember(deck.album) { fallbackColor(deck.album) }
    Canvas(Modifier.fillMaxSize()) {
        fun outline(y: Float, minX: Float, maxX: Float, minZ: Float, maxZ: Float) = Path().apply {
            val pts = listOf(V3(minX, y, minZ), V3(maxX, y, minZ), V3(maxX, y, maxZ), V3(minX, y, maxZ)).map { cam.project(it) }
            moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close()
        }
        val top = 0.468f
        drawPath(outline(top, -2.5725f, 0.5625f, -1.34f, 1.76f), colours.plinth)
        drawPath(outline(top, 0.5725f, 2.5725f, -1.34f, 1.76f), colours.panel)
        listOf(DeckModel.titleWindow, DeckModel.timeWindow).forEach { (a, b) -> drawPath(outline(top, a.x, b.x, a.z, b.z), Color(0xFF1A0A08)) }
        val c = cam.project(V3(DeckModel.platterCentre.x, DeckModel.RECORD_Y, DeckModel.platterCentre.z))
        val ppu = cam.pixelsPerUnit(DeckModel.platterCentre)
        drawCircle(colours.platter, ppu * DeckModel.PLATTER_R, c)
        if (deck.album != null) {
            // Turning over: squashed to a line on its horizontal axis halfway, then open again.
            val squash = kotlin.math.abs(kotlin.math.cos(flip() * PI.toFloat())).coerceAtLeast(0.02f)
            // Taken off: it rises up out of the deck and draws away.
            val off = liftOff().coerceIn(0f, 1f)
            val lifted = Offset(c.x, c.y - off * ppu * DeckModel.LIFT_OFF_H)
            scale(1f - 0.35f * off, squash * (1f - 0.35f * off), pivot = lifted) {
                drawRecord(lifted, ppu * DeckModel.RECORD_R, rotation, look, cover, labelFallback, boundaries, null, null)
            }
        }
        val pivot = cam.project(V3(DeckModel.pivot.x, 0.84f, DeckModel.pivot.z))
        val stylus = DeckModel.stylus(armAngle).let { cam.project(V3(it.x, 0.70f, it.z)) }
        drawCircle(colours.pivot, ppu * 0.36f, cam.project(V3(DeckModel.pivot.x, 0.58f, DeckModel.pivot.z)))
        drawLine(colours.arm, pivot, stylus, ppu * 0.05f)
        DeckModel.keys.forEach { k ->
            val a = k.centre
            drawPath(outline(a.y, a.x - k.width / 2, a.x + k.width / 2, a.z - k.depth / 2, a.z + k.depth / 2), if (k == DeckModel.startStop) colours.start else colours.key)
        }
    }
}

/**
 * The record's face for the 3D disc: the same drawing as everywhere else in the room (vinyl,
 * grooves, the cover as the label, side and format), painted square so the disc's UVs map it on.
 */
internal fun recordFace(album: Album, cover: ImageBitmap?, boundaries: List<Float>, side: String?, measurer: TextMeasurer): ImageBitmap {
    val px = 1024
    val image = ImageBitmap(px, px)
    val canvas = androidx.compose.ui.graphics.Canvas(image)
    val unit = Density(1f)
    val style = Room.Label.copy(color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Medium, letterSpacing = 3.sp)
    val sideText: TextLayoutResult? = side?.let { measurer.measure("SIDE $it", style, density = unit) }
    val qualityText: TextLayoutResult = measurer.measure(album.quality.shortLabel.uppercase(), style, density = unit)
    CanvasDrawScope().draw(unit, LayoutDirection.Ltr, canvas, Size(px.toFloat(), px.toFloat())) {
        drawRecord(
            center = Offset(px / 2f, px / 2f),
            radius = px / 2f,
            rotation = 0f,
            look = discLookFor(album),
            label = cover,
            labelFallback = fallbackColor(album),
            trackBoundaries = boundaries,
            sideText = sideText,
            qualityText = qualityText,
            // The 3D disc is lit for real: a painted highlight would turn with the platter.
            sheen = false,
        )
    }
    return image
}

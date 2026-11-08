package app.runout.ui

import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Whether the first-start tour of the room, and the storage demo on Settings, are still to be
 * shown. Both start pending, so a new install and everyone updating see each once; About's "how to
 * play the deck" sets the tour pending again.
 */
class TourPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("room", Context.MODE_PRIVATE)
    private val _tour = MutableStateFlow(!prefs.getBoolean(TOUR_SEEN, false))
    private val _storage = MutableStateFlow(!prefs.getBoolean(STORAGE_DEMO_SEEN, false))
    val tourPending: StateFlow<Boolean> = _tour.asStateFlow()
    val storageDemoPending: StateFlow<Boolean> = _storage.asStateFlow()

    fun finishTour() { _tour.value = false; prefs.edit().putBoolean(TOUR_SEEN, true).apply() }
    fun replayTour() { _tour.value = true }
    fun finishStorageDemo() { _storage.value = false; prefs.edit().putBoolean(STORAGE_DEMO_SEEN, true).apply() }

    private companion object {
        const val TOUR_SEEN = "tour_seen"
        const val STORAGE_DEMO_SEEN = "storage_demo_seen"
    }
}

/** One stop on the tour: what it's called, what it says, and whether its spotlight is round. */
internal data class TourStep(val title: String, val body: String, val round: Boolean = false, val fingers: Boolean = false)

/** The tour of the room, from TE · Tour: the deck, its keys, the crate, the record, the display, Flags. */
internal val TOUR = listOf(
    TourStep("your record player", "the deck plays one record at a time. this is where everything is."),
    TourStep("start / stop", "tap to lower the needle and play. tap again to lift it and pause."),
    TourStep("skip", "jump back or forward a track, within the side that's playing."),
    TourStep("eject", "sends the record back into its sleeve, ready for the next one."),
    TourStep("the crate", "flick to browse. tap a sleeve to take the record out, then drag it onto the platter. long-press a sleeve for its details."),
    TourStep("the record", "tap it to turn it over to the other side. hold it to slow it down, like a real one.", round = true),
    TourStep("the display", "shows the track and the time. hold it to see the audio quality, end to end."),
    TourStep("look closer", "two fingers down lay the deck flat. two fingers up stand it back.", fingers = true),
    TourStep("flags", "settings › flags holds the habits: auto-flip, auto-play, the sleep timer, the intro, your deck and vinyl only."),
)

/** The tour's indices, for the steps that move on when the real control is used. */
internal object TourAt {
    const val START_STOP = 1
    const val SKIP = 2
    const val EJECT = 3
    const val FINGERS = 7
    const val FLAGS = 8
}

/** Behind the tour and the storage demo: the room, dimmed. */
private val TourShade = Color.Black.copy(alpha = 0.72f)

/** How far the spotlight stands off what it lights. */
private val SPOT_PAD = 6.dp

/**
 * TE · Tour: the room dimmed around one real control ([target], in this box's coordinates; none
 * centres the card), its edge in signal orange, and an ink card under it (over it when there's no
 * room below) with the step, its words, skip and next. Touches outside the spotlight are held; the
 * control inside it still works, so trying it is how the tour moves on.
 */
@Composable
internal fun RoomTour(index: Int, target: Rect?, bottomClear: Float, onNext: () -> Unit, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val step = TOUR[index]
    val last = index == TOUR.lastIndex
    val density = LocalDensity.current
    val pad = with(density) { SPOT_PAD.toPx() }
    BoxWithConstraints(modifier.fillMaxSize().testTag("tour")) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val hole = target?.let { if (step.round) Rect(it.center, it.width / 2f + pad) else it.inflate(pad) }
        Spotlight(hole, step.round, Modifier.fillMaxSize())
        // What holds touches: everything but the spotlight.
        val blocks = if (hole == null) listOf(Rect(0f, 0f, w, h)) else listOf(
            Rect(0f, 0f, w, hole.top), Rect(0f, hole.bottom, w, h),
            Rect(0f, hole.top, hole.left, hole.bottom), Rect(hole.right, hole.top, w, hole.bottom),
        )
        blocks.filter { it.width > 0f && it.height > 0f }.forEach { b ->
            Box(
                Modifier
                    .offset { IntOffset(b.left.roundToInt(), b.top.roundToInt()) }
                    .size(with(density) { b.width.toDp() }, with(density) { b.height.toDp() })
                    .pointerInput(Unit) { detectTapGestures { } },
            )
        }
        var cardHeight by remember { mutableIntStateOf(0) }
        val gap = with(density) { 16.dp.toPx() }
        val cardY = when {
            hole == null -> (h - cardHeight) / 2f
            hole.bottom + gap + cardHeight <= h - bottomClear -> hole.bottom + gap
            else -> (hole.top - gap - cardHeight).coerceAtLeast(gap)
        }
        Column(
            Modifier
                .offset { IntOffset(0, cardY.roundToInt()) }
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .onSizeChanged { cardHeight = it.height }
                .background(Room.Ink)
                .padding(16.dp)
                .testTag("tour-card"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText("${index + 1} / ${TOUR.size}", style = Room.LabelQuiet, modifier = Modifier.testTag("tour-step"))
            if (step.fingers) Canvas(Modifier.size(28.dp)) { drawTwoFingerSwipe(Room.OnInk) }
            BasicText(step.title, style = Room.Heading.copy(color = Room.OnInk), modifier = Modifier.testTag("tour-title"))
            BasicText(step.body, style = Room.Text.copy(color = Room.OnInk, lineHeight = 16.sp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!last) TourKey("skip", Room.Quiet, "tour-skip", onSkip)
                Spacer(Modifier.weight(1f))
                TourKey(if (last) "done" else "next →", Room.Signal, "tour-next", onNext)
            }
        }
    }
}

/**
 * TE · Settings · first visit · storage demo: the page dimmed around its "swipe up with two
 * fingers" line ([hint]), two fingertips rising under it, and a card above with "got it". It holds
 * no touches but its key, so the real two-finger swipe still opens Storage, which ends it.
 */
@Composable
internal fun StorageDemo(hint: Rect, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val pad = with(density) { 8.dp.toPx() }
    val hole = hint.inflate(pad)
    val rise by rememberInfiniteTransition(label = "storage-demo").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "fingers",
    )
    Box(modifier.fillMaxSize().testTag("storage-demo")) {
        Spotlight(hole, round = false, Modifier.fillMaxSize())
        // Two fingertips sliding up from below the line, their trail fading behind them.
        Canvas(Modifier.fillMaxSize()) {
            val travel = 64.dp.toPx()
            val baseY = hole.bottom + 40.dp.toPx() + travel * (1f - rise)
            val tip = Size(18.dp.toPx(), 24.dp.toPx())
            val fade = if (rise < 0.8f) 1f else (1f - rise) / 0.2f
            listOf(-17.dp.toPx(), 17.dp.toPx()).forEach { dx ->
                val x = size.width / 2f + dx
                drawOval(Color.White.copy(alpha = fade), Offset(x - tip.width / 2f, baseY), tip)
                listOf(0.5f, 0.3f, 0.15f).forEachIndexed { k, a ->
                    val r = 4.dp.toPx()
                    drawCircle(Color.White.copy(alpha = a * fade), r, Offset(x, baseY + tip.height + 10.dp.toPx() + k * 18.dp.toPx()))
                }
            }
        }
        var cardHeight by remember { mutableIntStateOf(0) }
        val cardY = (hole.top - with(density) { 16.dp.toPx() } - cardHeight).coerceAtLeast(0f)
        Column(
            Modifier
                .offset { IntOffset(0, cardY.roundToInt()) }
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .onSizeChanged { cardHeight = it.height }
                .background(Room.Ink)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText("your storage", style = Room.Heading.copy(color = Room.OnInk))
            BasicText(
                "swipe up with two fingers, anywhere on settings, to see what your music takes up and delete what you don't need.",
                style = Room.Text.copy(color = Room.OnInk, lineHeight = 16.sp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Spacer(Modifier.weight(1f))
                TourKey("got it", Room.Signal, "storage-demo-done", onDone)
            }
        }
    }
}

/** The shade with one window cut out of it, edged in signal orange. Holds no touches. */
@Composable
private fun Spotlight(hole: Rect?, round: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val shade = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            if (hole != null) if (round) addOval(hole) else addRect(hole)
        }
        drawPath(shade, TourShade)
        if (hole != null) {
            val edge = Stroke(2.dp.toPx())
            if (round) drawOval(Room.Signal, hole.topLeft, hole.size, style = edge)
            else drawRect(Room.Signal, hole.topLeft, hole.size, style = edge)
        }
    }
}

@Composable
private fun TourKey(text: String, color: Color, tag: String, onClick: () -> Unit) {
    val tick = rememberTick()
    BasicText(
        text,
        style = Room.Text.copy(color = color),
        modifier = Modifier
            .testTag(tag)
            .semantics { contentDescription = text }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { tick(); onClick() }
            .touchHeight()
            .padding(horizontal = 4.dp),
    )
}


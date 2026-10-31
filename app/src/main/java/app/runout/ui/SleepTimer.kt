package app.runout.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs

/** The sleep timer's choices, in minutes: 15 to 50, five apart. */
internal val SLEEP_MINUTES = (15..50 step 5).toList()

/** Whole minutes until [at], at least one while it's still ahead; null when no timer is set. */
@Composable
internal fun minutesLeft(at: Long?): Int? {
    val now by produceState(System.currentTimeMillis(), at) {
        while (at != null) { value = System.currentTimeMillis(); delay(15_000) }
    }
    return at?.let { (((it - now) + 59_999) / 60_000).toInt().coerceAtLeast(1) }
}

/**
 * The moon in the room's top bar (sleep mode on): a crescent of dots in the LED's dot-matrix way,
 * ink while idle, signal orange with the minutes left beside it while the timer runs.
 */
@Composable
internal fun SleepMoon(left: Int?, onClick: () -> Unit) {
    Row(
        Modifier
            .testTag("sleep-moon")
            .semantics(mergeDescendants = true) { contentDescription = if (left != null) "Sleep timer, $left minutes left" else "Sleep timer" }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .touchHeight()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(Modifier.size(16.dp)) { drawDotMoon(if (left != null) Room.Signal else Room.Ink) }
        if (left != null) BasicText("$left", style = Room.Label.copy(color = Room.Signal), maxLines = 1)
    }
}

/** A crescent drawn as dots on a 9 × 9 grid: inside the moon's disc, outside the shadow's. */
internal fun DrawScope.drawDotMoon(ink: Color) {
    val cell = size.width / 9f
    val r = cell * 0.38f
    for (row in 0 until 9) for (col in 0 until 9) {
        val x = col + 0.5f
        val y = row + 0.5f
        val inMoon = (x - 4.5f) * (x - 4.5f) + (y - 4.5f) * (y - 4.5f) <= 4.4f * 4.4f
        val inShadow = (x - 6.9f) * (x - 6.9f) + (y - 3.2f) * (y - 3.2f) <= 3.7f * 3.7f
        if (inMoon && !inShadow) drawCircle(ink, r, Offset(x * cell, y * cell))
    }
}

/**
 * The sleep timer, in a TE card: a wheel of minutes in the track wheel's way (the chosen row sharp
 * between ink rules, its neighbours fading off), a tick under the finger at every step, and the
 * black key to start it. With a timer running it says how long is left and offers to turn it off.
 */
@Composable
internal fun SleepTimerCard(sleepAt: Long?, onSet: (Int?) -> Unit, onDismiss: () -> Unit) {
    val left = minutesLeft(sleepAt)
    val start = SLEEP_MINUTES.indexOfFirst { it >= (left ?: 30) }.let { if (it < 0) SLEEP_MINUTES.lastIndex else it }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val scope = rememberCoroutineScope()
    val rowPx = with(LocalDensity.current) { SLEEP_ROW.toPx() }
    val centred by remember { derivedStateOf { (list.firstVisibleItemIndex + if (list.firstVisibleItemScrollOffset > rowPx / 2) 1 else 0).coerceIn(0, SLEEP_MINUTES.lastIndex) } }
    val tick = rememberTick()
    val started = rememberTick(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
    val stopped = rememberTick(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff)
    // Every step the wheel passes, a tick under the finger.
    LaunchedEffect(list) { snapshotFlow { centred }.drop(1).collect { tick() } }
    TeCard("sleep-timer", onDismiss) {
        BasicText("SLEEP TIMER", style = Room.Title)
        BasicText(
            if (left != null) "the music stops in $left min. it fades out and the needle lifts." else "the music fades out and the needle lifts.",
            style = Room.Label.copy(color = if (left != null) Room.Signal else Room.Body),
            modifier = Modifier.testTag("sleep-left"),
        )
        Box(Modifier.fillMaxWidth().height(SLEEP_ROW * SLEEP_VISIBLE)) {
            LazyColumn(
                state = list,
                flingBehavior = rememberSnapFlingBehavior(list),
                contentPadding = PaddingValues(vertical = SLEEP_ROW * (SLEEP_VISIBLE / 2)),
                modifier = Modifier.fillMaxWidth().height(SLEEP_ROW * SLEEP_VISIBLE).testTag("sleep-wheel"),
            ) {
                items(SLEEP_MINUTES.size) { n ->
                    val focused = n == centred
                    val distance = abs(n - centred)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(SLEEP_ROW)
                            .then(
                                if (focused) Modifier.hairlineTop(Room.Ink).hairlineBottom(Room.Ink)
                                else Modifier.blur(if (distance == 1) 0.6.dp else 1.dp).alpha(if (distance == 1) 0.45f else 0.25f),
                            )
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                                scope.launch { list.animateScrollToItem(n) }
                            }
                            .testTag("sleep:${SLEEP_MINUTES[n]}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        BasicText("%02d".format(n + 1), style = Room.Label.copy(color = if (focused) Room.Signal else Room.Quiet), modifier = Modifier.width(24.dp))
                        BasicText("${SLEEP_MINUTES[n]} min", style = if (focused) Room.Text else Room.Label, maxLines = 1, modifier = Modifier.weight(1f))
                        if (focused) BasicText("▲▼", style = Room.LabelQuiet)
                    }
                }
            }
        }
        TeButton("stop in ${SLEEP_MINUTES[centred]} min", { started(); onSet(SLEEP_MINUTES[centred]); onDismiss() }, Modifier.fillMaxWidth().testTag("sleep-start"))
        if (left != null) TeButton("turn the timer off", { stopped(); onSet(null); onDismiss() }, Modifier.fillMaxWidth().testTag("sleep-off"), kind = KeyKind.OUTLINE)
    }
}

private val SLEEP_ROW = 44.dp
private const val SLEEP_VISIBLE = 5

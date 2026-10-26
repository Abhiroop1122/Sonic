package app.runout.ui

import androidx.compose.ui.text.withStyle
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The page furniture of runout-te.pen: the screen head, the nav bar and the help panel.
 */

/**
 * TE Screen Head: 22 dp in (11 at the foot), "close ×" (or "← back") in 12/400 at the right, then
 * the title in 44/700, 11 dp apart.
 */
@Composable
fun TeScreenHead(title: String, onClose: (() -> Unit)?, modifier: Modifier = Modifier, onBack: () -> (() -> Unit)? = { null }) {
    Column(modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 11.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            HeadKey(onClose, onBack)
        }
        BasicText(title.uppercase(), style = Room.Display, maxLines = 1, modifier = Modifier.testTag("panel-title").semantics { heading() })
    }
}

/**
 * The head's key: "← back" inside a page (a section, a chat, a page reached from another), else
 * "close ×". [onBack] is read here, so a step coming or going redraws only this key.
 */
@Composable
fun HeadKey(onClose: (() -> Unit)?, onBack: () -> (() -> Unit)?) {
    val back = onBack()
    if (back != null) BackKey(back) else if (onClose != null) CloseKey(onClose)
}

/** "close ×", 12/400, right-aligned in 64 dp, with a full-height touch target. */
@Composable
fun CloseKey(onClose: () -> Unit, modifier: Modifier = Modifier) = HeadText("close ×", "Close", onClose, modifier)

/** "← back", in close's place: one step back out of where you are. */
@Composable
fun BackKey(onBack: () -> Unit, modifier: Modifier = Modifier) = HeadText("← back", "Back", onBack, modifier)

@Composable
private fun HeadText(text: String, name: String, onClick: () -> Unit, modifier: Modifier) {
    val tick = rememberTick()
    BasicText(
        text,
        style = Room.Text.copy(textAlign = TextAlign.End),
        maxLines = 1,
        modifier = modifier
            .testTag("action:$name")
            .semantics { contentDescription = name }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { tick(); onClick() }
            .touchHeight()
            .width(64.dp),
    )
}

/**
 * The steps inside the open page that "← back" leaves, innermost last: a Settings section, a chat,
 * a drop or a folder registers itself with [PageStep] while it's showing.
 */
internal class PageSteps {
    var steps by mutableStateOf(listOf<Pair<Any, () -> Unit>>())
}
internal val LocalPageSteps = androidx.compose.runtime.staticCompositionLocalOf<PageSteps?> { null }

/** While this is showing, the page head's key reads "← back" and runs [onBack]. */
@Composable
internal fun PageStep(onBack: () -> Unit) {
    val host = LocalPageSteps.current ?: return
    val key = remember { Any() }
    val current by androidx.compose.runtime.rememberUpdatedState(onBack)
    androidx.compose.runtime.DisposableEffect(host) {
        host.steps = host.steps + (key to { current() })
        onDispose { host.steps = host.steps.filterNot { it.first === key } }
    }
}

/** The places in the nav bar, in its order. */
private val NAV = listOf(Panel.DIG, Panel.DROPS, Panel.FRIENDS, Panel.SETTINGS)

/**
 * TE Nav Bar: four equal cells, 11 dp above and below, a 20 dp Lucide icon over a 10/400 label,
 * 8 dp apart. The open place is a black cell with white ink. An orange square marks news.
 */
@Composable
fun TeNavBar(active: Panel?, badges: Set<Panel>, onOpen: (Panel) -> Unit, modifier: Modifier = Modifier, onHome: (() -> Unit)? = null, upright: Boolean = true) {
    val tick = rememberTick()
    Row(modifier.fillMaxWidth().background(Room.Background)) {
        // On a page, an orange "room" cell slides in from the left: home is under the thumb, not in the top corner.
        AnimatedVisibility(
            visible = onHome != null,
            enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
        ) {
            var last by remember { mutableStateOf(onHome) }
            if (onHome != null) last = onHome
            Column(
                Modifier
                    .width(76.dp)
                    // Home in signal orange, so the way back to the room is the brightest key on the bar.
                    .background(Room.Signal)
                    .testTag("nav:ROOM")
                    .semantics(mergeDescendants = true) { contentDescription = "Room" }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { tick(); last?.invoke() }
                    .padding(vertical = 11.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Canvas(Modifier.size(20.dp)) { drawRoomIcon(Color.White) }
                BasicText("room", style = Room.Label.copy(color = Color.White))
            }
        }
        // Sideways the room is for listening: the places wait until the phone is upright again.
        if (!upright) {
            UprightNote(Modifier.weight(1f))
            return@Row
        }
        NAV.forEach { p ->
            val on = p == active
            val ink = if (on) Room.OnInk else Room.Ink
            Column(
                Modifier
                    .weight(1f)
                    .background(if (on) Room.Ink else Room.Background)
                    .testTag("nav:${p.name}")
                    .semantics(mergeDescendants = true) { contentDescription = p.nav + if (p in badges) ", new" else "" }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { tick(); onOpen(p) }
                    .padding(vertical = 11.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box {
                    Canvas(Modifier.size(20.dp)) { drawNavIcon(p, ink) }
                    if (p in badges) Box(Modifier.size(6.dp).align(Alignment.TopEnd).background(Room.Signal))
                }
                BasicText(p.nav.lowercase(), style = Room.Label.copy(color = ink))
            }
        }
    }
}

/** Lucide's disc: a record with its label ring and spindle, for the "room" cell. */
internal fun DrawScope.drawRoomIcon(color: Color) {
    val k = size.width / 24f
    val s = Stroke(width = 1.8f * k, cap = StrokeCap.Round)
    drawCircle(color, 9.5f * k, Offset(12f * k, 12f * k), style = s)
    drawCircle(color, 2.5f * k, Offset(12f * k, 12f * k), style = s)
    drawArc(color, 200f, 70f, false, Offset(5.5f * k, 5.5f * k), Size(13f * k, 13f * k), style = s)
}

/** The places that need the phone upright: in landscape the room is only for listening. */
internal val UPRIGHT_ONLY = setOf(Panel.DIG, Panel.DROPS, Panel.FRIENDS, Panel.SETTINGS)

/**
 * "Turn your phone upright" with the turn-upright icon: in the nav bar's place in landscape, and
 * larger ([large]) where a place's page would be.
 */
@Composable
fun UprightNote(modifier: Modifier = Modifier, large: Boolean = false) {
    val text = "turn your phone upright to dig, browse drops, chat and change settings."
    if (large) Column(
        modifier.testTag("landscape-note").semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Canvas(Modifier.size(44.dp)) { drawTurnUpright(Room.Ink) }
        BasicText(text, style = Room.Label.copy(color = Room.Body, textAlign = TextAlign.Center, lineHeight = 15.sp), modifier = Modifier.widthIn(max = 260.dp))
    } else Row(
        modifier.testTag("landscape-note").semantics(mergeDescendants = true) {}.padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Canvas(Modifier.size(20.dp)) { drawTurnUpright(Room.Ink) }
        BasicText(text, style = Room.Label.copy(color = Room.Body, lineHeight = 13.sp), maxLines = 2)
    }
}

/**
 * Two fingertips side by side on a short track, with an arrowhead above and below: swipe two
 * fingers down or up. [lift] (0 to 1) slides the fingertips down the track, for a gentle demo.
 */
internal fun DrawScope.drawTwoFingerSwipe(color: Color, lift: Float = 0f) {
    val k = size.width / 24f
    val s = Stroke(width = 1.8f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val dy = 4f * k * lift
    drawRoundRect(color, Offset(5f * k, 6f * k + dy), Size(5.5f * k, 8f * k), CornerRadius(2.75f * k), style = s)
    drawRoundRect(color, Offset(13.5f * k, 6f * k + dy), Size(5.5f * k, 8f * k), CornerRadius(2.75f * k), style = s)
    // Up and down, the two ways the deck turns.
    drawLine(color, Offset(9.5f * k, 2.5f * k), Offset(12f * k, 0.8f * k), s.width, StrokeCap.Round)
    drawLine(color, Offset(14.5f * k, 2.5f * k), Offset(12f * k, 0.8f * k), s.width, StrokeCap.Round)
    drawLine(color, Offset(9.5f * k, 21.5f * k), Offset(12f * k, 23.2f * k), s.width, StrokeCap.Round)
    drawLine(color, Offset(14.5f * k, 21.5f * k), Offset(12f * k, 23.2f * k), s.width, StrokeCap.Round)
}

/** A phone standing upright with a curved arrow turning round it, on the nav icons' 24-unit grid. */
internal fun DrawScope.drawTurnUpright(color: Color) {
    val k = size.width / 24f
    val s = Stroke(width = 1.8f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawRoundRect(color, Offset(10f * k, 3f * k), Size(10f * k, 18f * k), CornerRadius(2f * k), style = s)
    drawLine(color, Offset(14f * k, 18f * k), Offset(16f * k, 18f * k), s.width, StrokeCap.Round)
    // The turn: an arc up the left side, its arrowhead at the top pointing towards the phone.
    drawArc(color, 100f, 150f, false, Offset(2f * k, 5f * k), Size(10f * k, 14f * k), style = s)
    drawLine(color, Offset(5.2f * k, 6.2f * k), Offset(8.4f * k, 5.6f * k), s.width, StrokeCap.Round)
    drawLine(color, Offset(5.2f * k, 6.2f * k), Offset(5.9f * k, 9.3f * k), s.width, StrokeCap.Round)
}

/** Lucide's search, calendar, message-square and sliders-horizontal, drawn on their 24-unit grid. */
internal fun DrawScope.drawNavIcon(panel: Panel, color: Color) {
    val k = size.width / 24f
    // Lucide draws at 2 units on 24; the board renders them a touch lighter at 20 dp.
    val s = Stroke(width = 1.8f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
    run {
        when (panel) {
            Panel.DIG -> {
                drawCircle(color, 7.5f * k, Offset(11f * k, 11f * k), style = s)
                drawLine(color, Offset(21f * k, 21f * k), Offset(16.65f * k, 16.65f * k), s.width, StrokeCap.Round)
            }
            Panel.DROPS -> {
                drawRoundRect(color, Offset(3.5f * k, 4.5f * k), Size(17f * k, 17f * k), CornerRadius(2f * k), style = s)
                drawLine(color, Offset(16f * k, 2.5f * k), Offset(16f * k, 6f * k), s.width, StrokeCap.Round)
                drawLine(color, Offset(8f * k, 2.5f * k), Offset(8f * k, 6f * k), s.width, StrokeCap.Round)
                drawLine(color, Offset(3.5f * k, 10f * k), Offset(20.5f * k, 10f * k), s.width, StrokeCap.Round)
            }
            Panel.FRIENDS -> {
                val p = Path().apply {
                    moveTo(20.5f * k, 15f * k)
                    quadraticTo(20.5f * k, 16.5f * k, 19f * k, 16.5f * k)
                    lineTo(7f * k, 16.5f * k)
                    lineTo(3.5f * k, 20.5f * k)
                    lineTo(3.5f * k, 5f * k)
                    quadraticTo(3.5f * k, 3.5f * k, 5f * k, 3.5f * k)
                    lineTo(19f * k, 3.5f * k)
                    quadraticTo(20.5f * k, 3.5f * k, 20.5f * k, 5f * k)
                    close()
                }
                drawPath(p, color, style = s)
            }
            else -> {
                fun l(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1 * k, y1 * k), Offset(x2 * k, y2 * k), s.width, StrokeCap.Round)
                l(21f, 4f, 14f, 4f); l(10f, 4f, 3f, 4f)
                l(21f, 12f, 12f, 12f); l(8f, 12f, 3f, 12f)
                l(21f, 20f, 16f, 20f); l(12f, 20f, 3f, 20f)
                l(14f, 2f, 14f, 6f); l(8f, 10f, 8f, 14f); l(16f, 18f, 16f, 22f)
            }
        }
    }
}

/**
 * TE Help Panel: white, a 2 dp ink rule along the top, 22 dp in (33 at the foot), 22 dp between
 * parts: [label] in blue ("help · sound › output") and "close ×", the 22/700 title, numbered points under hairlines (10/400,
 * 15 line height, #4D4D4D) and a black "got it" key across the width.
 */
@Composable
fun TeHelpPanel(title: String, points: List<String>, onClose: () -> Unit, modifier: Modifier = Modifier, tag: String = "help-panel", label: String = "info") {
    Column(
        modifier
            .fillMaxWidth()
            .background(Room.Background)
            .hairlineTop(Room.Ink, 2.dp)
            .testTag(tag)
            .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 33.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(label, style = Room.Label.copy(color = Room.Accent), modifier = Modifier.weight(1f))
            CloseKey(onClose)
        }
        BasicText(title.uppercase(), style = Room.Title, modifier = Modifier.fillMaxWidth().semantics { heading() })
        HelpPoints(points)
        TeButton("got it", onClose, Modifier.fillMaxWidth())
    }
}

/** A stats strip ruled in ink above and below: big 22/700 values over 10/400 grey labels. */
@Composable
fun TeStats(stats: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().hairlineTop(Room.Ink).hairlineBottom(Room.Ink)) {
        stats.forEach { (value, label) ->
            Column(Modifier.weight(1f).padding(vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(value, style = Room.Title)
                BasicText(label, style = Room.LabelQuiet)
            }
        }
    }
}

/**
 * A help panel opened by a TE Help "?": the grey scrim over everything and the panel rising from
 * the foot. Back or a tap on the scrim closes it.
 */
@Composable
fun TeHelpSheet(help: TeHelp?, onClose: () -> Unit) {
    if (help == null) return
    // Through the page's sheet host, so inside a page it covers the whole page, edge to edge.
    PageSheet("help:${help.title}", onClose) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(help.label, style = Room.Label.copy(color = Room.Accent), modifier = Modifier.weight(1f))
            CloseKey(onClose)
        }
        BasicText(help.title.uppercase(), style = Room.Title, modifier = Modifier.fillMaxWidth().semantics { heading() })
        HelpPoints(help.points)
        TeButton("got it", onClose, Modifier.fillMaxWidth())
    }
}

@Composable
private fun HelpPoints(points: List<String>) {
    Column(Modifier.fillMaxWidth()) {
        points.forEachIndexed { i, text ->
            Row(Modifier.fillMaxWidth().hairlineTop().padding(vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                BasicText("%02d".format(i + 1), style = Room.LabelQuiet)
                BasicText(text, style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp), modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * What's new: this version's section of CHANGELOG.md in a help panel's dress. Bullets numbered,
 * the ones under them indented; **bold** and `code` read as they do on GitHub.
 */
@Composable
fun WhatsNewSheet(notes: app.runout.update.WhatsNew.Section?, onClose: () -> Unit) {
    if (notes == null) return
    PageSheet("whats-new:${notes.version}", onClose) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText("what's new" + (notes.date?.let { " · $it" } ?: ""), style = Room.Label.copy(color = Room.Accent), modifier = Modifier.weight(1f))
            CloseKey(onClose)
        }
        BasicText("SONIC ${notes.version}", style = Room.Title, modifier = Modifier.fillMaxWidth().semantics { heading() }.testTag("whats-new-title"))
        notes.lede?.let { BasicText(markdownLine(it), style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp)) }
        Column(Modifier.fillMaxWidth().testTag("whats-new")) {
            var n = 0
            notes.items.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().then(if (item.depth == 0) Modifier.hairlineTop() else Modifier).padding(start = if (item.depth == 0) 0.dp else 33.dp, top = 11.dp, bottom = if (item.depth == 0) 11.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    BasicText(if (item.depth == 0) "%02d".format(++n) else "·", style = Room.LabelQuiet)
                    BasicText(markdownLine(item.text), style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp), modifier = Modifier.weight(1f))
                }
            }
        }
        TeButton("got it", onClose, Modifier.fillMaxWidth().testTag("whats-new-ok"))
    }
}

/** A CHANGELOG line as text: **bold** in ink at 500, *italic* slanted, `code` and the marks themselves dropped. */
private fun markdownLine(line: String): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    val marks = Regex("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*")
    var at = 0
    for (m in marks.findAll(line)) {
        append(line.substring(at, m.range.first).replace("`", ""))
        val bold = m.groupValues[1].isNotEmpty()
        val style = if (bold) androidx.compose.ui.text.SpanStyle(color = Room.Ink, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
        else androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
        withStyle(style) { append((if (bold) m.groupValues[1] else m.groupValues[2]).replace("`", "")) }
        at = m.range.last + 1
    }
    append(line.substring(at).replace("`", ""))
}

/** One help panel's words, from the TE · Help panels board. */
data class TeHelp(val label: String, val title: String, val points: List<String>)

/** The grey scrim behind every panel and dialog (#767676 at 60 %). */
@Composable
fun TeScrim(onDismiss: () -> Unit, modifier: Modifier = Modifier, tag: String? = null) {
    Box(
        modifier
            .background(Room.Scrim)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    )
}

/**
 * A vinyl record drawn in round dots, like the dotted cut of Array: rings of dots around a
 * label and a spindle hole. [lit] (0 to 1) is how much of each ring is inked from 12 o'clock
 * clockwise, starting at [phase] turns; the rest is drawn in [dim].
 */
@Composable
fun DotVinyl(
    modifier: Modifier = Modifier,
    phase: Float = 0f,
    lit: Float = 1f,
    ink: Color = Room.Ink,
    dim: Color = Room.HairlineSoft,
    label: Color = Room.Signal,
) {
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f
        val dot = r * 0.055f
        val rings = 6
        for (k in 0 until rings) {
            val rr = r * (0.42f + 0.55f * k / (rings - 1))
            val n = (2 * Math.PI * rr / (dot * 2.6f)).toInt().coerceAtLeast(8)
            for (i in 0 until n) {
                val f = i.toFloat() / n
                val a = ((f + phase) * 2 * Math.PI - Math.PI / 2).toFloat()
                val on = f <= lit
                drawCircle(if (on) ink else dim, dot, Offset(c.x + rr * kotlin.math.cos(a), c.y + rr * kotlin.math.sin(a)))
            }
        }
        // The label: a disc of dots in the accent, and the spindle hole.
        val lr = r * 0.28f
        var y = -lr
        while (y <= lr) {
            var x = -lr
            while (x <= lr) {
                if (x * x + y * y <= lr * lr && x * x + y * y > (dot * 1.6f) * (dot * 1.6f)) drawCircle(label, dot * 0.8f, Offset(c.x + x, c.y + y))
                x += dot * 2.4f
            }
            y += dot * 2.4f
        }
    }
}

/**
 * [DotVinyl] turning, its rings inking round and round: the waiting animation (Dig's search, and
 * anything else the room waits on). [description] says what it waits for, to a screen reader.
 */
@Composable
fun DotVinylSpinner(modifier: Modifier = Modifier, description: String = "Searching") {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "dot-vinyl")
    val spin by t.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1800, easing = androidx.compose.animation.core.LinearEasing)), label = "spin")
    val ink by t.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1200, easing = androidx.compose.animation.core.FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse), label = "ink")
    DotVinyl(modifier.semantics { contentDescription = description }, phase = spin, lit = 0.15f + 0.85f * ink)
}

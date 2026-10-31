package app.runout.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/*
 * The room's controls, drawn to the TE · Components board in runout-te.pen: zero radius, 1 dp
 * outlines, black when active, no shadows. Sizes, paddings and type follow the board exactly.
 */

/**
 * Keeps an element's drawn size but gives its touch target at least [min] of height, with the
 * drawing centred inside. Put it after `clickable` so the whole box answers the tap.
 */
internal fun Modifier.touchHeight(min: Dp = 48.dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0))
    val height = maxOf(placeable.height, min.roundToPx()).coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(placeable.width, height) { placeable.place(0, (height - placeable.height) / 2) }
}

/** The three kinds of TE key: black, outlined, and orange for the one action that matters. */
enum class KeyKind { FILLED, OUTLINE, SIGNAL }

/** TE Key: 12/400 label, 16 × 22 padding. Black (white text), outlined, or orange (black text). */
@Composable
fun TeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: KeyKind = KeyKind.FILLED,
    enabled: Boolean = true,
) {
    val tick = rememberTick()
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { tick(); onClick() }
            .then(
                when (kind) {
                    KeyKind.FILLED -> Modifier.background(Room.Ink)
                    KeyKind.SIGNAL -> Modifier.background(Room.Signal)
                    KeyKind.OUTLINE -> Modifier.background(Room.Background).border(1.dp, Room.Ink)
                },
            )
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = Room.Text.copy(color = if (kind == KeyKind.FILLED) Room.OnInk else Room.Ink), maxLines = 1)
    }
}

/** A key in a panel: a TE Key, black when [active], outlined otherwise. */
@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
) = TeButton(text, onClick, modifier, if (active) KeyKind.FILLED else KeyKind.OUTLINE, enabled)

/** TE Link: 12/400 text and an arrow, 8 apart. */
@Composable
fun TeLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, tag: String = "link:$text", arrow: String = "→", color: Color = Room.Ink) {
    val tick = rememberTick()
    Row(
        modifier
            .testTag(tag)
            .semantics(mergeDescendants = true) {}
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { tick(); onClick() }
            .touchHeight(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text, style = Room.Text.copy(color = color))
        if (arrow.isNotEmpty()) BasicText(arrow, style = Room.Text.copy(color = color))
    }
}

/** TE Tag: a 1 dp outlined label, 3 × 6 padding, 10/400. Orange for hi-res, ink otherwise. */
@Composable
fun TeTag(text: String, modifier: Modifier = Modifier, color: Color = Room.Ink) {
    BasicText(text, style = Room.Label.copy(color = color), maxLines = 1, modifier = modifier.border(1.dp, color).padding(horizontal = 8.dp, vertical = 4.dp))
}

/** TE Status: an 8 dp square lamp and a 10/400 label. */
@Composable
fun TeStatus(text: String?, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color))
        if (text != null) BasicText(text, style = Room.Label)
    }
}

/**
 * TE Toggle Row: a hairline above, 16 dp above and below, the 12/400 title over a 10/400 grey line,
 * and the switch. [more] adds a "?" after the title that opens the longer explanation underneath.
 */
@Composable
fun ToggleRow(
    title: String,
    detail: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    more: String? = null,
) {
    var open by remember(title) { mutableStateOf(false) }
    val toggled = rememberTick(if (checked) androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff else androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn)
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .hairlineTop()
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Switch) { toggled(); onChange(!checked) }
            .semantics { stateDescription = if (checked) "On" else "Off" }
            .testTag("toggle:$title")
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(title.lowercase(), style = Room.Text)
                if (more != null) InfoDot(title) { open = !open }
            }
            if (detail != null) BasicText(detail.lowercase(), style = Room.LabelQuiet)
            if (open && more != null) BasicText(more.lowercase(), style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp), modifier = Modifier.testTag("more:$title"))
        }
        Switch(checked)
    }
}

/**
 * TE Row: a hairline above, 16 dp above and below, a 22 dp index column, the 12/400 title over a
 * 10/400 grey line, and an arrow.
 */
@Composable
fun TeRow(
    index: String?,
    title: String,
    detail: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String = "→",
    titleColor: Color = Room.Ink,
) {
    val tick = rememberTick()
    Row(
        modifier
            .fillMaxWidth()
            .hairlineTop()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { tick(); onClick() }
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (index != null) BasicText(index, style = Room.LabelQuiet, modifier = Modifier.width(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(title, style = Room.Text.copy(color = titleColor), maxLines = 2)
            if (detail != null) BasicText(detail, style = Room.LabelQuiet, maxLines = 2)
        }
        if (trailing.isNotEmpty()) BasicText(trailing, style = Room.Text)
    }
}

/** TE Help: a 16 dp outlined square with "?" in it (with a 32 dp touch target). */
@Composable
internal fun InfoDot(about: String, onClick: () -> Unit) {
    Box(
        Modifier
            .testTag("info:$about")
            .semantics { contentDescription = "More about $about" }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .size(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(16.dp).border(1.dp, Room.Ink), contentAlignment = Alignment.Center) {
            BasicText("?", style = Room.Label)
        }
    }
}

/**
 * A small square key for EQ presets and the download controls: an outline that greys a little
 * when pressed, solid black when [selected]. Label 10/400.
 */
@Composable
internal fun TeKey(
    label: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    description: String? = label,
    icon: (androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit)? = null,
) {
    val tick = rememberTick(androidx.compose.ui.hapticfeedback.HapticFeedbackType.VirtualKey)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val ink = if (selected) Room.OnInk else Room.Ink
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, interactionSource = source, indication = null, role = Role.Button) { tick(); onClick() }
            .semantics { if (description != null) contentDescription = description; if (selected) stateDescription = "Selected" }
            .touchHeight()
            .heightIn(min = 34.dp)
            .widthIn(min = 40.dp)
            .drawBehind {
                drawRect(if (selected) Room.Ink else if (pressed) Room.HairlineSoft else Room.Background)
                drawRect(Room.Ink, style = Stroke(1.dp.toPx()))
            }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Canvas(Modifier.size(16.dp)) { icon(ink) }
        else if (label != null) BasicText(label, style = Room.Label.copy(color = ink), maxLines = 1)
    }
}

/** TE Switch, 44 × 22 with 2 dp inside: on, an ink outline and an 18 dp ink knob at the right; off, grey and hollow at the left. */
@Composable
internal fun Switch(checked: Boolean) {
    Canvas(Modifier.size(44.dp, 24.dp)) {
        val line = 1.dp.toPx()
        val c = if (checked) Room.Ink else Room.Faint
        drawRect(c, topLeft = Offset(line / 2, line / 2), size = Size(size.width - line, size.height - line), style = Stroke(line))
        val pad = 2.dp.toPx()
        val knob = 18.dp.toPx()
        val x = if (checked) size.width - pad - knob else pad
        if (checked) drawRect(Room.Ink, Offset(x, pad), Size(knob, knob))
        else drawRect(Room.Faint, Offset(x + line / 2, pad + line / 2), Size(knob - line, knob - line), style = Stroke(line))
    }
}

/** A wrapping row of choices; one is selected (black). */
@Composable
fun <T> Chips(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> TeKey(label(o), { onSelect(o) }, selected = o == selected, enabled = enabled) }
    }
}

/** A row of colour swatches, square. */
@Composable
fun ColourDots(colours: List<Int>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        colours.forEach { c ->
            Box(
                Modifier
                    .size(24.dp)
                    .border(if (c == selected) 2.dp else 0.dp, if (c == selected) Room.Ink else Color.Transparent)
                    .padding(4.dp)
                    .background(Color(c))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(c) }
                    .semantics { contentDescription = "Colour ${Integer.toHexString(c)}" },
            )
        }
    }
}

/** A section label, as "01 signal": 10/400 grey, set as given. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    BasicText(text, style = Room.LabelQuiet, modifier = modifier.padding(top = 24.dp, bottom = 12.dp))
}

/**
 * TE Field: an optional 10/400 grey [label], 8 dp, then the 16/400 value (placeholder in #B2B2B2)
 * over a 1 dp ink rule, 8 dp under the text.
 */
@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    secret: Boolean = false,
    onSubmit: (() -> Unit)? = null,
    label: String? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) BasicText(label, style = Room.LabelQuiet)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = when {
                secret -> KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
                onSubmit != null -> KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search)
                else -> KeyboardOptions.Default
            },
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmit?.invoke() }),
            textStyle = Room.Heading,
            cursorBrush = SolidColor(Room.Signal),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("field:$placeholder")
                .semantics { contentDescription = placeholder }
                .drawBehind { drawLine(Room.Ink, Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 1.dp.toPx()) }
                .padding(top = 8.dp, bottom = 8.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) BasicText(placeholder.lowercase(), style = Room.Heading.copy(color = Room.Faint))
                    inner()
                }
            },
        )
    }
}

/**
 * One band of the graphic EQ: a hairline rail with a square black knob. Drag to set the gain,
 * double-tap to reset to 0 dB.
 */
@Composable
fun EqFader(
    label: String,
    gainDb: Float,
    maxDb: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val current by rememberUpdatedState(gainDb)
    val change by rememberUpdatedState(onChange)
    Column(
        modifier.alpha(if (enabled) 1f else 0.4f).semantics { contentDescription = "$label Hz ${"%.1f".format(gainDb)} dB" }.testTag("fader:$label"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            (if (gainDb > 0.05f) "+" else "") + if (gainDb.roundToInt().toFloat() == gainDb) gainDb.roundToInt().toString() else "%.1f".format(gainDb),
            style = Room.Label.copy(textAlign = TextAlign.Center, color = if (kotlin.math.abs(gainDb) < 0.05f) Room.Quiet else Room.Ink),
        )
        Canvas(
            Modifier
                .width(26.dp)
                .height(118.dp)
                .pointerInput(enabled, maxDb) {
                    if (!enabled) return@pointerInput
                    var start = 0f
                    var travelled = 0f
                    detectVerticalDragGestures(
                        onDragStart = { start = current; travelled = 0f },
                    ) { ch, dy ->
                        ch.consume()
                        travelled += dy
                        val perPx = (2 * maxDb) / size.height
                        val next = ((start - travelled * perPx) * 2f).roundToInt() / 2f
                        change(next.coerceIn(-maxDb, maxDb))
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(onDoubleTap = { change(0f) })
                },
        ) {
            val cx = size.width / 2f
            val line = 1.dp.toPx()
            drawLine(Room.Hairline, Offset(cx, 0f), Offset(cx, size.height), line)
            drawLine(Room.Faint, Offset(cx - 5.dp.toPx(), size.height / 2f), Offset(cx + 5.dp.toPx(), size.height / 2f), line)
            val y = size.height / 2f - (gainDb / maxDb).coerceIn(-1f, 1f) * size.height / 2f
            val knob = 14.dp.toPx()
            drawRect(Room.Ink, Offset(cx - knob / 2, y - knob / 2), Size(knob, knob))
        }
        BasicText(label, style = Room.LabelQuiet.copy(textAlign = TextAlign.Center))
    }
}

/** A boxed progress bar. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, colour: Color = Room.Ink) {
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        drawRect(colour, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
        drawRect(Room.Ink, style = Stroke(1.dp.toPx()))
    }
}

/** A section heading: a 10/400 grey kicker, a 22/700 title and an optional 10/400 line under it. */
@Composable
internal fun ConsoleTitle(kicker: String, title: String, detail: String? = null) {
    BasicText(kicker.lowercase(), style = Room.LabelQuiet, modifier = Modifier.padding(bottom = 12.dp))
    BasicText(sentence(title), style = Room.Title)
    if (detail != null) BasicText(detail.lowercase(), style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp), modifier = Modifier.padding(top = 12.dp, bottom = 12.dp))
}

/** A flat grey tile (#F5F5F5) for one item, 11 dp inside. */
@Composable
internal fun ConsoleCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Room.Surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** A 1 dp hairline along the top edge, as between list rows. */
internal fun Modifier.hairlineTop(colour: Color = Room.Hairline, width: Dp = 1.dp): Modifier = drawBehind {
    drawLine(colour, Offset(0f, width.toPx() / 2), Offset(size.width, width.toPx() / 2), width.toPx())
}

/** A 1 dp hairline along the bottom edge. */
internal fun Modifier.hairlineBottom(colour: Color = Room.Hairline, width: Dp = 1.dp): Modifier = drawBehind {
    drawLine(colour, Offset(0f, size.height - width.toPx() / 2), Offset(size.width, size.height - width.toPx() / 2), width.toPx())
}

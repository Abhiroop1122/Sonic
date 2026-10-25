package app.runout.ui.settings

import kotlin.math.floor
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

// ---------------------------------------------------------------------------------------------
// Sound

/**
 * The help panels behind the Sound page's "?" squares, worded as on the TE · Help panels board.
 * The verdict's opens from the signal section at the head of the Settings list.
 */
internal object SoundHelp {
    val PURE = TeHelp("help · sound › output", "pure", listOf(
        "pure sends each file to the output untouched: no eq, no replaygain, no limiter, no warmth, no crackle.",
        "use it with a usb dac or amp that does its own shaping. on android 14 and later, a usb dac that offers it plays bit-perfect. the other sound settings rest while pure is on.",
    ))
    val HI_RES = TeHelp("help · sound › output", "hi-res output", listOf(
        "keeps 24-bit files at full depth on the way to android, as 32-bit float. android's mixer still sets the output rate, usually 48 khz, unless pure and a usb dac play bit-perfect; the signal section says which.",
        "when off, sonic sends 16-bit: a 16-bit file goes out as it is, and a deeper or processed one is dithered once.",
    ))
    val VERDICT = TeHelp("help · sound › signal", "the signal verdict", listOf(
        "one line on what reaches your ears: the track, what sonic does to it, and what the output takes.",
        "green: bit-perfect, nothing changed. amber: a lossless source, but resampled, processed or cut to 16-bit on the way. red: lossy, because bluetooth re-encodes the music.",
        "hold the deck's led window to see this chain from the room.",
    ))
    val EQ = TeHelp("help · sound › equaliser", "eq", listOf(
        "ten bands from 31 hz to 16 khz, applied after replaygain.",
        "pick a preset, drag a band, or import an autoeq profile. sonic lowers the level a little first, so boosts never clip.",
    ))
    val REPLAYGAIN = TeHelp("help · sound › loudness", "replaygain", listOf(
        "evens out loudness, so you don't reach for the volume between records.",
        "album keeps quiet and loud songs as the record intends. track makes every song as loud as the next.",
        "off plays each file at its mastered level.",
    ))
    val LIMITER = TeHelp("help · sound › loudness", "limiter", listOf(
        "catches the loudest peaks before they distort when eq, warmth or replaygain lift the level.",
        "it only switches in when the level could go over, and then only acts on those peaks; most records never touch it.",
    ))
    val WARMTH = TeHelp("help · sound › loudness", "warmth", listOf(
        "a gentle low lift and high roll-off, like a warm cartridge.",
        "it changes the sound, so the signal verdict stops saying bit-perfect while it's on.",
    ))
}

/**
 * Settings › Sound, as the TE · Settings · Sound board: 01 signal (how what's playing reaches you,
 * the chain from the Audio quality panel), 02 output (crackle first), 03 equaliser and 04 loudness,
 * 22 dp apart. Each "?" opens its help panel.
 */
@Composable
fun SoundPanel(s: SoundSettings, crackleOn: Boolean, actions: RoomActions, output: app.runout.sound.OutputStatus = app.runout.sound.OutputStatus(), state: RoomState? = null) {
    var help by remember { mutableStateOf<TeHelp?>(null) }
    val dsp = !s.pure
    Column(Modifier.fillMaxWidth().testTag("panel:sound"), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        PageSection("01 signal") {
            SignalChain(signalPath(output, s, state?.bluetoothCodec?.takeIf { state.bluetoothCodecOn }))
            if (state != null && (output.device.kind == app.runout.sound.OutputKind.BLUETOOTH || state.bluetoothCodecOn)) BluetoothCodecToggle(state, actions)
        }
        PageSection("02 output") {
            // Crackle moved here from the home screen: it's part of how the deck sounds.
            ToggleRow("Crackle", "Surface noise under the music.", crackleOn, { actions.toggleCrackle() })
            HelpToggle("Pure", "The music, untouched.", s.pure, { on -> actions.updateSound { it.copy(pure = on) } }) { help = SoundHelp.PURE }
            HelpToggle("Hi-res output", "Keeps 24-bit detail all the way to the output.", s.hiResOutput, { on -> actions.updateSound { it.copy(hiResOutput = on) } }) { help = SoundHelp.HI_RES }
        }
        PageSection("03 equaliser") {
            HelpToggle(
                "EQ",
                if (s.useParametric) "AutoEq headphone profile · ${s.parametric.size} filters" else "10-band graphic EQ · ${s.presetName}",
                s.eqEnabled,
                { on -> actions.updateSound { it.copy(eqEnabled = on) } },
                enabled = dsp,
            ) { help = SoundHelp.EQ }
            PresetGrid(s, actions, dsp)
            Row(
                Modifier.fillMaxWidth().hairlineTop().hairlineBottom().padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SoundSettings.GRAPHIC_LABELS.forEachIndexed { i, label ->
                    BandFader(
                        label = label,
                        gainDb = s.graphic.getOrElse(i) { 0f },
                        maxDb = SoundSettings.MAX_GAIN_DB,
                        enabled = dsp && !s.useParametric,
                        onChange = { g ->
                            actions.updateSound { cur ->
                                val next = cur.graphic.toMutableList().also { it[i] = g }
                                val named = Presets.all.firstOrNull { it.second == next }?.first ?: "Custom"
                                cur.copy(graphic = next, presetName = named, eqEnabled = true, useParametric = false)
                            }
                        },
                    )
                }
            }
            val pre = if (s.eqEnabled) s.preampDb() else 0f
            BasicText(
                if (pre < -0.05f) "automatic headroom ${"%.1f".format(pre)} db, so boosted bands never clip" else "no boost, no headroom needed",
                style = Room.LabelQuiet.copy(lineHeight = 15.sp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                TeButton("import autoeq…", actions::importAutoEq, Modifier.testTag("import-autoeq"), kind = KeyKind.OUTLINE, enabled = dsp)
                if (s.useParametric) FlatLink("use graphic eq", { actions.updateSound { it.copy(useParametric = false, presetName = "Custom") } }, tag = "use-graphic-eq", enabled = dsp)
            }
        }
        PageSection("04 loudness") {
            Column(
                Modifier.fillMaxWidth().alpha(if (dsp) 1f else 0.4f).hairlineTop().padding(vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    BasicText("replaygain", style = Room.Text)
                    HelpSquare("ReplayGain") { help = SoundHelp.REPLAYGAIN }
                }
                Segments(ReplayGainMode.entries.toList(), s.replayGain, { it.label.lowercase() }, { m -> actions.updateSound { it.copy(replayGain = m) } }, enabled = dsp)
            }
            HelpToggle("Limiter", "Catches peaks.", s.limiter, { on -> actions.updateSound { it.copy(limiter = on) } }, enabled = dsp) { help = SoundHelp.LIMITER }
            HelpToggle("Warmth", "Like a warm cartridge.", s.warmth, { on -> actions.updateSound { it.copy(warmth = on) } }, enabled = dsp) { help = SoundHelp.WARMTH }
        }
    }
    TeHelpSheet(help) { help = null }
}

/**
 * The EQ presets as one outlined block of keys, four to a row, 11 dp above and below each 10/400
 * label; the one in use is black. "custom" lights when the bands match no preset.
 */
@Composable
private fun PresetGrid(s: SoundSettings, actions: RoomActions, enabled: Boolean) {
    val names = Presets.all.map { it.first } + "Custom"
    val custom = !s.useParametric && Presets.all.none { it.first == s.presetName }
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f).border(1.dp, Room.Ink)) {
        names.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { name ->
                    val on = if (name == "Custom") custom else name == s.presetName && !s.useParametric
                    val pick = if (name == "Custom") ({ actions.updateSound { it.copy(useParametric = false, presetName = "Custom", eqEnabled = true) } }) else ({ actions.applyPreset(name) })
                    Box(
                        Modifier
                            .weight(1f)
                            .background(if (on) Room.Ink else Room.Background)
                            .testTag("preset:$name")
                            .semantics { if (on) stateDescription = "Selected" }
                            .overhangTap(enabled = enabled, onClick = pick)
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(name.lowercase(), style = Room.Label.copy(color = if (on) Room.OnInk else Room.Ink), maxLines = 1)
                    }
                }
            }
        }
    }
}

/** A row of choices in one 1 dp outline, equal cells 11 dp above and below; the chosen one black. */
@Composable
private fun <T> Segments(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().border(1.dp, Room.Ink)) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(if (on) Room.Ink else Room.Background)
                    .semantics { if (on) stateDescription = "Selected" }
                    .overhangTap(enabled = enabled) { onSelect(o) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(label(o), style = Room.Label.copy(color = if (on) Room.OnInk else Room.Ink), maxLines = 1)
            }
        }
    }
}

/**
 * One band of the graphic EQ, as on the board: the 10/400 gain, 8 dp, a 120 dp hairline rail with an
 * 8 dp zero mark and a 14 dp black knob, 8 dp, the grey frequency. Drag to set, double-tap for 0 dB.
 */
@Composable
private fun BandFader(label: String, gainDb: Float, maxDb: Float, onChange: (Float) -> Unit, enabled: Boolean = true) {
    val current by rememberUpdatedState(gainDb)
    val change by rememberUpdatedState(onChange)
    Column(
        Modifier.alpha(if (enabled) 1f else 0.4f).semantics { contentDescription = "$label Hz ${"%.1f".format(gainDb)} dB" }.testTag("fader:$label"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            (if (gainDb > 0.05f) "+" else "") + if (gainDb.roundToInt().toFloat() == gainDb) gainDb.roundToInt().toString() else "%.1f".format(gainDb),
            style = Room.Label.copy(color = if (abs(gainDb) < 0.05f) Room.Quiet else Room.Ink),
        )
        // A detent at every whole dB under the finger, a firmer one at 0 dB, and a confirm when a
        // double tap sets it back to flat.
        val detent = rememberTick(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentFrequentTick)
        val centre = rememberTick(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
        val reset = rememberTick(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
        // 14 dp drawn, a little wider to grab.
        Canvas(
            Modifier
                .width(24.dp)
                .height(120.dp)
                .pointerInput(enabled, maxDb) {
                    if (!enabled) return@pointerInput
                    var start = 0f
                    var travelled = 0f
                    var felt = 0f
                    detectVerticalDragGestures(onDragStart = { start = current; travelled = 0f; felt = current }) { ch, dy ->
                        ch.consume()
                        travelled += dy
                        val perPx = (2 * maxDb) / size.height
                        val next = (((start - travelled * perPx) * 2f).roundToInt() / 2f).coerceIn(-maxDb, maxDb)
                        if (next != felt) {
                            if (next == 0f || (felt > 0f && next < 0f) || (felt < 0f && next > 0f)) centre()
                            else if (floor(next) != floor(felt)) detent()
                            felt = next
                        }
                        change(next)
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(onDoubleTap = { if (current != 0f) reset(); change(0f) })
                },
        ) {
            val cx = size.width / 2f
            val line = 1.dp.toPx()
            val knob = 14.dp.toPx()
            drawRect(Room.Hairline, Offset(cx - line / 2, 0f), Size(line, size.height))
            drawRect(Room.Faint, Offset(cx - 4.dp.toPx(), size.height / 2f - line / 2), Size(8.dp.toPx(), line))
            // The knob travels the rail's length less its own size, centred on 0 dB.
            val y = (size.height - knob) / 2f * (1f - (gainDb / maxDb).coerceIn(-1f, 1f))
            drawRect(Room.Ink, Offset(cx - knob / 2, y), Size(knob, knob))
        }
        BasicText(label, style = Room.LabelQuiet)
    }
}

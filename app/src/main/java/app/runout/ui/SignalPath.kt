package app.runout.ui

import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.runout.model.AudioQuality
import app.runout.sound.BitPerfect
import app.runout.sound.OutputDevices
import app.runout.sound.OutputKind
import app.runout.sound.OutputStatus
import app.runout.sound.SoundSettings

/**
 * The way the music travels, in three steps like a hi-fi signal chain: the file on the record, what
 * Runout does to it, and what the phone sends out. Each step has a short spec, so you can see where
 * quality is kept and where it's changed.
 */
data class SignalPath(
    val track: Step,
    val runout: Step,
    val output: Step,
    val verdict: String,
    val short: String,
    val note: String?,
    /** The file's own samples reach the DAC: the verdict's lamp is green. */
    val bitPerfect: Boolean = false,
    /** Lossy on the way out (Bluetooth): the lamp is red. */
    val lossy: Boolean = false,
) {
    data class Step(val title: String, val spec: String, val detail: String, val ok: Boolean = true)
}

private fun rate(hz: Int) = AudioQuality.formatRate(hz)

/**
 * The chain for what's playing, or null when nothing is. Honest by rule: "bit-perfect" only when
 * a USB DAC media is routed to takes the file's own samples in an exactly matching mode; the bit
 * depth is the file's, not the decoder's; Bluetooth is lossy, whatever else is on.
 */
fun signalPath(o: OutputStatus, s: SoundSettings, codec: app.runout.sound.BluetoothCodec? = null): SignalPath? {
    if (o.sampleRate == 0) return null
    val fileBits = o.sourceBits
    val bits = if (fileBits > 0) "$fileBits-bit" else "PCM"
    val source = "$bits · ${rate(o.sampleRate)}"
    val hiResFile = fileBits > 16 || o.sampleRate > 48_000
    val track = SignalPath.Step(
        "FLAC", source,
        when {
            hiResFile -> "Hi-res lossless"
            fileBits == 16 -> "CD-quality lossless"
            else -> "Lossless"
        },
    )

    val carried = if (o.floatPath) "32-bit float · ${rate(o.sampleRate)}" else "16-bit · ${rate(o.sampleRate)}"
    // The decoder itself delivered fewer bits than the file has (Android 9's FLAC decoder does).
    val decoderCut = o.decodedBits in 1 until fileBits
    val folded = decoderCut || (!o.floatPath && fileBits > 16)
    val foldNote = when {
        !folded -> ""
        decoderCut -> " This phone's FLAC decoder gives 16-bit, so $fileBits-bit is cut to 16-bit."
        else -> " Hi-res output is off, so $fileBits-bit is reduced to 16-bit, with dither."
    }
    val bluetooth = o.device.kind == OutputKind.BLUETOOTH
    val crackle = o.crackle && !s.pure
    val dsp = if (s.pure) emptyList() else buildList {
        if (s.eqEnabled && (s.useParametric && s.parametric.any { it.enabled } || !s.useParametric && s.graphic.any { kotlin.math.abs(it) >= 0.05f })) {
            add(if (s.useParametric) "AutoEq" else "EQ ${s.presetName}")
        }
        if (o.replayGainDb != 0f) add("ReplayGain")
        if (s.warmth) add("warmth")
        if (o.limiting) add("limiter")
    }
    val processed = dsp.isNotEmpty() || crackle
    val runout = when {
        s.pure -> SignalPath.Step("Pure", carried, "Untouched: no EQ, ReplayGain, crackle or wind-down.$foldNote", ok = !folded)
        else -> SignalPath.Step(
            if (processed) "Processing" else "Clean",
            carried,
            buildString {
                if (dsp.isNotEmpty()) append(dsp.joinToString(", ").replaceFirstChar { it.uppercase() } + " in ${if (o.floatPath) "32-bit float" else "16-bit"}.")
                if (crackle) append((if (isEmpty()) "" else " ") + "Crackle is mixed in with the music.")
                if (isEmpty()) append("Nothing changes the sound.")
                append(foldNote)
            },
            ok = !folded,
        )
    }

    val d = o.device
    // Pure asks Android not to spatialize, so only outside Pure does Spatial Audio touch the sound.
    val spatial = d.spatial && !s.pure
    val spatialNote = if (spatial) " Android's Spatial Audio also reshapes it into 3D sound: turn it off for this output in Android's settings, or switch Pure on." else ""
    val mixer = d.mixerRate
    val resampled = mixer > 0 && mixer != o.sampleRate
    // A USB DAC lists the rates it takes: a file at any other rate is resampled before it gets there.
    val dacResamples = d.kind == OutputKind.USB && d.rates.isNotEmpty() && o.sampleRate !in d.rates
    val bitPerfect = o.bitPerfect == BitPerfect.ON && d.kind == OutputKind.USB && s.pure && !folded
    val routed = when {
        // Bluetooth first: nothing that goes over it is bit-perfect or lossless.
        // With the codec readout on, what Android's Bluetooth stack says it is; lossy either way.
        bluetooth && codec != null -> SignalPath.Step(
            d.label, codec.label,
            "Bluetooth re-encodes the music as ${codec.label}, as Android reports it. " +
                (if (codec.name == "LDAC") "Even LDAC is lossy." else "It's a lossy codec.") + spatialNote,
            ok = false,
        )
        bluetooth -> SignalPath.Step(d.label, if (hiResFile) "Lossy · resampled by Android" else "Lossy", bluetoothDetail(d.type) + spatialNote, ok = false)
        bitPerfect -> SignalPath.Step(d.label, source, "Bit-perfect: the DAC gets the file's own samples.")
        d.kind == OutputKind.USB -> SignalPath.Step(
            d.label,
            if (dacResamples) "Mixer · resampled" else if (mixer > 0) "Mixer · ${rate(mixer)}" else "Mixer",
            buildString {
                if (dacResamples) append("This DAC takes ${d.rates.joinToString(" or ") { rate(it) }}, so ${rate(o.sampleRate)} is resampled on the way. ")
                else if (resampled) append("Android mixes this output at ${rate(mixer)}, so ${rate(o.sampleRate)} is resampled on the way. ")
                append(if (s.pure) o.bitPerfect.label + "." else "Through Android's mixer. Turn on Pure for bit-perfect (Android 14 and later).")
            },
            ok = !resampled && !dacResamples,
        )
        d.kind == OutputKind.OTHER -> SignalPath.Step(
            d.label,
            "Mixer",
            "Android's mixer sends it to this output; what happens there isn't shown to apps.",
        )
        else -> SignalPath.Step(
            d.label,
            if (mixer > 0) "Mixer · ${rate(mixer)}" else "Mixer",
            if (resampled) "Android plays this output at ${rate(mixer)}, so ${rate(o.sampleRate)} is resampled on the way out." else "Android's mixer plays it at the file's own rate.",
            ok = !resampled,
        )
    }

    val output = if (spatial && !bluetooth) routed.copy(detail = routed.detail + spatialNote, ok = false) else routed

    val lossless = if (processed) "Lossless source, processed" else "Lossless"
    val verdict = when {
        bluetooth -> if (spatial) "Lossy over Bluetooth, spatialized by Android" else "Lossy over Bluetooth"
        bitPerfect -> "Bit-perfect"
        spatial -> "Lossless source, spatialized by Android"
        folded -> "Cut to 16-bit"
        d.kind == OutputKind.OTHER -> "Through Android's mixer"
        resampled -> "$lossless, resampled to ${rate(mixer)}"
        dacResamples -> "${if (processed) "Lossless source, processed" else "Lossless source"}, resampled for the DAC"
        d.kind == OutputKind.USB -> "$lossless, through Android's mixer"
        else -> lossless
    }
    val short = "${fileBits.takeIf { it > 0 }?.let { "$it/" } ?: ""}${rate(o.sampleRate, withUnit = false)} → ${d.kind.label}"
    val note = when {
        bitPerfect -> null
        hiResFile && d.kind != OutputKind.USB -> {
            // A 24/48 file already plays at its own rate on a 48 kHz phone: what a DAC adds is the depth.
            val what = if (fileBits > 16 && !resampled && o.sampleRate <= 48_000) "$fileBits-bit" else rate(o.sampleRate)
            "For the full $what, play through a USB DAC with Pure on, on a phone that offers bit-perfect (Android 14 and later)."
        }
        else -> null
    }
    return SignalPath(track, runout, output, verdict, short, note, bitPerfect = bitPerfect, lossy = bluetooth)
}

/** What each kind of Bluetooth link does to the music. Android doesn't tell apps the codec in use. */
private fun bluetoothDetail(type: Int): String = when (type) {
    OutputDevices.BLE_HEADSET, OutputDevices.BLE_SPEAKER, OutputDevices.BLE_BROADCAST -> "LE Audio re-encodes the music with LC3, a lossy codec."
    OutputDevices.HEARING_AID -> "Hearing aids stream at 16 kHz with a lossy codec."
    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Call audio: mono, 16 kHz at best."
    else -> "Bluetooth re-encodes the music with a lossy codec (SBC, AAC, aptX or LDAC, agreed by the phone and headphones; " +
        "Android doesn't tell apps which). Even LDAC is lossy, at 24-bit/96 kHz at most."
}

private fun rate(hz: Int, withUnit: Boolean) = AudioQuality.formatRate(hz, withUnit)

/** The home screen's small readout: "24/96 → Phone speaker", tap for the whole chain. */
@Composable
internal fun SignalChip(path: SignalPath, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val good = path.track.ok && path.runout.ok && path.output.ok
    Row(
        modifier
            .testTag("signal-chip")
            .semantics(mergeDescendants = true) { contentDescription = "Audio quality: ${path.verdict}" }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            // Shorter than a full 48 dp key so it fits between Crackle and the deck.
            .heightIn(min = 32.dp)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).background(if (good) Room.Online else Room.Signal))
        BasicText(path.short, style = Room.LabelQuiet.copy(fontSize = 10.sp, color = Room.Ink), maxLines = 1)
    }
}


/**
 * The whole chain, as on the TE boards: the verdict on a grey strip with its lamp (green
 * bit-perfect, amber lossless but changed, red lossy), then Track → Runout → Output as stations on
 * a black rail, then the tip in blue. [gap] is the space between those three parts (11 dp in
 * Settings › Sound, 16 dp in the Audio quality panel). [onVerdictHelp] adds the "?" to the verdict.
 */
@Composable
internal fun SignalChain(path: SignalPath?, modifier: Modifier = Modifier, gap: Dp = 11.dp, onVerdictHelp: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().testTag("signal-path"), verticalArrangement = Arrangement.spacedBy(gap)) {
        if (path == null) {
            Verdict("nothing playing", Room.Faint, onVerdictHelp)
            BasicText("lower the needle on a record to see how it reaches your ears.", style = Room.LabelQuiet.copy(lineHeight = 15.sp))
            return@Column
        }
        val lamp = when {
            path.bitPerfect -> Room.Online
            path.lossy -> Room.Danger
            else -> Room.Warning
        }
        Verdict(path.verdict.lowercase(), lamp, onVerdictHelp)
        Column(Modifier.fillMaxWidth().padding(top = 11.dp)) {
            StepRow("Track", path.track, first = true)
            StepRow("Sonic", path.runout)
            StepRow("Output", path.output, last = true)
        }
        path.note?.let { BasicText(it.lowercase(), style = Room.Label.copy(color = Room.Accent, lineHeight = 15.sp)) }
    }
}

/** The verdict strip: #F5F5F5, 11 dp in, a 10 dp lamp, the 12/400 verdict (17 line height) and the TE Help "?". */
@Composable
private fun Verdict(text: String, lamp: Color, onHelp: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().background(Room.Surface).padding(11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(Modifier.size(10.dp).background(lamp))
        BasicText(text, style = Room.Text.copy(lineHeight = 17.sp), modifier = Modifier.weight(1f).testTag("signal-verdict"))
        if (onHelp != null) HelpSquare("signal verdict", onHelp)
    }
}

/**
 * One station: a 10 dp square node (black, amber where quality changes) on the line of the 12/700
 * name, the name 11 dp after it and the format at the right. Only where quality changes does a grey
 * line under it say why; a station that keeps the music as it is says nothing more. A 1 dp rail runs
 * through the nodes from the first station to the last.
 */
@Composable
private fun StepRow(kicker: String, step: SignalPath.Step, first: Boolean = false, last: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 11.dp)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Box(Modifier.width(10.dp).fillMaxHeight().rail(top = !first, bottom = !last), contentAlignment = Alignment.Center) {
                Box(Modifier.size(10.dp).background(if (step.ok) Room.Ink else Room.Warning))
            }
            BasicText(step.title.lowercase(), style = Room.Strong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            BasicText(step.spec.lowercase(), style = Room.Label, maxLines = 1, modifier = Modifier.testTag("spec:$kicker"))
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Box(Modifier.width(10.dp).fillMaxHeight().rail(top = !last, bottom = !last))
            if (step.ok) Spacer(Modifier.weight(1f).height(if (last) 0.dp else 22.dp))
            else BasicText(
                step.detail.lowercase(),
                style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp),
                modifier = Modifier.weight(1f).padding(top = 6.dp, bottom = if (last) 0.dp else 22.dp).testTag("why:$kicker"),
            )
        }
    }
}

/** The 1 dp ink rail down the middle of a node's column: its top half, its bottom half, or both. */
private fun Modifier.rail(top: Boolean, bottom: Boolean) = drawBehind {
    val w = 1.dp.toPx()
    val x = size.width / 2f - w / 2f
    if (top) drawRect(Room.Ink, Offset(x, 0f), Size(w, size.height / 2f))
    if (bottom) drawRect(Room.Ink, Offset(x, size.height / 2f), Size(w, size.height / 2f))
}

/**
 * The Audio quality panel's body, under its head: the chain, the two switches that shape it, and
 * a black key across the width to Settings › Sound. 16 dp between parts.
 */
@Composable
internal fun SignalPanel(state: RoomState, actions: RoomActions, onSoundSettings: () -> Unit) {
    val s = state.sound
    Column(Modifier.fillMaxWidth().testTag("panel:signal"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SignalChain(signalPath(state.output, s, state.bluetoothCodec.takeIf { state.bluetoothCodecOn }), gap = 16.dp)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.output.device.kind == OutputKind.BLUETOOTH || state.bluetoothCodecOn) BluetoothCodecToggle(state, actions)
            ToggleRow("Pure", "Nothing is done to the sound.", s.pure, { on -> actions.updateSound { it.copy(pure = on) } })
            ToggleRow(
                "Hi-res output",
                if (state.output.device.kind == OutputKind.BLUETOOTH) "Keeps 24-bit inside the phone; Bluetooth sends its own format."
                else "Keeps 24-bit detail (32-bit float to Android).",
                s.hiResOutput, { on -> actions.updateSound { it.copy(hiResOutput = on) } },
            )
        }
        TeButton("open sound settings", onSoundSettings, Modifier.fillMaxWidth().testTag("signal-sound-settings"))
    }
}

/**
 * The opt-in for naming the Bluetooth codec: off, nothing is asked for; on, Android asks for nearby
 * devices and, once per pair of headphones, links them to Sonic. Its line says what went wrong.
 */
@Composable
internal fun BluetoothCodecToggle(state: RoomState, actions: RoomActions) {
    ToggleRow(
        "Bluetooth codec",
        state.bluetoothCodecProblem ?: state.bluetoothCodec?.label?.let { "Android says: $it." }
            ?: "Names the codec your headphones use. Asks for nearby devices.",
        state.bluetoothCodecOn,
        actions::setBluetoothCodec,
    )
}

/** A small on/off switch with its name, for the home screen's corner. */
@Composable
internal fun MiniToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    val tick = rememberTick()
    Row(
        Modifier
            .testTag(tag)
            .semantics(mergeDescendants = true) { contentDescription = "$label ${if (checked) "on" else "off"}" }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Switch) { tick(); onChange(!checked) }
            .touchHeight()
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(label.lowercase(), style = Room.Label.copy(color = if (checked) Room.Ink else Room.Quiet))
        Box(
            Modifier
                .size(26.dp, 15.dp)
                .background(if (checked) Room.Ink else Room.Background, RectangleShape)
                .border(1.dp, if (checked) Room.Ink else Room.Faint, RectangleShape)
                .padding(2.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(9.dp).background(if (checked) Room.Background else Room.Faint, RectangleShape))
        }
    }
}

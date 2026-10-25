package app.runout.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.media.MediaRouter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class BitPerfect(val label: String) {
    OFF("Off"),
    NO_USB("Plug in a USB DAC for bit-perfect"),
    NOT_ROUTED("The USB DAC isn't the output media plays to"),
    UNSUPPORTED("This phone or DAC doesn't offer bit-perfect"),
    NO_MATCH("The DAC has no bit-perfect mode for this exact format"),
    NOT_EXACT("Not bit-perfect: the samples are changed before the DAC"),
    ON("Bit-perfect to USB DAC"),
}

/** The kind of thing the sound comes out of. */
enum class OutputKind(val label: String) {
    SPEAKER("Phone speaker"),
    WIRED("Wired headphones"),
    USB("USB DAC"),
    BLUETOOTH("Bluetooth"),
    OTHER("Audio output"),
}

/**
 * Where the sound goes, and the rate Android's mixer runs at for it (0 when unknown: only the
 * phone's own outputs report it). [type] is the AudioDeviceInfo type, [id] the device's id.
 */
data class OutputDevice(
    val kind: OutputKind = OutputKind.SPEAKER,
    val name: String? = null,
    val mixerRate: Int = 0,
    val type: Int = 0,
    val id: Int = 0,
    /** Android's Spatial Audio would reshape music played here into 3D sound (Android 12L+). */
    val spatial: Boolean = false,
    /** The sample rates the device itself takes, as it reports them; empty when it takes any or doesn't say. */
    val rates: List<Int> = emptyList(),
) {
    val label: String get() = if (name != null) "${kind.label} · $name" else kind.label
}

/** What's actually reaching the speakers, shown on the home screen and in the Sound panel. */
data class OutputStatus(
    /** The file's own bit depth, from the FLAC stream itself; 0 when unknown. */
    val sourceBits: Int = 0,
    val sampleRate: Int = 0,
    /** Android gets 32-bit float; otherwise 16-bit. */
    val floatPath: Boolean = false,
    /** Runout's chain changes the samples. */
    val processing: Boolean = false,
    val bitPerfect: BitPerfect = BitPerfect.OFF,
    val device: OutputDevice = OutputDevice(),
    /** The depth the decoder delivered (32 for float); 0 when unknown. Below [sourceBits], the decoder cut it. */
    val decodedBits: Int = 0,
    /** The 16-bit output carries dither, because the music was deeper than 16-bit or was changed. */
    val dithered: Boolean = false,
    /** ReplayGain in effect, in dB. */
    val replayGainDb: Float = 0f,
    /** The limiter is in the chain: it only switches in when the level could pass full scale. */
    val limiting: Boolean = false,
    /** Crackle is on and mixed in alongside the music. */
    val crackle: Boolean = false,
) {
    val label: String
        get() = if (sampleRate == 0) "Nothing playing" else buildString {
            append(if (sourceBits > 0) "$sourceBits-bit" else "PCM")
            append(" · ")
            append(app.runout.model.AudioQuality.formatRate(sampleRate))
            append(if (floatPath) " → 32-bit float" else if (dithered) " → 16-bit, dithered" else " → 16-bit")
            if (!processing && !dithered) append(" · untouched")
        }
}

/**
 * One stream as the sink set it up. [fileBits] is the file's depth (0 when unknown),
 * [decodedBits] what the decoder handed over (32 for float), [trackEncoding] what Android's
 * AudioTrack gets, and [deep] whether the samples carry more than 16 bits.
 */
data class StreamFormat(
    val fileBits: Int,
    val decodedBits: Int,
    val sampleRate: Int,
    val channels: Int,
    val trackEncoding: Int,
    val deep: Boolean,
)

/**
 * The app's sound settings and the live state shared between the UI and the player.
 * The audio thread reads [settings] and [version] to rebuild its [DspChain] when anything changes.
 */
class SoundEngine(private val context: Context?) {

    private val prefs = context?.getSharedPreferences("sound", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<SoundSettings> = _settings.asStateFlow()

    private val _status = MutableStateFlow(OutputStatus())
    val status: StateFlow<OutputStatus> = _status.asStateFlow()

    private val _version = AtomicInteger()
    val version: Int get() = _version.get()

    /** Output and mixer calls go to Android's audio service; they run here, off the UI and audio threads. */
    private val io: Executor by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "runout-output").apply { isDaemon = true } } }

    @Volatile private var stream: StreamFormat? = null
    @Volatile private var streamOwner: Any? = null

    init {
        context?.let { ctx ->
            watchRoutes(ctx)
            val device = OutputDevices.current(ctx)
            _status.update { it.copy(device = device) }
        }
    }

    /** Re-reads where media is playing to, and re-checks bit-perfect for the stream on it. */
    fun refreshDevice() {
        val ctx = context ?: return
        io.execute {
            val device = OutputDevices.current(ctx)
            _status.update { it.copy(device = device) }
            if (stream != null) applyPure()
        }
    }

    // Plugging in a DAC or headphones, picking another output, a call: the path changes mid-song.
    private fun watchRoutes(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refreshDevice()
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refreshDevice()
            }, Handler(Looper.getMainLooper()))
        }
        if (Build.VERSION.SDK_INT >= 31) runCatching { am.addOnModeChangedListener(io) { refreshDevice() } }
        // Spatial Audio switched on or off for the headphones in Android's settings.
        if (Build.VERSION.SDK_INT >= 32) runCatching {
            am.spatializer.addOnSpatializerStateChangedListener(io, object : android.media.Spatializer.OnSpatializerStateChangedListener {
                override fun onSpatializerEnabledChanged(spat: android.media.Spatializer, enabled: Boolean) = refreshDevice()
                override fun onSpatializerAvailableChanged(spat: android.media.Spatializer, available: Boolean) = refreshDevice()
            })
        }
        // Before Android 13 the media route is only told through MediaRouter.
        if (Build.VERSION.SDK_INT < 33) runCatching {
            ctx.getSystemService(MediaRouter::class.java)?.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, object : MediaRouter.SimpleCallback() {
                override fun onRouteSelected(router: MediaRouter?, type: Int, info: MediaRouter.RouteInfo?) = refreshDevice()
                override fun onRouteUnselected(router: MediaRouter?, type: Int, info: MediaRouter.RouteInfo?) = refreshDevice()
                override fun onRouteChanged(router: MediaRouter?, info: MediaRouter.RouteInfo?) = refreshDevice()
            })
        }
    }

    @Volatile private var albumGainDb: Float? = null
    @Volatile private var trackGainDb: Float? = null
    /** The stream playing now carried its own tags, so they, not the media item, set ReplayGain. */
    @Volatile private var streamTagged = false

    /** Frames that went through the processing chain. Lets tests prove the audio path is live. */
    val framesProcessed = AtomicLong()

    fun update(transform: (SoundSettings) -> SoundSettings) {
        val prev = _settings.value
        val next = transform(prev)
        if (next == prev) return
        _settings.value = next
        prefs?.edit()?.putString(KEY, next.toJson().toString())?.apply()
        _version.incrementAndGet()
        refreshProcessing()
        if (next.pure != prev.pure && stream != null && context != null) io.execute { applyPure() }
    }

    /** Sets the ReplayGain values directly. */
    fun setGains(albumDb: Float?, trackDb: Float?) {
        // Also guard cached/imported metadata, which does not necessarily pass through TagReader.
        val album = albumDb?.takeIf { it.isFinite() }?.coerceIn(-24f, 12f)
        val track = trackDb?.takeIf { it.isFinite() }?.coerceIn(-24f, 12f)
        if (album == albumGainDb && track == trackGainDb) return
        albumGainDb = album
        trackGainDb = track
        _version.incrementAndGet()
        refreshProcessing()
    }

    /**
     * The sink starts a stream: its own tags set ReplayGain, at the exact sample the stream starts.
     * [tagged] is false for a stream without Vorbis comments; then the media item's values apply.
     */
    fun onStreamGains(albumDb: Float?, trackDb: Float?, tagged: Boolean) {
        streamTagged = tagged
        if (tagged) setGains(albumDb, trackDb)
    }

    /** A new media item is playing; its ReplayGain only counts when the stream itself had no tags. */
    fun setItemGains(albumDb: Float?, trackDb: Float?) {
        if (!streamTagged) setGains(albumDb, trackDb)
    }

    fun replayGainDb(s: SoundSettings = _settings.value): Float = when (s.replayGain) {
        ReplayGainMode.OFF -> 0f
        ReplayGainMode.ALBUM -> albumGainDb ?: trackGainDb ?: 0f
        ReplayGainMode.TRACK -> trackGainDb ?: albumGainDb ?: 0f
    }

    fun buildChain(sampleRate: Int, channels: Int): DspChain {
        val s = _settings.value
        val bands = s.activeBands()
        val gain = if (s.pure) 0f else replayGainDb(s) + s.preampDb(sampleRate)
        // The limiter only switches in when the level can pass full scale: a lift in gain, or
        // filters, whose ringing can overshoot. A plain or lowered level is left alone.
        val limit = s.limiter && !s.pure && (gain > 0.001f || bands.isNotEmpty())
        return DspChain(sampleRate, channels, bands, gain, limiterOn = limit, bypass = s.pure)
    }

    /** Crackle is on or off; it's mixed in beside the music, so the signal lists it. */
    fun setCrackle(on: Boolean) {
        _status.update { it.copy(crackle = on) }
    }

    /** The player has started a stream in this format. Runs on the audio thread. */
    fun onStreamConfigured(owner: Any, format: StreamFormat) {
        stream = format
        streamOwner = owner
        _status.update {
            it.copy(
                sourceBits = format.fileBits, sampleRate = format.sampleRate, decodedBits = format.decodedBits,
                floatPath = format.trackEncoding == C.ENCODING_PCM_FLOAT,
            )
        }
        refreshProcessing()
        // Before the AudioTrack opens, so a bit-perfect stream starts that way. Cached: a gapless
        // join in the same format makes no calls to Android's audio service.
        applyPure()
        // A USB output may reopen at the stream's rate once the track starts: read its rate after.
        if (context != null && _status.value.device.kind == OutputKind.USB) {
            Handler(Looper.getMainLooper()).postDelayed({ refreshDevice() }, 1_000)
        }
    }

    /** The output that started a stream has stopped; the readout goes back to "nothing playing". */
    fun onStreamReset(owner: Any) {
        if (streamOwner !== owner) return
        stream = null
        streamOwner = null
        _status.update { OutputStatus(device = it.device, crackle = it.crackle) }
    }

    /** The player is going away: no mixer preference is left behind for the next session. */
    fun releaseOutput() {
        val ctx = context ?: return
        io.execute {
            synchronized(pureLock) {
                runCatching { PureOutput.apply(ctx, false, OutputDevice(), 0, 0, C.ENCODING_PCM_16BIT, exact = false) }
                pureKey = null
                integerOutput = null
            }
        }
    }

    private fun refreshProcessing() {
        val s = _settings.value
        val st = stream
        val chain = buildChain(st?.sampleRate ?: 48_000, st?.channels ?: 2)
        val processing = !s.pure && !chain.isIdentity
        val dithered = st != null && st.trackEncoding != C.ENCODING_PCM_FLOAT && (processing || st.deep)
        _status.update {
            it.copy(processing = processing, dithered = dithered, limiting = chain.limiterOn, replayGainDb = if (s.pure) 0f else replayGainDb(s))
        }
    }

    private data class PureKey(val pure: Boolean, val rate: Int, val channels: Int, val encoding: Int, val exact: Boolean, val device: OutputDevice)
    private val pureLock = Any()
    private var pureKey: PureKey? = null
    private var pureResult = BitPerfect.OFF

    /** A bit-perfect stream the track must carry as integers: its rate, channel mask and encoding. */
    private data class IntegerOutput(val sampleRate: Int, val channelMask: Int, val encoding: Int)
    @Volatile private var integerOutput: IntegerOutput? = null

    /**
     * The integer encoding a new track at [sampleRate] / [channelMask] must use for the bit-perfect
     * mode arranged for it, or null to play the stream as it is (IntegerPcmOutputProvider asks).
     */
    fun integerOutputFor(sampleRate: Int, channelMask: Int): Int? =
        integerOutput?.takeIf { it.sampleRate == sampleRate && it.channelMask == channelMask }?.encoding

    private fun applyPure() {
        val ctx = context ?: return
        val st = stream ?: return
        val pure = _settings.value.pure
        val device = _status.value.device
        val exact = pure && samplesExact(st)
        val key = PureKey(pure, st.sampleRate, st.channels, st.trackEncoding, exact, device)
        val result = synchronized(pureLock) {
            if (key != pureKey) {
                val choice = runCatching { PureOutput.apply(ctx, pure, device, st.sampleRate, st.channels, st.trackEncoding, exact) }
                    .getOrDefault(PureOutput.Choice(BitPerfect.UNSUPPORTED))
                pureResult = choice.state
                integerOutput = choice.integerOutput?.let { IntegerOutput(st.sampleRate, PureOutput.channelMask(st.channels), it) }
                pureKey = key
            }
            pureResult
        }
        _status.update { it.copy(bitPerfect = result) }
    }

    fun applyPreset(name: String) {
        val gains = Presets.all.firstOrNull { it.first == name }?.second ?: return
        update { it.copy(graphic = gains, presetName = name, useParametric = false, eqEnabled = true) }
    }

    fun importAutoEq(text: String): Boolean {
        val r = AutoEq.parse(text) ?: return false
        update { it.copy(parametric = r.bands, parametricPreampDb = r.preampDb, useParametric = true, eqEnabled = true, presetName = "AutoEq") }
        return true
    }

    private fun load(): SoundSettings = runCatching {
        prefs?.getString(KEY, null)?.let { SoundSettings.fromJson(JSONObject(it)) }
    }.getOrNull() ?: SoundSettings()

    companion object {
        private const val KEY = "settings"

        /**
         * Whether the samples handed to Android are the file's own: 16-bit files as 16-bit, or
         * files up to 24-bit as float, which holds them exactly; never after the decoder cut them.
         */
        fun samplesExact(st: StreamFormat): Boolean = when (st.trackEncoding) {
            C.ENCODING_PCM_16BIT -> st.fileBits == 16 && st.decodedBits >= 16
            C.ENCODING_PCM_FLOAT -> st.fileBits in 1..24 && st.decodedBits >= st.fileBits
            else -> false
        }
    }
}

/** Finds where media is playing to right now. */
object OutputDevices {
    private val media = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    fun current(context: Context): OutputDevice = runCatching {
        val am = context.getSystemService(AudioManager::class.java) ?: return OutputDevice()
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        var routeName: String? = null
        val device = if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+ says which devices media goes to. More than one is a shared path (LE
            // audio sharing, say): a lossy link among them decides.
            val routed = runCatching { am.getAudioDevicesForAttributes(media) }.getOrNull().orEmpty()
            routed.firstOrNull { kindOf(it.type) == OutputKind.BLUETOOTH } ?: routed.firstOrNull()
                ?: byRouteOrder(outputs, withBluetooth = true)
        } else {
            // Before that, MediaRouter knows whether media is on Bluetooth; Android routes to the
            // device connected last, so a list order alone can't tell.
            val route = runCatching {
                context.getSystemService(MediaRouter::class.java)?.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
            }.getOrNull()
            when {
                route == null -> byRouteOrder(outputs, withBluetooth = true)
                route.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH -> {
                    routeName = route.name?.toString()
                    outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                        ?: outputs.firstOrNull { kindOf(it.type) == OutputKind.BLUETOOTH }
                }
                else -> byRouteOrder(outputs, withBluetooth = false)
            }
        }
        val kind = device?.let { kindOf(it.type) } ?: if (routeName != null) OutputKind.BLUETOOTH else OutputKind.SPEAKER
        val name = (device?.productName?.toString() ?: routeName)?.trim()
            ?.takeIf { it.isNotEmpty() && kind != OutputKind.SPEAKER && !it.equals(Build.MODEL, ignoreCase = true) }
        // The reported rate belongs to the phone's own (primary) output. A USB DAC may share that
        // mixer or get its own: the rate of the output music goes to tells which. Bluetooth and
        // HDMI paths run at their own rate, which Android doesn't tell apps.
        val rate = when (kind) {
            OutputKind.SPEAKER, OutputKind.WIRED -> am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
            OutputKind.USB -> runCatching { AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) }.getOrDefault(0).coerceAtLeast(0)
            else -> 0
        }
        OutputDevice(kind, name, rate, device?.type ?: if (routeName != null) AudioDeviceInfo.TYPE_BLUETOOTH_A2DP else 0, device?.id ?: 0, spatial(am),
            device?.sampleRates?.filter { it > 0 }?.sorted().orEmpty())
    }.getOrDefault(OutputDevice())

    // Whether Android would spatialize stereo music on the current route; Pure opts Runout out.
    private val stereo = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
        .setSampleRate(48_000)
        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
        .build()

    private fun spatial(am: AudioManager): Boolean = Build.VERSION.SDK_INT >= 32 && runCatching {
        val sp = am.spatializer
        sp.immersiveAudioLevel != android.media.Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE &&
            sp.isAvailable && sp.isEnabled && sp.canBeSpatialized(media, stereo)
    }.getOrDefault(false)

    private fun byRouteOrder(outputs: List<AudioDeviceInfo>, withBluetooth: Boolean): AudioDeviceInfo? =
        outputs.filter { val k = kindOf(it.type); k != OutputKind.OTHER && (withBluetooth || k != OutputKind.BLUETOOTH) }
            .minByOrNull { ROUTE_ORDER.indexOf(kindOf(it.type)) }

    private val ROUTE_ORDER = listOf(OutputKind.USB, OutputKind.WIRED, OutputKind.BLUETOOTH, OutputKind.SPEAKER)

    fun kindOf(type: Int): OutputKind = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputKind.SPEAKER
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG -> OutputKind.WIRED
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputKind.USB
        // Every Bluetooth link is lossy: A2DP, LE Audio (headset, speaker, broadcast), hearing
        // aids and call audio (SCO).
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, HEARING_AID,
        BLE_HEADSET, BLE_SPEAKER, BLE_BROADCAST -> OutputKind.BLUETOOTH
        else -> OutputKind.OTHER
    }

    // AudioDeviceInfo.TYPE_HEARING_AID (Android 9), TYPE_BLE_HEADSET / TYPE_BLE_SPEAKER (Android 12),
    // TYPE_BLE_BROADCAST (Android 13).
    const val HEARING_AID = 23
    const val BLE_HEADSET = 26
    const val BLE_SPEAKER = 27
    const val BLE_BROADCAST = 30
}

/** Android 14+ bit-perfect output to USB DACs, when the phone and the DAC both support it. */
object PureOutput {
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** What [apply] arranged: the state, and the integer encoding the track must use when it isn't the stream's own. */
    data class Choice(val state: BitPerfect, val integerOutput: Int? = null)

    /**
     * Asks Android to play the stream bit-perfect to the USB DAC media is routed to ([routed]).
     * Only an exact match counts: the mixer mode must have the stream's sample rate and channel mask,
     * and [exact] says the samples are the file's own. The stream's own encoding is matched first; a
     * float stream (a file up to 24-bit, which float holds exactly) may also go out as 24- or 32-bit
     * integers, the modes most USB DACs offer, and the [Choice] says so. Anything else is reported
     * as it is, and no preference is left set.
     */
    fun apply(context: Context, enable: Boolean, routed: OutputDevice, sampleRate: Int, channels: Int, encoding: Int, exact: Boolean): Choice =
        choose(context, enable, routed, sampleRate, channels, encoding, exact)

    private fun choose(context: Context, enable: Boolean, routed: OutputDevice, sampleRate: Int, channels: Int, encoding: Int, exact: Boolean): Choice {
        if (Build.VERSION.SDK_INT < 34) return Choice(if (enable) BitPerfect.UNSUPPORTED else BitPerfect.OFF)
        val am = context.getSystemService(AudioManager::class.java) ?: return Choice(BitPerfect.UNSUPPORTED)
        val usbs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { OutputDevices.kindOf(it.type) == OutputKind.USB }
        if (!enable) {
            usbs.forEach { runCatching { am.clearPreferredMixerAttributes(attrs, it) } }
            return Choice(BitPerfect.OFF)
        }
        if (usbs.isEmpty()) return Choice(BitPerfect.NO_USB)
        // The preference only acts on the device media is actually routed to.
        if (routed.kind != OutputKind.USB) return Choice(BitPerfect.NOT_ROUTED)
        val usb = usbs.firstOrNull { it.id == routed.id } ?: usbs.firstOrNull { it.type == routed.type } ?: return Choice(BitPerfect.NOT_ROUTED)
        fun clear() = runCatching { am.clearPreferredMixerAttributes(attrs, usb) }
        if (!exact) { clear(); return Choice(BitPerfect.NOT_EXACT) }
        val options = runCatching { am.getSupportedMixerAttributes(usb) }.getOrDefault(emptyList())
            .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
        if (options.isEmpty()) { clear(); return Choice(BitPerfect.UNSUPPORTED) }
        val mask = channelMask(channels)
        val match = bitPerfectEncodings(encoding).firstNotNullOfOrNull { wanted ->
            options.firstOrNull { it.format.sampleRate == sampleRate && it.format.encoding == wanted && it.format.channelMask == mask }
        }
        if (match == null) { clear(); return Choice(BitPerfect.NO_MATCH) }
        if (!runCatching { am.setPreferredMixerAttributes(attrs, usb, match) }.getOrDefault(false)) return Choice(BitPerfect.UNSUPPORTED)
        return Choice(BitPerfect.ON, match.format.encoding.takeIf { it != encoding })
    }

    /**
     * The track encodings a stream in [encoding] can go out bit-perfect in, best first: its own, and
     * for float (24-bit samples held exactly) packed 24-bit or 32-bit integers too.
     */
    fun bitPerfectEncodings(encoding: Int): List<Int> =
        if (encoding == C.ENCODING_PCM_FLOAT) listOf(C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT) else listOf(encoding)

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun channelMask(channels: Int): Int = when (channels) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        else -> androidx.media3.common.util.Util.getAudioTrackChannelConfig(channels)
    }
}

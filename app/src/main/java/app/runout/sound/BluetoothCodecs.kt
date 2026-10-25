package app.runout.sound

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Bluetooth link's codec, as Android's Bluetooth stack reports it. [quality] is LDAC's bitrate mode, when set. */
data class BluetoothCodec(val name: String, val sampleRate: Int, val bits: Int, val quality: String? = null) {
    /** "LDAC · 96 kHz · 24-bit · adaptive". */
    val label: String
        get() = listOfNotNull(
            name,
            sampleRate.takeIf { it > 0 }?.let { app.runout.model.AudioQuality.formatRate(it) },
            bits.takeIf { it > 0 }?.let { "$it-bit" },
            quality,
        ).joinToString(" · ")
}

/**
 * Which codec the phone and the headphones agreed on (SBC, AAC, aptX, LDAC…). Android keeps this
 * from ordinary apps: BluetoothA2dp.getCodecStatus is a system API, reachable by reflection for an
 * app with the "Nearby devices" permission and, from Android 14, a companion-device link with those
 * headphones (a one-time system dialog naming them). Changes arrive by broadcast after that.
 *
 * Opt-in (Settings › Sound and the Audio quality panel): nothing here runs, and no permission is
 * asked for, until the listener turns it on. Whatever goes wrong is said in [problem], once.
 */
@android.annotation.SuppressLint("MissingPermission") // checked in needsPermission() before any call
class BluetoothCodecs(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("bluetooth-codec", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ON, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _codec = MutableStateFlow<BluetoothCodec?>(null)
    val codec: StateFlow<BluetoothCodec?> = _codec.asStateFlow()
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private var a2dp: BluetoothA2dp? = null
    private var listening = false

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs.edit().putBoolean(KEY_ON, on).apply()
        if (!on) stop()
    }

    /** Android 12+ asks for "Nearby devices" before any of this. */
    fun needsPermission(): Boolean =
        Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED

    /**
     * Looks again, now that it's allowed: the headphones' codec, after the companion-device link on
     * Android 14+ when there isn't one yet ([askLink] shows Android's dialog and reports back to
     * [onLinked]). Quiet when nothing Bluetooth is connected.
     */
    fun refresh(askLink: (IntentSender) -> Unit) {
        if (!_enabled.value) return
        if (needsPermission()) { _problem.value = PERMISSION; return }
        listen()
        withA2dp { proxy ->
            val device = runCatching { proxy.connectedDevices.firstOrNull() }.getOrNull()
            if (device == null) { _codec.value = null; _problem.value = null; return@withA2dp }
            if (needsLink(device)) link(device, askLink) else read(proxy, device)
        }
    }

    /** Android's companion-device dialog came back. */
    fun onLinked(granted: Boolean) {
        if (!granted) { _problem.value = UNAVAILABLE; return }
        withA2dp { proxy -> runCatching { proxy.connectedDevices.firstOrNull() }.getOrNull()?.let { read(proxy, it) } }
    }

    private fun needsLink(device: BluetoothDevice): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: return false
        return runCatching { cdm.myAssociations.none { it.deviceMacAddress?.toString().equals(device.address, ignoreCase = true) } }.getOrDefault(true)
    }

    private fun link(device: BluetoothDevice, askLink: (IntentSender) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) return
        val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: run { _problem.value = UNAVAILABLE; return }
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(device.address).build())
            .setSingleDevice(true)
            .build()
        runCatching {
            cdm.associate(request, ContextCompat.getMainExecutor(context), object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) = askLink(intentSender)
                override fun onAssociationCreated(associationInfo: AssociationInfo) = onLinked(true)
                override fun onFailure(error: CharSequence?) { _problem.value = UNAVAILABLE }
            })
        }.onFailure { _problem.value = UNAVAILABLE }
    }

    /** The hidden getCodecStatus, by reflection; a SecurityException or a missing method says "unavailable". */
    private fun read(proxy: BluetoothA2dp, device: BluetoothDevice) {
        val status = runCatching {
            BluetoothA2dp::class.java.getMethod("getCodecStatus", BluetoothDevice::class.java).invoke(proxy, device) as? BluetoothCodecStatus
        }.getOrNull()
        val codec = status?.codecConfig?.let(::describe)
        _codec.value = codec
        _problem.value = if (codec == null) UNAVAILABLE else null
    }

    private fun withA2dp(block: (BluetoothA2dp) -> Unit) {
        a2dp?.let { block(it); return }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        runCatching {
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    (proxy as? BluetoothA2dp)?.let { a2dp = it; block(it) }
                }
                override fun onServiceDisconnected(profile: Int) { a2dp = null }
            }, BluetoothProfile.A2DP)
        }.onFailure { _problem.value = UNAVAILABLE }
    }

    /** Codec changes, and the headphones becoming the active output, as the Bluetooth stack announces them. */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_CODEC_CONFIG_CHANGED -> IntentCompat.getParcelableExtra(intent, BluetoothCodecStatus.EXTRA_CODEC_STATUS, BluetoothCodecStatus::class.java)
                    ?.codecConfig?.let(::describe)?.let { _codec.value = it; _problem.value = null }
                ACTION_ACTIVE_DEVICE_CHANGED -> withA2dp { proxy ->
                    val device = runCatching { proxy.connectedDevices.firstOrNull() }.getOrNull()
                    if (device == null) _codec.value = null else if (!needsLink(device)) read(proxy, device)
                }
            }
        }
    }

    private fun listen() {
        if (listening) return
        val filter = IntentFilter().apply { addAction(ACTION_CODEC_CONFIG_CHANGED); addAction(ACTION_ACTIVE_DEVICE_CHANGED) }
        // Sent by the Bluetooth app (another uid), and protected, so it can't be spoofed.
        runCatching { ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED) }.onSuccess { listening = true }
    }

    private fun stop() {
        if (listening) runCatching { context.unregisterReceiver(receiver) }
        listening = false
        a2dp?.let { proxy -> runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.closeProfileProxy(BluetoothProfile.A2DP, proxy) } }
        a2dp = null
        _codec.value = null
        _problem.value = null
    }

    companion object {
        private const val KEY_ON = "on"
        const val ACTION_CODEC_CONFIG_CHANGED = "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"
        const val ACTION_ACTIVE_DEVICE_CHANGED = "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED"
        const val PERMISSION = "allow nearby devices for sonic to see the codec."
        const val UNAVAILABLE = "android didn't say which codec. developer options › bluetooth audio codec shows it."

        /** What a codec config says, in words: its name, rate and depth, and LDAC's bitrate mode when chosen. */
        fun describe(c: BluetoothCodecConfig): BluetoothCodec {
            val name = (if (Build.VERSION.SDK_INT >= 34) runCatching { c.extendedCodecType?.codecName }.getOrNull() else null)
                ?.takeIf { it.isNotBlank() } ?: codecName(c.codecType)
            val quality = if (c.codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC) ldacQuality(c.codecSpecific1) else null
            return BluetoothCodec(name, sampleRateOf(c.sampleRate), bitsOf(c.bitsPerSample), quality)
        }

        fun codecName(type: Int): String = when (type) {
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC -> "SBC"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC -> "AAC"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX -> "aptX"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD -> "aptX HD"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC -> "LDAC"
            5 -> "LC3"
            6 -> "Opus"
            else -> "Bluetooth"
        }

        fun sampleRateOf(mask: Int): Int = when (mask) {
            BluetoothCodecConfig.SAMPLE_RATE_44100 -> 44_100
            BluetoothCodecConfig.SAMPLE_RATE_48000 -> 48_000
            BluetoothCodecConfig.SAMPLE_RATE_88200 -> 88_200
            BluetoothCodecConfig.SAMPLE_RATE_96000 -> 96_000
            BluetoothCodecConfig.SAMPLE_RATE_176400 -> 176_400
            BluetoothCodecConfig.SAMPLE_RATE_192000 -> 192_000
            else -> 0
        }

        fun bitsOf(mask: Int): Int = when (mask) {
            BluetoothCodecConfig.BITS_PER_SAMPLE_16 -> 16
            BluetoothCodecConfig.BITS_PER_SAMPLE_24 -> 24
            BluetoothCodecConfig.BITS_PER_SAMPLE_32 -> 32
            else -> 0
        }

        /** LDAC's mode, as Developer options sets it (1000 + index); unset (0) says nothing rather than guess. */
        fun ldacQuality(specific1: Long): String? = when (specific1) {
            1000L -> "990 kbps"
            1001L -> "660 kbps"
            1002L -> "330 kbps"
            1003L -> "adaptive"
            else -> null
        }
    }
}

package app.runout

import android.bluetooth.BluetoothCodecConfig
import android.media.AudioDeviceInfo
import app.runout.sound.BluetoothCodec
import app.runout.sound.BluetoothCodecs
import app.runout.sound.OutputDevice
import app.runout.sound.OutputKind
import app.runout.sound.OutputStatus
import app.runout.sound.SoundSettings
import app.runout.ui.signalPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BluetoothCodecTest {
    @Test fun `a codec config reads as its name, rate, depth and LDAC mode`() {
        assertEquals("LDAC", BluetoothCodecs.codecName(BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC))
        assertEquals("aptX HD", BluetoothCodecs.codecName(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD))
        assertEquals(96_000, BluetoothCodecs.sampleRateOf(BluetoothCodecConfig.SAMPLE_RATE_96000))
        assertEquals(24, BluetoothCodecs.bitsOf(BluetoothCodecConfig.BITS_PER_SAMPLE_24))
        assertEquals("adaptive", BluetoothCodecs.ldacQuality(1003))
        assertEquals("990 kbps", BluetoothCodecs.ldacQuality(1000))
        assertNull("unset says nothing", BluetoothCodecs.ldacQuality(0))
        val config = BluetoothCodecConfig.Builder()
            .setCodecType(BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC)
            .setSampleRate(BluetoothCodecConfig.SAMPLE_RATE_96000)
            .setBitsPerSample(BluetoothCodecConfig.BITS_PER_SAMPLE_24)
            .setCodecSpecific1(1003)
            .build()
        val ldac = BluetoothCodecs.describe(config)
        assertEquals(96_000, ldac.sampleRate)
        assertEquals(24, ldac.bits)
        assertTrue(ldac.label, ldac.label.endsWith("96 kHz · 24-bit · adaptive"))
    }

    @Test fun `with the readout on, the Bluetooth step names the codec and stays lossy`() {
        val xm5 = OutputDevice(OutputKind.BLUETOOTH, "WH-1000XM5", type = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        val o = OutputStatus(24, 96_000, floatPath = true, device = xm5)
        val path = signalPath(o, SoundSettings(), BluetoothCodec("LDAC", 96_000, 24, "adaptive"))!!
        assertEquals("LDAC · 96 kHz · 24-bit · adaptive", path.output.spec)
        assertTrue(path.output.detail, path.output.detail.contains("Even LDAC is lossy"))
        assertFalse(path.output.ok)
        assertTrue(path.lossy)
        // Without it, the old wording: Android doesn't tell apps which.
        assertTrue(signalPath(o, SoundSettings())!!.output.detail.contains("doesn't tell apps"))
    }
}

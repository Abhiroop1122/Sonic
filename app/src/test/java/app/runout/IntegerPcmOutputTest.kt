package app.runout

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioOutput
import app.runout.sound.IntegerPcmOutput
import app.runout.sound.PureOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class)
class IntegerPcmOutputTest {
    /** A sample as Android's FLAC decoder hands it over in float: left-justified, times 2^-31. */
    private fun decoded(sample: Int, bits: Int): Float = ((sample shl (32 - bits)).toFloat() * (1f / 2_147_483_648f))

    @Test fun `a 24-bit sample carried as float comes back as exactly the same integer`() {
        val rnd = Random(7)
        val edges = listOf(0, 1, -1, 8_388_607, -8_388_608, 4_194_304, -123_457)
        (edges + List(20_000) { rnd.nextInt(-8_388_608, 8_388_608) }).forEach { v ->
            val f = decoded(v, 24)
            assertEquals("24-bit $v", v, IntegerPcmOutput.toInt24(f))
            assertEquals("as 32-bit $v", v shl 8, IntegerPcmOutput.toInt32(f))
        }
        // 16-bit samples too, and out-of-range floats are clamped rather than wrapped.
        listOf(32_767, -32_768, 1, -1).forEach { assertEquals(it shl 8, IntegerPcmOutput.toInt24(decoded(it, 16))) }
        assertEquals(8_388_607, IntegerPcmOutput.toInt24(1.5f))
        assertEquals(-8_388_608, IntegerPcmOutput.toInt24(-1.5f))
    }

    @Test fun `float streams can go out as 24 or 32-bit integers, others only as they are`() {
        assertEquals(listOf(C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT), PureOutput.bitPerfectEncodings(C.ENCODING_PCM_FLOAT))
        assertEquals(listOf(C.ENCODING_PCM_16BIT), PureOutput.bitPerfectEncodings(C.ENCODING_PCM_16BIT))
    }

    @Test fun `a track that takes part of each write gets every sample once, and the player's buffer follows it`() {
        val received = java.io.ByteArrayOutputStream()
        var calls = 0
        // Takes at most 7 frames (21 bytes per channel pair... 7 × 2 × 3 = 42 bytes) a call.
        val track = Proxy.newProxyInstance(AudioOutput::class.java.classLoader, arrayOf(AudioOutput::class.java)) { _, method, args ->
            when (method.name) {
                "write" -> {
                    calls++
                    val b = args!![0] as ByteBuffer
                    val n = minOf(b.remaining(), 42)
                    val chunk = ByteArray(n); b.get(chunk); received.write(chunk)
                    !b.hasRemaining()
                }
                else -> null
            }
        } as AudioOutput
        val out = IntegerPcmOutput(track, C.ENCODING_PCM_24BIT, 2)
        val samples = IntArray(40) { (it - 20) * 211_111 }
        val input = ByteBuffer.allocateDirect(samples.size * 4).order(ByteOrder.nativeOrder())
        samples.forEach { input.putFloat(decoded(it, 24)) }
        input.flip()
        var done = false
        var guard = 0
        while (!done) {
            done = out.write(input, 1, 0)
            if (!done) assertTrue("advances by whole frames", input.position() % 8 == 0)
            check(++guard < 100)
        }
        assertFalse(input.hasRemaining())
        assertTrue(calls > 1)
        val bytes = received.toByteArray()
        assertEquals(samples.size * 3, bytes.size)
        samples.forEachIndexed { i, v ->
            val got = (bytes[i * 3].toInt() and 0xFF) or ((bytes[i * 3 + 1].toInt() and 0xFF) shl 8) or (bytes[i * 3 + 2].toInt() shl 16)
            assertEquals("sample $i", v, got)
        }
    }
}

package app.runout.sound

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/**
 * Android's AudioTrack output, except for a bit-perfect stream to a DAC whose bit-perfect modes are
 * integer: the player still hands over float (Media3's sink only makes 16-bit or float), and the
 * track is opened as packed 24-bit or 32-bit integers, each float sample turned back into the
 * file's own integer on the way ([IntegerPcmOutput]). For files up to 24-bit that's exact: float
 * holds 24 bits of mantissa, and the decoder only scaled them by a power of two.
 *
 * [SoundEngine.integerOutputFor] decides, from what PureOutput arranged with Android; everything
 * else goes to the plain AudioTrack output untouched.
 */
@OptIn(UnstableApi::class)
internal class IntegerPcmOutputProvider(context: Context, private val engine: SoundEngine) :
    ForwardingAudioOutputProvider(AudioTrackAudioOutputProvider.Builder(context).build()) {

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val integer = if (config.encoding == C.ENCODING_PCM_FLOAT) engine.integerOutputFor(config.sampleRate, config.channelMask) else null
        if (integer == null) return super.getAudioOutput(config)
        val bytes = IntegerPcmOutput.bytesPerSample(integer)
        val track = super.getAudioOutput(config.buildUpon().setEncoding(integer).setBufferSize(config.bufferSize / FLOAT_BYTES * bytes).build())
        return IntegerPcmOutput(track, integer, Integer.bitCount(config.channelMask).coerceAtLeast(1))
    }
}

/**
 * Takes float PCM from the player and writes it to an integer track ([encoding]: packed 24-bit or
 * 32-bit). Each buffer is converted once, into a staging buffer the track drains over as many calls
 * as it needs; the player's buffer advances by the frames the track has actually taken, so Media3's
 * frame counting, position and end-of-stream stay as they are for float.
 */
@OptIn(UnstableApi::class)
internal class IntegerPcmOutput(output: AudioOutput, private val encoding: Int, private val channels: Int) : ForwardingAudioOutput(output) {
    private val outBytes = bytesPerSample(encoding)
    private var staging: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var pending = false
    private var sourceStart = 0

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        if (!pending) {
            sourceStart = buffer.position()
            convert(buffer)
            pending = true
        }
        super.write(staging, encodedAccessUnitCount, presentationTimeUs)
        val frames = staging.position() / (outBytes * channels)
        buffer.position(sourceStart + frames * FLOAT_BYTES * channels)
        if (staging.hasRemaining()) return false
        pending = false
        return true
    }

    override fun flush() {
        pending = false
        super.flush()
    }

    override fun release() {
        pending = false
        super.release()
    }

    /** The float samples from [source]'s position to its limit, as integers in [staging]; [source] is left as it was. */
    private fun convert(source: ByteBuffer) {
        val floats = source.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val count = floats.remaining()
        if (staging.capacity() < count * outBytes) staging = ByteBuffer.allocateDirect(count * outBytes).order(ByteOrder.nativeOrder())
        staging.clear()
        for (i in 0 until count) {
            val f = floats.get()
            if (encoding == C.ENCODING_PCM_24BIT) {
                val v = toInt24(f)
                staging.put(v.toByte())
                staging.put((v shr 8).toByte())
                staging.put((v shr 16).toByte())
            } else {
                staging.putInt(toInt32(f))
            }
        }
        staging.flip()
    }

    companion object {
        fun bytesPerSample(encoding: Int): Int = if (encoding == C.ENCODING_PCM_24BIT) 3 else 4

        /** A float sample as a 24-bit integer: exact for samples that came from 24 bits or fewer. */
        fun toInt24(f: Float): Int = (f * 8_388_608.0).roundToLong().coerceIn(-8_388_608L, 8_388_607L).toInt()

        /** A float sample as a left-justified 32-bit integer: exact for samples that came from 24 bits or fewer. */
        fun toInt32(f: Float): Int = (f * 2_147_483_648.0).roundToLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }
}

private const val FLOAT_BYTES = 4

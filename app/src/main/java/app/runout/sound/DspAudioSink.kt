package app.runout.sound

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import app.runout.library.TagReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * Sits in front of Android's audio output and runs the [DspChain] on every PCM buffer in 32-bit float.
 *
 * Media3 only applies plug-in audio processors in its 16-bit path, so an EQ added the usual way
 * would switch itself off for hi-res files. Doing the work here keeps the EQ, ReplayGain and the
 * limiter active for every file. With hi-res output on, Android gets float, which holds 24-bit
 * exactly; with it off, this sink makes the 16-bit itself: a 16-bit file with nothing to do goes out
 * as its own bytes, and anything deeper or changed is rounded once, with TPDF dither.
 *
 * Pure hands the decoder's samples over untouched: a 16-bit file as its own 16-bit samples, a
 * deeper one as float.
 */
@OptIn(UnstableApi::class)
class DspAudioSink(
    private val delegate: AudioSink,
    private val engine: SoundEngine,
    private val floatOutput: Boolean,
) : ForwardingAudioSink(delegate) {

    /** PASS hands buffers over unchanged; FLOAT and INT16 run the chain and write that encoding. */
    private enum class Mode { PASS, FLOAT, INT16 }

    private var mode = Mode.PASS
    private var inEncoding = C.ENCODING_INVALID
    private var channels = 0
    private var sampleRate = 0
    private var deep = false
    private var chain: DspChain? = null
    private var chainVersion = -1

    /** The file's own bit depth, told by the renderer before its decoder starts; 0 when unknown. */
    @Volatile private var sourceBits = 0
    /** The file's lossy codec ("MP3"), or null for lossless (FLAC). A lossy file has no depth of its own. */
    @Volatile private var lossyCodec: String? = null

    private var pending: ByteBuffer? = null
    private var pendingPtsUs = 0L
    private var nextPtsUs = 0L
    private var tail: ByteBuffer? = null
    private var tailDone = false
    private var out: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    private var scratch = FloatArray(0)
    private var seed = 0x2545F4914F6CDD1DL

    /** The compressed stream about to be decoded: its bit depth is the file's. */
    fun onSourceFormat(format: Format) {
        lossyCodec = app.runout.library.LocalAudioPolicy.lossyCodecOfMime(format.sampleMimeType)
        sourceBits = if (lossyCodec != null) 0 else bitsOf(format.pcmEncoding)
    }

    override fun getFormatSupport(format: Format): Int {
        if (!canProcess(format)) return delegate.getFormatSupport(format)
        val pure = engine.settings.value.pure
        // Pure, 16-bit file: ask the decoder for its own 16-bit samples, which then go out unchanged.
        // A lossy file (MP3) is treated as 16-bit: it never takes the deep path.
        if (pure && format.pcmEncoding == C.ENCODING_PCM_FLOAT && (sourceBits in 1..16 || lossyCodec != null)) return AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
        return when (val m = modeFor(format.pcmEncoding, pure)) {
            Mode.PASS -> delegate.getFormatSupport(format)
            // Everything else is taken directly, so the decoder keeps its full precision.
            else -> if (delegate.getFormatSupport(format.withEncoding(encodingOf(m))) == AudioSink.SINK_FORMAT_UNSUPPORTED) {
                AudioSink.SINK_FORMAT_UNSUPPORTED
            } else AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        }
    }

    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        tail = null
        tailDone = false
        if (!canProcess(inputFormat)) {
            mode = Mode.PASS
            delegate.configure(inputFormat, specifiedBufferSize, outputChannels)
            return
        }
        val pure = engine.settings.value.pure
        val enc = inputFormat.pcmEncoding
        val lossy = lossyCodec
        val fileBits = if (lossy != null) 0 else sourceBits.takeIf { it > 0 } ?: bitsOf(enc).takeIf { enc != C.ENCODING_PCM_FLOAT } ?: 0
        mode = modeFor(enc, pure)
        deep = when {
            // A lossy decode is 16-bit at heart: no deep (float) path and no hi-res claims.
            lossy != null -> false
            enc == C.ENCODING_PCM_16BIT -> false
            enc == C.ENCODING_PCM_FLOAT -> fileBits == 0 || fileBits > 16
            else -> true
        }
        readReplayGain(inputFormat)
        if (mode == Mode.PASS) {
            delegate.configure(inputFormat, specifiedBufferSize, outputChannels)
        } else {
            // A new track in the same shape keeps the running chain: it's rebuilt for the new
            // track's gain but carries the old state over, so gapless joins stay gapless.
            if (inputFormat.sampleRate != sampleRate || inputFormat.channelCount != channels) chain = null
            chainVersion = -1
            inEncoding = enc
            channels = inputFormat.channelCount
            sampleRate = inputFormat.sampleRate
            val outEnc = encodingOf(mode)
            val size = if (specifiedBufferSize > 0) specifiedBufferSize / bytesPerSample(enc) * bytesPerSample(outEnc) else 0
            delegate.configure(inputFormat.withEncoding(outEnc), size, outputChannels)
        }
        val track = when {
            mode == Mode.FLOAT -> C.ENCODING_PCM_FLOAT
            mode == Mode.INT16 -> C.ENCODING_PCM_16BIT
            enc == C.ENCODING_PCM_16BIT -> C.ENCODING_PCM_16BIT
            else -> C.ENCODING_PCM_FLOAT // Pure with hi-res: Android's sink writes float, exact up to 24-bit
        }
        engine.onStreamConfigured(this, StreamFormat(fileBits, bitsOf(enc), inputFormat.sampleRate, inputFormat.channelCount, track, deep, lossyCodec = lossy))
    }

    private fun modeFor(encoding: Int, pure: Boolean): Mode = when {
        pure && encoding == C.ENCODING_PCM_16BIT -> Mode.PASS
        pure && floatOutput -> Mode.PASS
        floatOutput -> Mode.FLOAT
        // 16-bit out, made here (Pure included, with the chain bypassed): float from a 16-bit file
        // rounds back to the very same samples; anything deeper is dithered once.
        else -> Mode.INT16
    }

    // The track's own ReplayGain tags, read as its first sample arrives rather than when the
    // position catches up, which is up to a buffer later.
    private fun readReplayGain(format: Format) {
        val m = format.metadata ?: return engine.onStreamGains(null, null, tagged = false)
        val entries = (0 until m.length()).map { m[it] }
        val tags = replayGainTags(entries)
        if (tags == null) return engine.onStreamGains(null, null, tagged = false)
        engine.onStreamGains(TagReader.gain(tags["REPLAYGAIN_ALBUM_GAIN"]), TagReader.gain(tags["REPLAYGAIN_TRACK_GAIN"]), tagged = true)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (mode == Mode.PASS) return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        var p = pending
        if (p == null) {
            val frames = buffer.remaining() / bytesPerSample(inEncoding) / channels.coerceAtLeast(1)
            p = process(buffer)
            pending = p
            pendingPtsUs = presentationTimeUs
            nextPtsUs = presentationTimeUs + frames * 1_000_000L / sampleRate.coerceAtLeast(1)
        }
        val done = delegate.handleBuffer(p, pendingPtsUs, encodedAccessUnitCount)
        if (done) pending = null
        return done
    }

    /** At the end of the side, the last 1.5 ms still in the limiter's delay line plays too. */
    override fun playToEndOfStream() {
        if (mode != Mode.PASS) {
            if (!tailDone) {
                tailDone = true
                tail = drainTail()
            }
            tail?.let { t ->
                if (!delegate.handleBuffer(t, nextPtsUs, 1)) return
                tail = null
            }
        }
        super.playToEndOfStream()
    }

    override fun flush() {
        pending = null
        tail = null
        tailDone = false
        chain?.reset()
        super.flush()
    }

    override fun reset() {
        pending = null
        tail = null
        tailDone = false
        chain = null
        sampleRate = 0
        channels = 0
        engine.onStreamReset(this)
        super.reset()
    }

    override fun release() {
        engine.onStreamReset(this)
        super.release()
    }

    private fun currentChain(): DspChain {
        val v = engine.version
        val c = chain
        if (c != null && chainVersion == v) return c
        val next = engine.buildChain(sampleRate, channels)
        c?.let { next.carryFrom(it) }
        chain = next
        chainVersion = v
        return next
    }

    private fun drainTail(): ByteBuffer? {
        val c = chain ?: return null
        if (!c.limiterOn) return null
        val frames = c.lookahead
        val n = frames * channels
        if (scratch.size < n) scratch = FloatArray(n)
        scratch.fill(0f, 0, n)
        c.process(scratch, frames)
        return write(n, dither = mode == Mode.INT16)
    }

    private fun process(input: ByteBuffer): ByteBuffer {
        val inBytes = bytesPerSample(inEncoding)
        val frames = if (channels > 0) input.remaining() / inBytes / channels else 0
        val n = frames * channels
        val c = currentChain()
        val untouched = c.passesThrough()
        engine.framesProcessed.addAndGet(frames.toLong())

        // A 16-bit source with nothing to do leaves as its own bytes.
        if (mode == Mode.INT16 && inEncoding == C.ENCODING_PCM_16BIT && untouched) {
            val bytes = n * 2
            ensureOut(bytes)
            val src = input.duplicate()
            src.limit(src.position() + bytes)
            out.put(src)
            input.position(input.limit())
            out.flip()
            return out
        }

        val src = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (scratch.size < n) scratch = FloatArray(n)
        for (i in 0 until n) {
            scratch[i] = when (inEncoding) {
                C.ENCODING_PCM_16BIT -> src.short / 32768f
                C.ENCODING_PCM_24BIT -> {
                    val b0 = src.get().toInt() and 0xFF
                    val b1 = src.get().toInt() and 0xFF
                    val b2 = src.get().toInt()
                    ((b2 shl 16) or (b1 shl 8) or b0) / 8_388_608f
                }
                C.ENCODING_PCM_32BIT -> src.int / 2_147_483_648f
                else -> src.float
            }
        }
        // Mark the input as consumed.
        input.position(input.limit())
        c.process(scratch, frames)
        // Dither only when there is something to round away: a deeper source or changed samples.
        return write(n, dither = !untouched || deep)
    }

    private fun write(n: Int, dither: Boolean): ByteBuffer {
        if (mode == Mode.FLOAT) {
            ensureOut(n * 4)
            out.asFloatBuffer().put(scratch, 0, n)
            out.position(0)
            out.limit(n * 4)
            return out
        }
        ensureOut(n * 2)
        for (i in 0 until n) {
            var y = scratch[i] * 32768.0
            if (dither) y += nextUnit() - nextUnit() // TPDF: ±1 LSB, triangular
            out.putShort(floor(y + 0.5).coerceIn(-32768.0, 32767.0).toInt().toShort())
        }
        out.flip()
        return out
    }

    private fun ensureOut(bytes: Int) {
        if (out.capacity() < bytes) out = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        out.clear()
    }

    // xorshift64*: cheap, good enough for dither, in [0, 1).
    private fun nextUnit(): Double {
        var x = seed
        x = x xor (x ushr 12); x = x xor (x shl 25); x = x xor (x ushr 27)
        seed = x
        return ((x * 0x2545F4914F6CDD1DL) ushr 11) * (1.0 / (1L shl 53))
    }

    companion object {
        /**
         * The stream's own tags, upper-cased: FLAC's Vorbis comments, or MP3's ID3 TXXX frames
         * (REPLAYGAIN_TRACK_GAIN and friends). Null when the stream carries neither.
         */
        fun replayGainTags(entries: List<androidx.media3.common.Metadata.Entry>): Map<String, String>? {
            val comments = entries.filterIsInstance<VorbisComment>()
            val frames = entries.filterIsInstance<TextInformationFrame>().filter { it.id == "TXXX" && it.description?.startsWith("REPLAYGAIN_", ignoreCase = true) == true }
            if (comments.isEmpty() && frames.isEmpty()) return null
            val out = mutableMapOf<String, String>()
            comments.forEach { out.putIfAbsent(it.key.uppercase(), it.value) }
            frames.forEach { f -> f.values.firstOrNull()?.let { out.putIfAbsent(f.description!!.uppercase(), it) } }
            return out
        }

        fun canProcess(f: Format): Boolean =
            MimeTypes.AUDIO_RAW == f.sampleMimeType && f.pcmEncoding in setOf(
                C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT,
            )

        fun asFloat(f: Format): Format = f.withEncoding(C.ENCODING_PCM_FLOAT)

        private fun Format.withEncoding(encoding: Int): Format = buildUpon().setPcmEncoding(encoding).build()

        private fun encodingOf(m: Mode) = if (m == Mode.INT16) C.ENCODING_PCM_16BIT else C.ENCODING_PCM_FLOAT

        fun bytesPerSample(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            else -> 4
        }

        /** Bits per sample of a PCM encoding: 32 for float. */
        fun bitsOf(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
            else -> 0
        }
    }
}

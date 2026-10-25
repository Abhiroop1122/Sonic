package app.runout.sound

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/** Builds the player with the sound engine wired into its audio output. */
object PlayerFactory {

    @OptIn(UnstableApi::class)
    fun create(
        context: Context,
        engine: SoundEngine,
        handleAudioFocus: Boolean = true,
        clock: Clock? = null,
        /** Replaces Android's audio output, for tests. Receives whether hi-res output is on. */
        outputSink: ((Context, Boolean) -> AudioSink)? = null,
    ): ExoPlayer {
        // Float carries 24-bit exactly, in Pure too: with nothing processing, the samples arrive as
        // they left the decoder. With hi-res off, the sink makes the 16-bit itself.
        val floatOut = engine.settings.value.hiResOutput
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean,
            ): AudioSink {
                val base = outputSink?.invoke(context, floatOut) ?: DefaultAudioSink.Builder(context)
                    // Android's AudioTrack, or for a bit-perfect stream to an integer-only DAC, a
                    // 24- or 32-bit integer track fed the file's own samples.
                    .setAudioOutputProvider(IntegerPcmOutputProvider(context, engine))
                    .setEnableFloatOutput(floatOut)
                    // Speed (the platter-hold wind-down) via Android's AudioTrack, which also works in the float path.
                    .setEnableAudioOutputPlaybackParameters(true)
                    .build()
                return DspAudioSink(base, engine, floatOutput = floatOut)
            }

            // The FLAC decoder's renderer, telling the sink each file's own bit depth before the
            // decoder starts: Pure then asks for 16-bit samples from 16-bit files, float otherwise.
            override fun buildAudioRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: Handler,
                eventListener: AudioRendererEventListener,
                out: ArrayList<Renderer>,
            ) {
                out.add(object : MediaCodecAudioRenderer(
                    context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink,
                ) {
                    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
                        formatHolder.format?.let { (audioSink as? DspAudioSink)?.onSourceFormat(it) }
                        return super.onInputFormatChanged(formatHolder)
                    }
                })
            }
        }
        return ExoPlayer.Builder(context, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    // Pure means nothing is done to the sound: Android's Spatial Audio stays out too.
                    .setSpatializationBehavior(
                        if (engine.settings.value.pure) C.SPATIALIZATION_BEHAVIOR_NEVER else C.SPATIALIZATION_BEHAVIOR_AUTO,
                    )
                    .build(),
                handleAudioFocus,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .apply { if (clock != null) setClock(clock) }
            .build()
    }
}

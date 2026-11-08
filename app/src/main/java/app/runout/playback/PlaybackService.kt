package app.runout.playback

import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.runout.RunoutApp
import app.runout.library.LocalAudioPolicy
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import app.runout.sound.PlayerFactory
import app.runout.sound.SoundEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Hosts the player so a record keeps spinning with the screen off, with lock-screen and
 * notification controls. The turntable UI talks to it through a MediaController (see [Deck]).
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var engine: SoundEngine
    private lateinit var player: ExoPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val gainListener = object : Player.Listener {
        // The stream's own tags set ReplayGain at its first sample (see DspAudioSink); the item's
        // values only stand in for a stream without them.
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            engine.setItemGains(albumGainOf(mediaItem), trackGainOf(mediaItem))
        }

        // A cheap moment to re-read the output: the system output switcher changes the route
        // without connecting or disconnecting anything.
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) engine.refreshDevice()
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        engine = (application as RunoutApp).graph.sound
        player = PlayerFactory.create(this, engine)
        player.addListener(gainListener)
        session = MediaSession.Builder(this, player).setCallback(object : MediaSession.Callback {
            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: List<MediaItem>,
            ): ListenableFuture<List<MediaItem>> = validatedItems(mediaItems)

            override fun onSetMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: List<MediaItem>,
                startIndex: Int,
                startPositionMs: Long,
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
                if (isAllowed(mediaItems)) Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs))
                else Futures.immediateFailedFuture(IllegalArgumentException("Only local verified music can be played"))
        }).build()

        // Switching hi-res output on or off needs a new audio output; carry the record across.
        scope.launch {
            engine.settings.map { it.hiResOutput to it.pure }.distinctUntilChanged().drop(1).collect { rebuildPlayer() }
        }
    }

    private val policy: LocalAudioPolicy get() = (application as RunoutApp).graph.library.localPolicy

    private fun isAllowed(items: List<MediaItem>): Boolean = items.all { item ->
        // MP3 only with Settings › Flags › MP3 in your folder on; Deck sends MP3 for folder records alone.
        item.localConfiguration?.uri?.let { policy.read(this, it) != null } == true
    }

    private fun validatedItems(items: List<MediaItem>): ListenableFuture<List<MediaItem>> =
        if (isAllowed(items)) Futures.immediateFuture(items)
        else Futures.immediateFailedFuture(IllegalArgumentException("Only local verified music can be played"))

    private fun rebuildPlayer() {
        val old = player
        val items = (0 until old.mediaItemCount).map { old.getMediaItemAt(it) }
        val next = PlayerFactory.create(this, engine)
        next.addListener(gainListener)
        if (items.isNotEmpty()) {
            next.setMediaItems(items, old.currentMediaItemIndex.coerceAtLeast(0), old.currentPosition)
            // Always the recorded speed: a wind-down in progress doesn't carry over.
            next.playbackParameters = androidx.media3.common.PlaybackParameters.DEFAULT
            next.volume = if (engine.settings.value.pure) 1f else old.volume
            next.prepare()
            next.playWhenReady = old.playWhenReady
        }
        session?.player = next
        old.removeListener(gainListener)
        old.release()
        player = next
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        engine.releaseOutput()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ALBUM_GAIN = "rg_album"
        const val EXTRA_TRACK_GAIN = "rg_track"

        fun albumGainOf(item: MediaItem?): Float? =
            item?.mediaMetadata?.extras?.takeIf { it.containsKey(EXTRA_ALBUM_GAIN) }?.getFloat(EXTRA_ALBUM_GAIN)

        fun trackGainOf(item: MediaItem?): Float? =
            item?.mediaMetadata?.extras?.takeIf { it.containsKey(EXTRA_TRACK_GAIN) }?.getFloat(EXTRA_TRACK_GAIN)
    }
}

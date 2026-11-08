package app.runout.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.runout.model.Album
import app.runout.library.LocalAudioPolicy
import app.runout.model.Side
import app.runout.model.SideSplitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class DeckState(
    val album: Album? = null,
    val sides: List<Side> = emptyList(),
    val sideIndex: Int = 0,
    val needleDown: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val sideFinished: Boolean = false,
    val held: Boolean = false,
) {
    val side: Side? get() = sides.getOrNull(sideIndex)

    /** Which track on this side the needle is in. */
    val trackIndex: Int
        get() {
            val s = side ?: return 0
            var acc = 0L
            s.tracks.forEachIndexed { i, t ->
                if (positionMs < acc + t.durationMs) return i
                acc += t.durationMs
            }
            return s.tracks.lastIndex.coerceAtLeast(0)
        }

    val currentTrack: app.runout.model.Track? get() = side?.tracks?.getOrNull(trackIndex)

    /** Where the needle is inside the current track. */
    val trackPositionMs: Long get() = side?.let { positionMs - it.positionOf(trackIndex, 0) }?.coerceAtLeast(0) ?: 0

    /** The track's place on the record, as printed on vinyl labels: A1, A2 … B1. */
    val trackCode: String get() = side?.let { "${it.letter}${trackIndex + 1}" } ?: ""

    /** How far the needle is through the current side, 0..1. Drives the tonearm. */
    val fraction: Float
        get() {
            val d = side?.durationMs ?: return 0f
            return if (d <= 0) 0f else (positionMs.toFloat() / d).coerceIn(0f, 1f)
        }

    val currentTrackTitle: String? get() = currentTrack?.title
}

/**
 * Settings › Flags: the deck's habits. [autoFlip] turns the record over when a side runs out and plays
 * on; [autoPlay] lowers the needle as soon as a record is on the platter; [sleepMode] puts the moon
 * in the room's top bar for the sleep timer.
 */
data class DeckFlags(val autoFlip: Boolean = false, val autoPlay: Boolean = false, val sleepMode: Boolean = false)

/**
 * The turntable's mechanics. Owns the side that's on the platter and translates turntable
 * gestures (needle drop, lift, hold the platter, flip, start/stop) into player commands. Records
 * always turn at the speed they were recorded at.
 */
class Deck(
    private val context: Context,
    private val sfx: VinylSfx,
    private val scope: CoroutineScope,
    private val sound: app.runout.sound.SoundEngine? = null,
    /** Settings › Flags › MP3 in your folder: MP3 records from the music folder play too. */
    private val allowMp3: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
    /** How the deck reaches a player: the playback service by default; tests hand it one directly. */
    private val connectPlayer: (onReady: (Player?) -> Unit) -> Unit = { ready -> connectToService(context, ready) },
) {
    /** Pure mode turns off everything that alters the sound: crackle and the platter wind-down. */
    private val pure: Boolean get() = sound?.settings?.value?.pure == true
    private val _state = MutableStateFlow(DeckState())
    val state: StateFlow<DeckState> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences("deck", Context.MODE_PRIVATE)
    private val _crackleOn = MutableStateFlow(prefs.getBoolean("crackle", true))
    val crackleOn: StateFlow<Boolean> = _crackleOn.asStateFlow()

    private val _flags = MutableStateFlow(DeckFlags(prefs.getBoolean("auto_flip", false), prefs.getBoolean("auto_play", false), prefs.getBoolean("sleep_mode", false)))
    val flags: StateFlow<DeckFlags> = _flags.asStateFlow()
    private fun saveFlags(next: DeckFlags) {
        _flags.value = next
        prefs.edit().putBoolean("auto_flip", next.autoFlip).putBoolean("auto_play", next.autoPlay).putBoolean("sleep_mode", next.sleepMode).apply()
    }
    fun setAutoFlip(on: Boolean) = saveFlags(_flags.value.copy(autoFlip = on))
    fun setAutoPlay(on: Boolean) = saveFlags(_flags.value.copy(autoPlay = on))
    fun setSleepMode(on: Boolean) {
        saveFlags(_flags.value.copy(sleepMode = on))
        if (!on) setSleepTimer(null)
    }

    /** When the sleep timer stops the music, in epoch milliseconds; null when it isn't set. */
    private val _sleepAt = MutableStateFlow<Long?>(null)
    val sleepAt: StateFlow<Long?> = _sleepAt.asStateFlow()
    private var sleepJob: Job? = null

    /**
     * Stops the music in [minutes]: it fades out over a few seconds and the needle lifts, like
     * someone turning the volume down before they leave. Null turns the timer off. Only with sleep
     * mode on (Settings › Flags, off until you turn it on).
     */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null || !_flags.value.sleepMode) { _sleepAt.value = null; return }
        val at = clock() + minutes * 60_000L
        _sleepAt.value = at
        sleepJob = scope.launch {
            delay((at - clock()).coerceAtLeast(0))
            if (_state.value.needleDown) {
                rampJob?.cancel()
                for (step in 1..SLEEP_FADE_STEPS) {
                    withController { c -> c.volume = 1f - step.toFloat() / SLEEP_FADE_STEPS }
                    delay(SLEEP_FADE_MS / SLEEP_FADE_STEPS)
                }
                liftNeedle()
            }
            _sleepAt.value = null
        }
    }

    private var controller: Player? = null
    private val pending = mutableListOf<(Player) -> Unit>()
    private var rampJob: Job? = null

    /** A short note for the listener when playback fails; shown once, then [clearProblem]. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    fun clearProblem() { _problem.value = null }

    private val listener = object : Player.Listener {
        // The player is the truth: headset and car-kit buttons, the notification and a Bluetooth
        // disconnect all reach it without the deck, so the needle and the platter follow it here.
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val c = controller
            val ours = rampJob?.isActive == true
            if (isPlaying) {
                _state.update { it.copy(isPlaying = true, needleDown = true, held = false, sideFinished = false) }
                // A play from outside the deck always turns at the recorded speed.
                if (!ours && c != null && c.playbackParameters.speed != RECORDED_SPEED) {
                    c.playbackParameters = PlaybackParameters(RECORDED_SPEED, RECORDED_SPEED)
                }
            } else {
                // Paused from outside with the needle down: the platter is held, so START spins it up.
                val pausedOutside = !ours && c != null && !c.playWhenReady && c.playbackState != Player.STATE_ENDED
                _state.update { it.copy(isPlaying = false, held = it.held || (it.needleDown && pausedOutside)) }
            }
            if (isPlaying && !pure) sfx.startCrackle() else if (!isPlaying) sfx.stopCrackle()
            followPosition(isPlaying)
        }

        override fun onPlayerError(error: PlaybackException) {
            rampJob?.cancel()
            sfx.stopCrackle()
            _state.update { it.copy(needleDown = false, isPlaying = false, held = false) }
            _problem.value = describe(error)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && _state.value.needleDown) {
                sfx.stopCrackle()
                if (!pure) sfx.runout()
                _state.update { it.copy(needleDown = false, isPlaying = false, sideFinished = true, positionMs = it.side?.durationMs ?: 0) }
                autoFlip()
            }
        }
    }

    private var connecting = false

    /**
     * Starts the playback service the first time a record goes on the platter, not at app launch:
     * an app that's only being browsed doesn't hold a media session or a service.
     */
    private fun connect() {
        if (connecting || controller != null) return
        connecting = true
        connectPlayer { c ->
            connecting = false
            if (c == null) return@connectPlayer
            controller = c
            c.addListener(listener)
            if (c.isPlaying) followPosition(true)
            pending.toList().forEach { it(c) }
            pending.clear()
        }
    }

    private var ticker: Job? = null

    /** Whether the needle's place is being followed; only while the record plays. */
    internal val ticking: Boolean get() = ticker?.isActive == true

    /**
     * Follows the needle five times a second while the record plays, and not at all otherwise:
     * the deck lives as long as the app, so a ticker that never stops would wake it for nothing.
     * Stopping reads the place once more, so the deck shows where it stopped.
     */
    private fun followPosition(playing: Boolean) {
        if (playing) {
            if (ticker?.isActive == true) return
            ticker = scope.launch {
                while (isActive) {
                    if (controller?.isPlaying == true) updatePosition()
                    delay(200)
                }
            }
        } else {
            ticker?.cancel()
            ticker = null
            updatePosition()
        }
    }

    private fun updatePosition() {
        val c = controller ?: return
        val s = _state.value
        if (!s.needleDown) return
        val side = s.side ?: return
        val pos = side.positionOf(c.currentMediaItemIndex, c.currentPosition)
        _state.update { it.copy(positionMs = pos) }
    }

    init {
        sfx.crackleEnabled = _crackleOn.value
        sound?.setCrackle(_crackleOn.value)
    }

    private fun withController(block: (Player) -> Unit) {
        val c = controller
        if (c != null) {
            block(c)
        } else {
            pending += block
            connect()
        }
    }

    /** The soft thud of a disc seating on the platter. Pure mode stays silent, as with every deck sound. */
    fun discLanded() {
        if (!pure) sfx.platterDrop()
    }

    /** MP3 only ever plays from the listener's own folder, and only with the flag on; every other record is FLAC. */
    private fun policyFor(album: Album): LocalAudioPolicy =
        if (album.source == app.runout.model.Source.FOLDER) LocalAudioPolicy.of(allowMp3()) else LocalAudioPolicy.FLAC_ONLY

    /** A record has been dropped on the platter. It plays at once with auto-play on; else it waits for the needle. */
    fun load(album: Album) {
        put(album)
        autoStart(0)
    }

    private fun put(album: Album) {
        val verified = policyFor(album).filterAlbum(context, album, refreshMetadata = false) ?: return
        val sides = SideSplitter.split(verified.tracks)
        rampJob?.cancel()
        sfx.stopCrackle()
        _state.value = DeckState(album = verified, sides = sides)
        cueSide(verified, sides.firstOrNull())
    }

    /**
     * Puts the record on, turns it to the side holding [trackId] and parks the needle over that
     * song. Nothing plays: the listener lowers the tonearm or presses START on the deck.
     */
    fun cueTrack(album: Album, trackId: String?) {
        put(album)
        val loaded = _state.value
        val record = loaded.album ?: return
        if (record.id != album.id) return
        val sideIndex = loaded.sides.indexOfFirst { side -> trackId == null || side.tracks.any { it.id == trackId } }
        if (sideIndex < 0) return
        val side = loaded.sides[sideIndex]
        val trackIndex = side.tracks.indexOfFirst { trackId == null || it.id == trackId }.coerceAtLeast(0)
        _state.update { it.copy(sideIndex = sideIndex, positionMs = side.positionOf(trackIndex, 0), needleDown = false, sideFinished = false) }
        if (sideIndex != 0) cueSide(record, side) // put() has already cued side A
        withController { c -> c.seekTo(trackIndex, 0) }
        autoStart(trackIndex)
    }

    /** Auto-play: the needle goes down by itself on the record just put on. */
    private fun autoStart(trackIndex: Int) {
        if (_flags.value.autoPlay && _state.value.album != null) dropAt(trackIndex, 0)
    }

    /**
     * Auto-flip: the runout groove turns a moment, then the record is turned over and the next side
     * plays from its first song. After the last side the record rests, as it would on a real deck.
     */
    private fun autoFlip() {
        val ended = _state.value
        if (!_flags.value.autoFlip || ended.sideIndex >= ended.sides.lastIndex) return
        scope.launch {
            delay(AUTO_FLIP_PAUSE_MS)
            val now = _state.value
            // Still the same record, side and silence: nobody has stepped in meanwhile.
            if (now.album?.id != ended.album?.id || now.sideIndex != ended.sideIndex || !now.sideFinished || now.needleDown) return@launch
            flip()
            dropAt(0, 0)
        }
    }

    private fun cueSide(album: Album, side: Side?) {
        withController { c ->
            c.pause()
            if (side == null) {
                c.clearMediaItems()
                return@withController
            }
            c.setMediaItems(mediaItemsFor(album, side, policyFor(album)))
            c.playbackParameters = PlaybackParameters(RECORDED_SPEED, RECORDED_SPEED)
            c.prepare()
        }
    }

    fun needleDrop(fraction: Float) {
        val side = _state.value.side ?: return
        val (index, offset) = side.locate(fraction)
        dropAt(index, offset)
    }

    /** Lowers the needle at an exact place: a track and a time inside it. */
    private fun dropAt(index: Int, offset: Long) {
        val side = _state.value.side ?: return
        if (!pure) sfx.needleDrop()
        _state.update { it.copy(needleDown = true, sideFinished = false, positionMs = side.positionOf(index, offset), held = false) }
        rampJob?.cancel()
        rampJob = scope.launch {
            withController { c ->
                c.volume = 1f
                // After a playback error the player rests idle until it's prepared again.
                if (c.playbackState == Player.STATE_IDLE && c.mediaItemCount > 0) c.prepare()
                c.seekTo(index, offset)
                c.playbackParameters = PlaybackParameters(RECORDED_SPEED, RECORDED_SPEED)
            }
            delay(420) // the needle settles; the program stays at full volume
            withController { c -> c.play() }
        }
    }

    fun liftNeedle() {
        if (!_state.value.needleDown) return
        rampJob?.cancel()
        if (!pure) sfx.lift()
        sfx.stopCrackle()
        // Whatever plays next starts at the recorded speed, even if a wind-down was cut short.
        withController { c -> c.pause(); c.volume = 1f; c.playbackParameters = PlaybackParameters(RECORDED_SPEED, RECORDED_SPEED) }
        _state.update { it.copy(needleDown = false, held = false) }
    }

    /** A finger on the platter: the music winds down like a real turntable. */
    fun holdPlatter() {
        val s = _state.value
        if (!s.needleDown || s.held) return
        _state.update { it.copy(held = true) }
        rampJob?.cancel()
        rampJob = scope.launch {
            if (!pure) {
                // Coasting to a stop: fast at first, easing out, from wherever the platter is now.
                val start = controller?.playbackParameters?.speed ?: RECORDED_SPEED
                for (step in 1..RAMP_STEPS) {
                    val t = step / RAMP_STEPS.toFloat()
                    val f = (start - (start - MIN_SPEED) * (1f - (1f - t) * (1f - t))).coerceAtLeast(MIN_SPEED)
                    withController { c -> c.playbackParameters = PlaybackParameters(f, f) }
                    delay(RAMP_STEP_MS)
                }
            }
            // Paused at the recorded speed, so a play from anywhere (the notification, a headset)
            // never starts slowed.
            withController { c -> c.pause(); c.playbackParameters = PlaybackParameters(RECORDED_SPEED, RECORDED_SPEED) }
        }
    }

    fun releasePlatter() {
        val s = _state.value
        if (!s.held) return
        _state.update { it.copy(held = false) }
        if (!s.needleDown) return
        rampJob?.cancel()
        rampJob = scope.launch {
            val end = RECORDED_SPEED
            if (pure) {
                withController { c -> c.play() }
                return@launch
            }
            val startF = MIN_SPEED
            try {
                withController { c ->
                    c.playbackParameters = PlaybackParameters(startF, startF)
                    c.play()
                }
                for (step in 1..RAMP_STEPS) {
                    val t = step / RAMP_STEPS.toFloat()
                    val f = startF + (end - startF) * (1f - (1f - t) * (1f - t))
                    withController { c -> c.playbackParameters = PlaybackParameters(f, f) }
                    delay(RAMP_STEP_MS)
                }
            } finally {
                // Cut short by anything but a new hold: back to the recorded speed.
                if (!_state.value.held) withController { c -> c.playbackParameters = PlaybackParameters(RECORDED_SPEED, RECORDED_SPEED) }
            }
        }
    }

    /**
     * The big start/stop button. With the needle up it lowers it where the side left off; with the
     * needle down it stops the platter (the music winds down) or starts it again.
     */
    fun startStop() {
        val s = _state.value
        if (s.album == null) return
        when {
            !s.needleDown -> if (s.sideFinished) needleDrop(0f) else dropAt(s.trackIndex, s.trackPositionMs)
            s.held -> releasePlatter()
            else -> holdPlatter()
        }
    }

    /** Next track on this side. The last track stays put: turn the record over to go on. */
    fun nextTrack() {
        val s = _state.value
        val side = s.side ?: return
        if (s.trackIndex < side.tracks.lastIndex) jumpTo(s.sideIndex, s.trackIndex + 1)
    }

    /** Back to the start of this track, or to the one before if it has only just begun. */
    fun previousTrack() {
        val s = _state.value
        if (s.side == null) return
        when {
            s.needleDown && s.trackPositionMs > RESTART_WINDOW_MS -> jumpTo(s.sideIndex, s.trackIndex)
            s.trackIndex > 0 -> jumpTo(s.sideIndex, s.trackIndex - 1)
            else -> jumpTo(s.sideIndex, 0)
        }
    }

    /**
     * A song picked from the track wheel ("tap to play"): the needle goes to it and it plays,
     * turning the record over first when the song is on the other side.
     */
    fun playTrack(sideIndex: Int, trackIndex: Int) {
        val s = _state.value
        if (s.album == null || s.sides.getOrNull(sideIndex)?.tracks?.getOrNull(trackIndex) == null) return
        jumpTo(sideIndex, trackIndex)
    }

    private fun jumpTo(sideIndex: Int, trackIndex: Int) {
        val s = _state.value
        val album = s.album ?: return
        if (sideIndex != s.sideIndex) {
            // Another side: lift, turn the record over, then drop at the track.
            rampJob?.cancel()
            if (s.needleDown && !pure) sfx.lift()
            sfx.stopCrackle()
            _state.update { it.copy(sideIndex = sideIndex, sideFinished = false, positionMs = 0, needleDown = false, held = false) }
            cueSide(album, s.sides[sideIndex])
        }
        dropAt(trackIndex, 0)
    }

    /** Called when Pure mode is switched, so the deck drops crackle and any wind-down straight away. */
    fun onPureChanged(on: Boolean) {
        if (on) {
            sfx.stopCrackle()
            withController { c -> c.playbackParameters = PlaybackParameters(RECORDED_SPEED, RECORDED_SPEED) }
        } else if (_state.value.isPlaying && _crackleOn.value) {
            sfx.startCrackle()
        }
    }

    /** Turn the record over. Sides run A, B, then C, D for double albums. */
    fun flip() {
        val s = _state.value
        val album = s.album ?: return
        if (s.sides.size < 2) {
            _state.update { it.copy(sideFinished = false, positionMs = 0) }
            cueSide(album, s.side)
            return
        }
        liftNeedle()
        val next = (s.sideIndex + 1) % s.sides.size
        _state.update { it.copy(sideIndex = next, sideFinished = false, positionMs = 0, needleDown = false) }
        cueSide(album, s.sides[next])
    }

    fun eject() {
        liftNeedle()
        withController { c -> c.stop(); c.clearMediaItems() }
        _state.value = DeckState()
    }

    fun toggleCrackle() {
        val on = !_crackleOn.value
        _crackleOn.value = on
        prefs.edit().putBoolean("crackle", on).apply()
        sfx.crackleEnabled = on
        sound?.setCrackle(on)
        if (on && _state.value.isPlaying && !pure) sfx.startCrackle()
    }

    companion object {
        /** Binds to [PlaybackService] through a MediaController. */
        fun connectToService(context: Context, ready: (Player?) -> Unit) {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            future.addListener({ ready(runCatching { future.get() }.getOrNull()) }, ContextCompat.getMainExecutor(context))
        }

        /** What went wrong, in words a listener can act on. */
        fun describe(error: PlaybackException): String = when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "This track couldn't be decoded on this phone. The needle is up."
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "The phone's audio output couldn't play this track. The needle is up."
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> "Couldn't read this track. Is its folder still there?"
            else -> "This track stopped playing. The needle is up."
        }

        /** Wind-down and spin-up: small steps, so the pitch glides instead of stepping. */
        private const val RAMP_STEPS = 25
        private const val RAMP_STEP_MS = 18L

        /** Android's playback-speed control doesn't go much lower without distorting. */
        const val MIN_SPEED = 0.25f
        /** The platter only ever turns at the speed the music was recorded at. */
        const val RECORDED_SPEED = 1f
        /** Pressing "previous" this far into a track restarts it instead. */
        const val RESTART_WINDOW_MS = 3_000L

        fun mediaItemsFor(album: Album, side: Side, policy: LocalAudioPolicy = LocalAudioPolicy.FLAC_ONLY): List<MediaItem> = side.tracks.map { t ->
            val name = LocalAudioPolicy.nameOf(t)
            require(policy.accepts(name)) { if (policy.mp3) "Only FLAC and MP3 tracks can be played" else "Only FLAC tracks can be played" }
            val extras = android.os.Bundle().apply {
                album.albumGainDb?.let { putFloat(PlaybackService.EXTRA_ALBUM_GAIN, it) }
                t.trackGainDb?.let { putFloat(PlaybackService.EXTRA_TRACK_GAIN, it) }
            }
            MediaItem.Builder()
                .setMediaId(t.id)
                .setUri(Uri.parse(t.uri))
                // The player is told what the file is: MP3 as MP3, never passed off as FLAC.
                .setMimeType(LocalAudioPolicy.mimeTypeFor(name))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(t.title)
                        .setArtist(album.artist)
                        .setAlbumTitle("${album.title} · Side ${side.letter}")
                        .setTrackNumber(t.trackNumber)
                        .setArtworkUri(album.coverPath?.let { Uri.fromFile(File(it)) })
                        .setExtras(extras)
                        .build(),
                )
                .build()
        }
    }
}

/** Runout groove, then the turn: how long a finished side spins before auto-flip turns it over. */
private const val AUTO_FLIP_PAUSE_MS = 2_500L
/** The sleep timer's fade-out before the needle lifts. */
private const val SLEEP_FADE_MS = 8_000L
private const val SLEEP_FADE_STEPS = 32

package com.lunara.music.service.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.lunara.extractor.StreamResolver
import com.lunara.music.data.models.RepeatMode
import com.lunara.music.data.models.Song
import com.lunara.music.database.LunaraDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

object LunaraPlayerManager {
    private const val TAG = "LunaraPlayerManager"

    private var exoPlayer: ExoPlayer? = null
    private var serviceContext: Context? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var positionJob: Job? = null

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _bufferedPositionMs = MutableStateFlow(0L)
    val bufferedPositionMs: StateFlow<Long> = _bufferedPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _queueIndex = MutableStateFlow(-1)
    val queueIndex: StateFlow<Int> = _queueIndex.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    private var songRetryCount = 0

    // NOTE: cannot be a companion object — this is an `object`, not a class.
    private const val MAX_SONG_RETRIES = 3

    /**
     * How many times one user-initiated load may silently reconnect a stalled
     * stream before it says so out loud. Bounded because the unbounded version is
     * the failure that reads as "buffer 3 seconds, pause, forever".
     */
    private const val MAX_STALL_RECOVERIES = 5

    /**
     * How long the buffer may sit completely still before we treat the stream
     * as dead. Long enough that a slow-but-working connection (or a seek's
     * short rebuffer) never trips it, short enough that a truly dead URL does
     * not leave the UI spinning for a minute.
     */
    private const val STALL_TIMEOUT_MS = 25_000L

    /**
     * Minimum gap between two stall recoveries for the same song. Without it a
     * dead stream would reconnect in a tight loop; with only a one-shot flag
     * the second death would strand the song buffering forever.
     */
    private const val RECOVER_COOLDOWN_MS = 30_000L

    // Stall-watchdog state.
    private var lastPosition = -1L
    private var lastBuffered = -1L
    private var lastProgressAt = 0L
    private var stallRecovered = false
    private var stallRecoveryCount = 0

    /**
     * The URI and container of the item being played — what a stall reconnects
     * with. For an online song the URI is the video id, so re-preparing it sends
     * the resolving data source back to the network for a fresh address.
     */
    private var currentMediaUri: String? = null
    private var currentContentType: String? = null

    fun init(context: Context) {
        serviceContext = context.applicationContext
        startService(context)
    }

    private fun startService(context: Context) {
        val intent = Intent(context, LunaraMediaSessionService::class.java)
        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start LunaraMediaSessionService: ${e.message}")
        }
    }

    fun getPlayer(): ExoPlayer? = exoPlayer

    internal fun attachPlayer(player: ExoPlayer, context: Context) {
        exoPlayer = player
        serviceContext = context.applicationContext
        setupPlayerListener()
    }

    internal fun detachPlayer() {
        exoPlayer = null
        stopPositionTracker()
    }

    private fun setupPlayerListener() {
        exoPlayer?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                // Actual audio output state. This is the ONLY writer that may turn
                // the play/pause affordance to "Play" mid-song: buffering, seeks
                // and stall recoveries all keep playWhenReady=true, so deriving
                // _isPlaying from the buffering state is exactly what painted a
                // "paused" UI over a song that was about to keep playing.
                // Deliberately NOT `_isPlaying = playing`: ExoPlayer reports
                // isPlaying=false through every rebuffer and seek, and painting
                // that as "paused" is the flicker users read as an auto-pause
                // mid-song. The affordance follows playWhenReady (user intent)
                // via onPlayWhenReadyChanged below; position tracking starts in
                // playMediaUri, NOT here, because a stalled stream never reports
                // isPlaying=true and gating the watchdog on it would blind it.
                // (see playMediaUri), NOT here: a stalled stream never reports
                // isPlaying = true, so gating it on this callback would mean the
                // one failure we most need to catch is the one we never watch.
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // The play/pause affordance follows *intent*, not audio output:
                // isPlaying drops to false for every rebuffer and seek while the
                // song is still meant to be playing, and surfacing that as
                // "paused" is the flicker users read as auto-pause mid-song.
                if (_currentSong.value != null &&
                    exoPlayer?.playbackState != Player.STATE_ENDED
                ) {
                    _isPlaying.value = playWhenReady
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                // A seek must never pause and must not trip the stall watchdog:
                // re-baseline it so the position jump and the short rebuffer
                // after a fast-forward are not mistaken for a dead stream.
                if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                ) {
                    lastPosition = newPosition.positionMs
                    exoPlayer?.let { lastBuffered = it.bufferedPosition }
                    lastProgressAt = SystemClock.elapsedRealtime()
                    _currentPositionMs.value = newPosition.positionMs
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                exoPlayer?.let { p ->
                    _bufferedPositionMs.value = p.bufferedPosition.coerceAtLeast(0L)
                }
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        _isBuffering.value = true
                        _playbackError.value = null
                    }
                    Player.STATE_READY -> {
                        _isBuffering.value = false
                        _playbackError.value = null
                        // Ready means data flowed and playback moved: both budgets
                        // are per-failure, not per-song, so a track that plays and
                        // later stumbles still gets its full recovery allowance.
                        songRetryCount = 0
                        stallRecoveryCount = 0
                        _durationMs.value = exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L
                    }
                    Player.STATE_ENDED -> {
                        _isBuffering.value = false
                        handleSongEnded()
                    }
                    Player.STATE_IDLE -> {
                        _isBuffering.value = false
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Playback error: ${error.message}", error)
                _isBuffering.value = false
                // _isPlaying is left alone on purpose: the playWhenReady listener
                // reports the truth right after this, and the retry below keeps
                // playing — writing false first is the flash of "paused" users
                // see on errors the app was about to recover from.
                val song = _currentSong.value
                if (song != null) {
                    // Where the listener had got to. The retry re-enters the stream
                    // at the same second through the resolving data source instead
                    // of starting the song over from nothing.
                    val resumeAt = exoPlayer?.currentPosition ?: 0L
                    // The resolved stream URL may have expired or been rejected;
                    // drop it, resolve the next audio candidate, and retry the
                    // same song instead of leaving the player silent.
                    StreamResolver.invalidate(song.id)
                    if (songRetryCount < MAX_SONG_RETRIES) {
                        songRetryCount += 1
                        _playbackError.value = "Retrying playback..."
                        // isRetry stops loadAndPlay zeroing the retry budget. That
                        // reset used to make every failure look like a first failure,
                        // so a dead URL retried forever instead of ever giving up.
                        loadAndPlay(song, resumeFromMs = resumeAt, isRetry = true)
                        return
                    }
                }
                _playbackError.value = "Couldn't play this song. Tap to retry."
            }
        })
    }

    private fun startPositionTracker() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive) {
                exoPlayer?.let { player ->
                    _currentPositionMs.value = player.currentPosition.coerceAtLeast(0L)
                    _bufferedPositionMs.value = player.bufferedPosition.coerceAtLeast(0L)
                    val dur = player.duration
                    if (dur > 0L) {
                        _durationMs.value = dur
                    }
                    watchForStall(player)
                }
                delay(400)
            }
        }
    }

    /**
     * Stall watchdog.
     *
     * A truncated googlevideo URL lets ExoPlayer fill its buffer and then sit in
     * `STATE_BUFFERING` indefinitely: no error, no progress, no end of stream.
     * That is the exact failure this player was built to avoid, so we detect it
     * ourselves — if neither the position nor the buffered position has moved
     * for [STALL_TIMEOUT_MS] while buffering, the stream is treated as dead, the
     * cached URL is dropped and the song is re-resolved against a different
     * client instead of hanging.
     */
    private fun watchForStall(player: ExoPlayer) {
        if (player.playbackState != Player.STATE_BUFFERING) {
            lastPosition = -1L
            lastBuffered = -1L
            lastProgressAt = SystemClock.elapsedRealtime()
            return
        }

        val position = player.currentPosition
        val buffered = player.bufferedPosition
        val now = SystemClock.elapsedRealtime()

        if (position != lastPosition || buffered != lastBuffered) {
            lastPosition = position
            lastBuffered = buffered
            lastProgressAt = now
            return
        }

        // A brand-new buffer has not had time to fill yet; give it room. The
        // progress check above already resets this window, so we only reach
        // here when the buffer genuinely has not moved.
        if (now - lastProgressAt < STALL_TIMEOUT_MS) return

        val song = _currentSong.value ?: return
        val uri = currentMediaUri ?: return
        // One recovery per buffering episode is not enough: a capped URL dies,
        // the fresh one dies the same way, and a pure one-shot flag would
        // strand the song buffering forever. Allow re-recovery once the last
        // one is old; the count budget below is what still stops a true loop.
        if (stallRecovered && now - lastProgressAt < RECOVER_COOLDOWN_MS) return
        stallRecovered = true
        lastProgressAt = now

        // The budget is what separates "reconnecting" from "looping forever". It is
        // refilled whenever the player reaches READY (see the listener above), so
        // this only ever stops a stream that silently dies over and over.
        if (stallRecoveryCount >= MAX_STALL_RECOVERIES) {
            Log.w(TAG, "Playback stalled $MAX_STALL_RECOVERIES times; giving up on ${song.id}")
            _isBuffering.value = false
            _playbackError.value = "Couldn't keep this song playing. Tap to retry."
            return
        }
        stallRecoveryCount += 1

        // The reconnect continues from here rather than restarting the song. The
        // cached URL is dropped first — it is the one that just failed — so the
        // resolving data source's next open mints a fresh one through the health-
        // ordered client rotation.
        val resumeAt = player.currentPosition
        Log.w(TAG, "Playback stalled with no buffer progress for ${STALL_TIMEOUT_MS}ms; reconnecting ${song.id} at ${resumeAt}ms")
        _playbackError.value = "Stream stalled — reconnecting…"
        StreamResolver.invalidate(song.id)
        scope.launch {
            withContext(Dispatchers.Main) {
                playMediaUri(song, uri, currentContentType, resumePositionMs = resumeAt)
            }
        }
    }

    private fun stopPositionTracker() {
        positionJob?.cancel()
        positionJob = null
    }

    fun playSong(song: Song, newQueue: List<Song> = emptyList()) {
        val q = if (newQueue.isNotEmpty()) newQueue else listOf(song)
        val idx = q.indexOfFirst { it.id == song.id }.let { if (it >= 0) it else 0 }
        _queue.value = q
        _queueIndex.value = idx
        loadAndPlay(song)
    }

    fun playSongs(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val validIndex = startIndex.coerceIn(0, songs.size - 1)
        _queue.value = songs
        _queueIndex.value = validIndex
        loadAndPlay(songs[validIndex])
    }

    private var loadJob: Job? = null

    /**
     * @param resumeFromMs where a recovery should continue from; 0 for a fresh
     *   user-initiated play.
     * @param isRetry true when this reload is the player recovering from an error.
     *   Retries must not zero the retry budget (that reset is what used to turn a
     *   dead URL into an infinite "buffering…" loop) nor the stall budget.
     */
    private fun loadAndPlay(song: Song, resumeFromMs: Long = 0L, isRetry: Boolean = false) {
        _currentSong.value = song
        if (!isRetry) {
            songRetryCount = 0
            stallRecoveryCount = 0
        }
        _isBuffering.value = true
        _playbackError.value = null
        _currentPositionMs.value = resumeFromMs

        // Reset the stall watchdog for the new track.
        lastPosition = -1L
        lastBuffered = -1L
        lastProgressAt = SystemClock.elapsedRealtime()
        stallRecovered = false

        // Record history in local Room database
        serviceContext?.let { ctx ->
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val db = LunaraDatabase.getDatabase(ctx)
                    db.songDao().insertOrUpdateSong(song.toEntity())
                    db.songDao().recordPlay(song.id, System.currentTimeMillis())
                }
            }
        }

        // Cancel any in-flight resolve so rapid taps never pile up resolves on
        // the (single) main-thread scope — that pile-up is what froze the UI
        // into "Lunara isn't responding". Only the latest tap keeps running.
        loadJob?.cancel()
        loadJob = scope.launch(Dispatchers.IO) {
            // 1. A downloaded file on disk always wins.
            val localFile = song.localFilePath
                ?.takeIf { it.isNotBlank() }
                ?.let { path -> File(path).takeIf { it.exists() && it.length() > 0 } }
            if (localFile != null) {
                withContext(Dispatchers.Main) {
                    playMediaUri(
                        song,
                        Uri.fromFile(localFile).toString(),
                        resumePositionMs = resumeFromMs,
                    )
                }
                return@launch
            }

            // 2. Otherwise resolve a stream and prove it is playable.
            //
            //    Note: the persisted `song.streamUrl` is deliberately NOT reused.
            //    For catalogue tracks it holds a googlevideo URL that expires
            //    within hours; replaying it produced an endless "couldn't play"
            //    loop because invalidating the resolver cache never touched the
            //    copy stored in Room. Only a `content://` URI from the local
            //    media scanner is a stable, reusable value.
            val persisted = song.streamUrl?.takeIf { it.startsWith("content://") }
            if (persisted != null) {
                withContext(Dispatchers.Main) {
                    playMediaUri(song, persisted, resumePositionMs = resumeFromMs)
                }
                return@launch
            }

            // Resolve a stream. The extractor mints a BotGuard token first and then rotates
            // through clients, because without a token YouTube either bot-gates the
            // request or hands back a URL whose media is capped at 1 MiB — which
            // ExoPlayer experiences as an endless buffer rather than as an error.
            // Bounded so a dead network/token can never pin the player in a
            // buffering state forever (which the UI reads as a hang).
            val stream = try {
                withTimeout(45_000L) {
                    when (val outcome = StreamResolver.resolve(song.id)) {
                        is StreamResolver.Outcome.Success -> outcome.stream
                        is StreamResolver.Outcome.Failure -> {
                            _isBuffering.value = false
                            // Say what actually went wrong. "Couldn't stream this song" for a
                            // track YouTube has removed is technically true and completely
                            // useless, and that vagueness is how a broken player passes for a
                            // working one that simply has no music.
                            _playbackError.value = outcome.reason.toUserMessage()
                            Log.w(TAG, "Could not resolve ${song.id}: ${outcome.reason}")
                            null
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Superseded by a newer tap, or the scope died — stay silent.
                throw e
            } catch (e: Exception) {
                _isBuffering.value = false
                _playbackError.value = "Couldn't start this song. Check your connection and retry."
                Log.w(TAG, "Resolve for ${song.id} failed: ${e.message}")
                null
            }
            if (stream == null) return@launch

            // Bind the minting identity to this URL so the data source sends the
            // matching User-Agent / Referer on every range request.
            StreamHeaders.register(stream.url, stream.headers)

            // The player is handed the *video id*, not the URL. From here on the
            // resolving data source owns the address: it resolves at every load,
            // caps each load at 512 KiB and swaps in a fresh URL the moment the old
            // one expires or is refused — which is what stops a mid-song death from
            // becoming "buffer, pause, repeat". (The register above pre-warms the
            // header registry; the data source re-registers on every open.)
            withContext(Dispatchers.Main) {
                playMediaUri(song, song.id, stream.containerMimeType, resumeFromMs)
            }
        }
    }

    private fun playMediaUri(
        song: Song,
        mediaUri: String,
        contentType: String? = null,
        resumePositionMs: Long = 0L,
    ) {
        val player = exoPlayer
        if (player == null) {
            serviceContext?.let { startService(it) }
            _playbackError.value = "Starting audio service..."
            return
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album ?: "Lunara")
            .setArtworkUri(song.thumbnailUrl?.let { Uri.parse(it) })
            .build()

        // A schemeless URI is the video id, and the custom cache key is how the
        // resolving data source finds it again at every chunk open. Real addresses
        // (file://, content://) need neither and pass through untouched.
        val isVideoId = Uri.parse(mediaUri).scheme == null
        val mediaItem = MediaItem.Builder()
            .setUri(mediaUri)
            .setMediaId(song.id)
            .setMimeType(contentType)
            .setMediaMetadata(metadata)
            .apply { if (isVideoId) setCustomCacheKey(song.id) }
            .build()

        // Remembered for the stall watchdog: a reconnect re-enters with exactly
        // this URI, so an online song goes back to the network for a fresh address
        // while a local file is simply reopened.
        currentMediaUri = mediaUri
        currentContentType = contentType
        // Every prepared item gets its own chance at the watchdog.
        stallRecovered = false

        player.setMediaItem(mediaItem)
        player.prepare()
        if (resumePositionMs > 0L) player.seekTo(resumePositionMs)
        // playWhenReady=true fires onPlayWhenReadyChanged, which is the single
        // writer of the play/pause affordance — no manual _isPlaying write here.
        player.playWhenReady = true

        // Watch from the moment the item is prepared: a truncated stream will sit
        // in STATE_BUFFERING forever without ever reaching isPlaying.
        startPositionTracker()
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.playWhenReady) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun resume() {
        // The playWhenReady listener updates the affordance; writing it here
        // as well would only risk racing the listener.
        exoPlayer?.play()
    }

    fun seekTo(positionMs: Long) {
        val player = exoPlayer ?: return
        // Preserve the play intent across the seek and say so explicitly:
        // a fast-forward must never come back paused.
        val resume = player.playWhenReady
        player.seekTo(positionMs)
        player.playWhenReady = resume
        _currentPositionMs.value = positionMs
    }

    fun next() {
        val q = _queue.value
        if (q.isEmpty()) return

        if (_repeatMode.value == RepeatMode.ONE) {
            _currentSong.value?.let { loadAndPlay(it) }
            return
        }

        var nextIndex = _queueIndex.value + 1
        if (nextIndex >= q.size) {
            if (_repeatMode.value == RepeatMode.ALL) {
                nextIndex = 0
            } else {
                return // Reached end of queue
            }
        }

        _queueIndex.value = nextIndex
        loadAndPlay(q[nextIndex])
    }

    fun previous() {
        val player = exoPlayer
        if (player != null && player.currentPosition > 3000L) {
            player.seekTo(0)
            _currentPositionMs.value = 0L
            return
        }

        val q = _queue.value
        if (q.isEmpty()) return

        var prevIndex = _queueIndex.value - 1
        if (prevIndex < 0) {
            prevIndex = if (_repeatMode.value == RepeatMode.ALL) q.size - 1 else 0
        }

        _queueIndex.value = prevIndex
        loadAndPlay(q[prevIndex])
    }

    private fun handleSongEnded() {
        when (_repeatMode.value) {
            RepeatMode.ONE -> {
                exoPlayer?.seekTo(0)
                exoPlayer?.play()
            }
            RepeatMode.ALL -> next()
            RepeatMode.OFF -> {
                if (_queueIndex.value < _queue.value.size - 1) {
                    next()
                } else {
                    _isPlaying.value = false
                }
            }
        }
    }

    fun toggleShuffle() {
        val newShuffle = !_shuffleEnabled.value
        _shuffleEnabled.value = newShuffle
        val current = _currentSong.value ?: return
        val currentQ = _queue.value.toMutableList()
        if (newShuffle) {
            currentQ.remove(current)
            currentQ.shuffle()
            currentQ.add(0, current)
            _queue.value = currentQ
            _queueIndex.value = 0
        }
    }

    fun toggleRepeat() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    fun addToQueue(song: Song) {
        val list = _queue.value.toMutableList()
        list.add(song)
        _queue.value = list
    }

    fun addNext(song: Song) {
        val list = _queue.value.toMutableList()
        val insertIndex = (_queueIndex.value + 1).coerceIn(0, list.size)
        list.add(insertIndex, song)
        _queue.value = list
    }

    fun removeFromQueue(index: Int) {
        val list = _queue.value.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            _queue.value = list
            if (index < _queueIndex.value) {
                _queueIndex.value = _queueIndex.value - 1
            }
        }
    }

    fun reorderQueue(from: Int, to: Int) {
        val list = _queue.value.toMutableList()
        if (from in list.indices && to in list.indices) {
            val item = list.removeAt(from)
            list.add(to, item)
            _queue.value = list
            // Update queueIndex if current playing song moved
            if (_queueIndex.value == from) {
                _queueIndex.value = to
            } else if (from < _queueIndex.value && to >= _queueIndex.value) {
                _queueIndex.value = _queueIndex.value - 1
            } else if (from > _queueIndex.value && to <= _queueIndex.value) {
                _queueIndex.value = _queueIndex.value + 1
            }
        }
    }

    fun clearQueue() {
        val current = _currentSong.value
        if (current != null) {
            _queue.value = listOf(current)
            _queueIndex.value = 0
        } else {
            _queue.value = emptyList()
            _queueIndex.value = -1
        }
    }
}

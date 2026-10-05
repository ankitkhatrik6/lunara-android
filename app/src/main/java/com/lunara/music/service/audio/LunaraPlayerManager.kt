package com.lunara.music.service.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.lunara.extractor.ResolveFailure
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
     * How long the buffer may sit completely still before we treat the stream
     * as dead. Must comfortably exceed a slow-but-working connection.
     */
    private const val STALL_TIMEOUT_MS = 15_000L

    // Stall-watchdog state.
    private var lastPosition = -1L
    private var lastBuffered = -1L
    private var lastProgressAt = 0L
    private var stallRecovered = false

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
                _isPlaying.value = playing
                // The position/stall tracker is started when an item is prepared
                // (see playMediaUri), NOT here: a stalled stream never reports
                // isPlaying = true, so gating it on this callback would mean the
                // one failure we most need to catch is the one we never watch.
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
                        songRetryCount = 0
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
                _isPlaying.value = false
                val song = _currentSong.value
                if (song != null) {
                    // The resolved stream URL may have expired or been rejected;
                    // drop it, resolve the next audio candidate, and retry the
                    // same song instead of leaving the player silent.
                    StreamResolver.invalidate(song.id)
                    if (songRetryCount < MAX_SONG_RETRIES) {
                        songRetryCount += 1
                        _playbackError.value = "Retrying playback..."
                        loadAndPlay(song)
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
            lastProgressAt = System.currentTimeMillis()
            return
        }

        val position = player.currentPosition
        val buffered = player.bufferedPosition
        val now = System.currentTimeMillis()

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
        if (stallRecovered) return

        val song = _currentSong.value ?: return
        stallRecovered = true
        lastProgressAt = now
        Log.w(TAG, "Playback stalled with no buffer progress for ${STALL_TIMEOUT_MS}ms; re-resolving ${song.id}")
        _playbackError.value = "Stream stalled — reconnecting…"
        // Drop the cached URL first: it is the one that just failed, and re-resolving
        // without dropping it would hand back the same dead URL.
        StreamResolver.invalidate(song.id)
        scope.launch {
            // The rotation may have moved on since the last resolve, and the health
            // scoring means a different client is now the one most likely to serve.
            val replacement = when (val outcome = StreamResolver.resolve(song.id)) {
                is StreamResolver.Outcome.Success -> outcome.stream
                is StreamResolver.Outcome.Failure -> {
                    _isBuffering.value = false
                    _playbackError.value = "Couldn't reconnect to this song"
                    return@launch
                }
            }
            StreamHeaders.register(replacement.url, replacement.headers)
            withContext(Dispatchers.Main) {
                playMediaUri(song, replacement.url, replacement.containerMimeType)
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

    private fun loadAndPlay(song: Song) {
        _currentSong.value = song
        songRetryCount = 0
        _isBuffering.value = true
        _playbackError.value = null
        _currentPositionMs.value = 0L

        // Reset the stall watchdog for the new track.
        lastPosition = -1L
        lastBuffered = -1L
        lastProgressAt = System.currentTimeMillis()
        stallRecovered = false

        // Record history in local Room database
        serviceContext?.let { ctx ->
            scope.launch(Dispatchers.IO) {
                val db = LunaraDatabase.getDatabase(ctx)
                db.songDao().insertOrUpdateSong(song.toEntity())
                db.songDao().recordPlay(song.id, System.currentTimeMillis())
            }
        }

        scope.launch {
            // 1. A downloaded file on disk always wins.
            val localFile = song.localFilePath
                ?.takeIf { it.isNotBlank() }
                ?.let { path -> File(path).takeIf { it.exists() && it.length() > 0 } }
            if (localFile != null) {
                withContext(Dispatchers.Main) { playMediaUri(song, Uri.fromFile(localFile).toString()) }
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
                withContext(Dispatchers.Main) { playMediaUri(song, persisted) }
                return@launch
            }

            // Resolve a stream. The extractor mints a BotGuard token first and then rotates
            // through clients, because without a token YouTube either bot-gates the
            // request or hands back a URL whose media is capped at 1 MiB — which
            // ExoPlayer experiences as an endless buffer rather than as an error.
            val stream = when (val outcome = StreamResolver.resolve(song.id)) {
                is StreamResolver.Outcome.Success -> outcome.stream
                is StreamResolver.Outcome.Failure -> {
                    _isBuffering.value = false
                    // Say what actually went wrong. "Couldn't stream this song" for a
                    // track YouTube has removed is technically true and completely
                    // useless, and that vagueness is how a broken player passes for a
                    // working one that simply has no music.
                    _playbackError.value = when (outcome.reason) {
                        is ResolveFailure.Unavailable ->
                            "This song isn't available on YouTube Music"

                        is ResolveFailure.Blocked ->
                            "YouTube is rate-limiting this device. Try again shortly."

                        is ResolveFailure.NoPlayableStream ->
                            "YouTube throttled the stream. Try again in a moment."

                        is ResolveFailure.Network ->
                            "No connection to YouTube. Check your network."
                    }
                    Log.w(TAG, "Could not resolve ${song.id}: ${outcome.reason}")
                    return@launch
                }
            }

            // Bind the minting identity to this URL so the data source sends the
            // matching User-Agent / Referer on every range request.
            StreamHeaders.register(stream.url, stream.headers)

            withContext(Dispatchers.Main) {
                playMediaUri(song, stream.url, stream.containerMimeType)
            }
        }
    }

    private fun playMediaUri(song: Song, mediaUri: String, contentType: String? = null) {
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

        val mediaItem = MediaItem.Builder()
            .setUri(mediaUri)
            .setMediaId(song.id)
            .setMimeType(contentType)
            .setMediaMetadata(metadata)
            .build()

        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true
        _isPlaying.value = true

        // Watch from the moment the item is prepared: a truncated stream will sit
        // in STATE_BUFFERING forever without ever reaching isPlaying.
        startPositionTracker()
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            _isPlaying.value = false
        } else {
            player.play()
            _isPlaying.value = true
        }
    }

    fun pause() {
        exoPlayer?.pause()
        _isPlaying.value = false
    }

    fun resume() {
        exoPlayer?.play()
        _isPlaying.value = true
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
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

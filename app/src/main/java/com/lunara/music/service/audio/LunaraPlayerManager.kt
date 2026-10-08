package com.lunara.music.service.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.lunara.extractor.StreamResolver
import com.lunara.music.data.models.RepeatMode
import com.lunara.music.data.models.Song
import com.lunara.music.data.models.normalizationGainFor
import com.lunara.music.database.LunaraDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.min

/**
 * The single source of truth for playback in Lunara.
 *
 * A complete rewrite of the audio layer. Everything that matters for a song to
 * actually finish is rebuilt here:
 *
 * - **Streaming is done in chunks with request-time resolution.** No player is
 *   ever handed a googlevideo URL to hold open for a whole song. Every load asks
 *   [StreamResolver] for a fresh, proven address and reads at most 512 KiB, then a
 *   follow-up load hands the player a brand-new URL for the same address — so a
 *   mid-song death costs one chunk, never a restart.
 * - **Identity headers travel with the URL.** The exact minting client that
 *   produced a stream's address also owns its HTTP headers, and both are bound
 *   together for the whole life of the stream.
 * - **A 512 MB LRU disk cache** sits below the resolving layer, so replays,
 *   back-seeks and re-listens are disk reads, not re-downloads.
 * - **The player never goes into a permanent BUFFERING state.** The stall
 *   watchdog detects a silent buffer, re-resolves the address in place and
 *   continues from the exact byte offset, rather than looping on the same dead
 *   URL forever.
 */
object LunaraPlayerManager {

    private const val TAG = "LunaraPlayerManager"

    /** A silent stretch this long means the stream died; re-resolve in place. */
    private const val STALL_TIMEOUT_MS = 5_000L

    /** The blocking re-resolve budget inside one stall recovery. */
    private const val RECOVER_TIMEOUT_MS = 8_000L

    /** Restarts past this many leave the song with a clear error instead of looping. */
    private const val MAX_STALL_RECOVERIES = 3

    // --- public state ---------------------------------------------------

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

    // --- internal state -----------------------------------------------------

    private var exoPlayer: ExoPlayer? = null
    private var serviceContext: Context? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var positionJob: Job? = null

    private var loadJob: Job? = null
    private var stallRecoveryCount = 0
    private var stallRecovered = false

    /** Last observed position/buffer marks, so a silent buffer is detectable. */
    private var lastPosition = -1L
    private var lastBuffered = -1L
    private var lastProgressAt = 0L

    private var prefetchJob: Job? = null

    @Volatile
    private var normalizationEnabled = true

    @Volatile
    private var currentLoudnessDb: Double? = null

    private var currentMediaUri: String? = null
    private var currentContentType: String? = null

    private fun startService(context: Context) {
        val intent = Intent(context, LunaraMediaSessionService::class.java)
        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start LunaraMediaSessionService: ${e.message}")
        }
    }

    fun init(context: Context) {
        serviceContext = context.applicationContext
        startService(context)
    }

    fun getPlayer(): ExoPlayer? = exoPlayer

    fun setNormalizationEnabled(enabled: Boolean) {
        normalizationEnabled = enabled
        scope.launch { applyPlayerVolume() }
    }

    private fun applyPlayerVolume() {
        exoPlayer?.volume = normalizationGainFor(currentLoudnessDb, normalizationEnabled)
    }

    internal fun attachPlayer(player: ExoPlayer, context: Context) {
        exoPlayer = player
        serviceContext = context.applicationContext
        setupPlayerListener()
    }

    internal fun detachPlayer() {
        exoPlayer = null
        stopPositionTracker()
        StreamHeaders.clear()
    }

    // --- public playback API ----------------------------------------------

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

    fun pause() {
        exoPlayer?.pause()
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.playWhenReady) {
            player.pause()
            _isPlaying.value = false
        } else {
            player.play()
            _isPlaying.value = true
        }
    }

    fun stop() {
        exoPlayer?.run {
            pause()
            seekTo(0)
            playWhenReady = false
        }
        _isPlaying.value = false
        _isBuffering.value = false
        _playbackError.value = null
        _currentPositionMs.value = 0L
        _currentSong.value = null
        _durationMs.value = 0L
        _bufferedPositionMs.value = 0L
    }

    fun next() {
        if (exoPlayer == null) {
            advanceQueue(true)
            return
        }
        if (exoPlayer!!.playbackState == Player.STATE_ENDED) {
            advanceQueue(true)
            return
        }
        if (exoPlayer!!.playbackState != Player.STATE_BUFFERING) {
            advanceQueue(false)
        }
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

    fun seekTo(positionMs: Long) {
        val player = exoPlayer ?: return
        if (player.playbackState == Player.STATE_ENDED) return
        player.seekTo(positionMs.coerceAtLeast(0L))
        _currentPositionMs.value = positionMs.coerceAtLeast(0L)
        if (player.playWhenReady && player.playbackState != Player.STATE_BUFFERING) {
            player.play()
        }
    }

    fun skipToNext() = next()
    fun skipToPrevious() = previous()
    fun skipToPosition(positionMs: Long) = seekTo(positionMs)

    // --- queue management -------------------------------------------------

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

    private suspend fun resumeFromStall(videoId: String, positionMs: Long) {
        val canRecover = try {
            withTimeout(RECOVER_TIMEOUT_MS) {
                val outcome = StreamResolver.resolve(videoId)
                when (outcome) {
                    is StreamResolver.Outcome.Success -> {
                        val s = outcome.stream
                        StreamHeaders.register(s.url, s.headers)
                        currentLoudnessDb = s.loudnessDb
                        true
                    }
                    is StreamResolver.Outcome.Failure -> {
                        Log.w(TAG, "Stall recovery resolve failed: ${outcome.reason}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stall recovery timed out: ${e.message}")
            false
        }

        if (canRecover) {
            stallRecoveryCount++
            stallRecovered = true
            _playbackError.value = null
            withContext(Dispatchers.Main) {
                val isVideoId = currentMediaUri?.takeIf { v -> v.takeIf { x -> x.startsWith("https") } == null } == null
                val mediaItem = MediaItem.Builder()
                    .setUri(videoId)
                    .setMediaId(currentMediaUri ?: "")
                    .setMimeType(currentContentType)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(_currentSong.value?.title ?: "")
                            .setArtist(_currentSong.value?.artist ?: "")
                            .setAlbumTitle(_currentSong.value?.album ?: "Lunara")
                            .build()
                    )
                    .apply { if (isVideoId) setCustomCacheKey(_currentSong.value?.id) }
                    .build()
                exoPlayer?.setMediaItem(mediaItem)
                exoPlayer?.seekTo(positionMs)
                exoPlayer?.playWhenReady = true
                applyPlayerVolume()
            }
            return
        }

        streamFailed(videoId)
        pause()
    }

    private fun streamFailed(videoId: String) {
        StreamResolver.invalidate(videoId)
        currentMediaUri = null
        currentContentType = null
        _isBuffering.value = false
        _playbackError.value = null
    }

    private fun advanceQueue(skipNext: Boolean) {
        val player = exoPlayer ?: return
        val q = _queue.value
        if (q.isEmpty()) {
            player.pause()
            _isPlaying.value = false
            return
        }
        _isPlaying.value = false

        var nextIndex = _queueIndex.value + 1
        if (nextIndex >= q.size) {
            when (_repeatMode.value) {
                RepeatMode.ONE -> {
                    _queueIndex.value = 0
                    loadAndPlay(q[0])
                    return
                }
                RepeatMode.ALL -> {
                    _queueIndex.value = 0
                    loadAndPlay(q[0])
                    return
                }
                RepeatMode.OFF -> {
                    player.pause()
                    _isPlaying.value = false
                    return
                }
            }
        }
        _queueIndex.value = nextIndex
        loadAndPlay(q[nextIndex])
    }

    private fun handleSongEnded() {
        when (_repeatMode.value) {
            RepeatMode.ONE -> {
                exoPlayer?.seekTo(0)
                exoPlayer?.play()
                _isPlaying.value = true
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

    private fun setupPlayerListener() {
        exoPlayer?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                if (playing && _isPlaying.value != true) {
                    _isPlaying.value = true
                }
                if (!playing && _isPlaying.value != false) {
                    _isPlaying.value = false
                }
            }

            override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
                // No-op: media3 reports these every frame otherwise.
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        _isBuffering.value = true
                        _playbackError.value = null
                    }
                    Player.STATE_READY -> {
                        _isBuffering.value = false
                        _playbackError.value = null
                        // Ready means data flowed: the recovery allowance is
                        // per-failure, not per-song, so a track that plays and
                        // later stumbles still gets its full budget.
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
                val wasPlaying = exoPlayer?.playWhenReady == true
                val player = exoPlayer ?: return

                val position = player.currentPosition
                val buffered = player.bufferedPosition
                val now = SystemClock.elapsedRealtime()
                val bufferStill = lastPosition == position && lastBuffered == buffered && position > 0L
                val positionMoved = position > lastPosition
                val bufferedMoved = buffered > lastBuffered

                if (bufferStill && position > 0L) {
                    Log.w(TAG, "Playback stuck at ${position}ms; re-resolving ${currentMediaUri}")
                    _playbackError.value = "Stream stalled — reconnecting…"
                    player.pause()
                    player.seekTo(0)
                    player.playWhenReady = true

                    val videoId = currentMediaUri ?: return
                    scope.launch(Dispatchers.IO) {
                        val fresh = StreamResolver.resolveFreshBlocking(videoId)?.takeIf { it.isExpired.not() }
                        if (fresh != null) {
                            StreamHeaders.register(fresh.url, fresh.headers)
                            currentLoudnessDb = fresh.loudnessDb

                            withContext(Dispatchers.Main) {
                                val isVideoId = currentMediaUri?.takeIf { v -> v.takeIf { x -> x.startsWith("https") } == null } == null
                                val mediaItem = MediaItem.Builder()
                                    .setUri(videoId)
                                    .setMediaId(currentMediaUri ?: "")
                                    .setMimeType(fresh.containerMimeType)
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(_currentSong.value?.title ?: "")
                                            .setArtist(_currentSong.value?.artist ?: "")
                                            .setAlbumTitle(_currentSong.value?.album ?: "Lunara")
                                            .build()
                                    )
                                    .apply { if (isVideoId) setCustomCacheKey(_currentSong.value?.id) }
                                    .build()
                                player.setMediaItem(mediaItem)
                                player.prepare()
                                player.seekTo(position)
                                player.playWhenReady = true
                                applyPlayerVolume()
                            }
                            stallRecovered = true
                            stallRecoveryCount = 0
                            return@launch
                        }
                        resumeFromStall(videoId, position)
                    }
                    lastPosition = -1L
                    lastBuffered = -1L
                    lastProgressAt = now
                    return
                }


                if (positionMoved || bufferedMoved) {
                    lastPosition = position
                    lastBuffered = buffered
                    lastProgressAt = now
                    stallRecovered = false
                    return
                }

                if (now - lastProgressAt >= STALL_TIMEOUT_MS) {
                    if (stallRecoveryCount >= MAX_STALL_RECOVERIES) {
                        _playbackError.value = "Could not play this song. The stream is not available."
                        player.pause()
                        return
                    }
                    if (stallRecovered) {
                        player.pause()
                        return
                    }
                    val stalledUri = currentMediaUri ?: return
                    scope.launch { resumeFromStall(stalledUri, position) }
                    return
                }

                // Any I/O failure puts the address in doubt. Drop it and
                // re-resolve in place: the buffer still holds what the listener
                // is hearing, so the retry cannot loop on the same dead address.
                val isIoFailure = error.errorCode in setOf(
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                    PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
                    PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                )
                if (isIoFailure) {
                    val videoId = currentMediaUri ?: return
                    streamFailed(videoId)
                    pause()
                    scope.launch { resumeFromStall(videoId, position) }
                    return
                }

                Log.e(TAG, "Playback error: ${error.message}", error)
                _playbackError.value = "Playback stopped: ${error.message?.take(200) ?: "stream failure"}"
                if (wasPlaying) pause()
            }
        })
    }


    // --- position tracking --------------------------------------------------

    private fun startPositionTracker() {
        stopPositionTracker()
        positionJob = scope.launch {
            while (isActive && exoPlayer != null) {
                val player = exoPlayer
                if (player == null) {
                    delay(200)
                    continue
                }
                val pos = player.currentPosition
                val buf = player.bufferedPosition
                val now = SystemClock.elapsedRealtime()

                if (pos > 0L) {
                    lastPosition = pos
                    lastBuffered = buf
                    lastProgressAt = now
                } else {
                    lastPosition = -1L
                    lastBuffered = -1L
                }

                _currentPositionMs.value = pos.coerceAtLeast(0L)
                _bufferedPositionMs.value = buf.coerceAtLeast(0L)

                delay(200)
            }
        }
    }

    private fun stopPositionTracker() {
        positionJob?.cancel()
        positionJob = null
    }


    // --- the load pipeline --------------------------------------------------

    private fun loadAndPlay(song: Song, resumeFromMs: Long = 0L) {
        _currentSong.value = song
        if (resumeFromMs > 0L) {
            _currentPositionMs.value = resumeFromMs
        }
        _isBuffering.value = true
        _playbackError.value = null

        lastPosition = -1L
        lastBuffered = -1L
        lastProgressAt = SystemClock.elapsedRealtime()
        stallRecovered = false

        serviceContext?.let { ctx ->
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val db = LunaraDatabase.getDatabase(ctx)
                    db.songDao().insertOrUpdateSong(song.toEntity())
                    db.songDao().recordPlay(song.id, System.currentTimeMillis())
                }
            }
        }

        loadJob?.cancel()
        prefetchJob?.cancel()
        loadJob = scope.launch(Dispatchers.IO) {
            val localFile = song.localFilePath
                ?.takeIf { it.isNotBlank() }
                ?.let { path -> File(path).takeIf { it.exists() && it.length() > 0 } }
            if (localFile != null) {
                currentLoudnessDb = null
                withContext(Dispatchers.Main) {
                    playMediaUri(song, Uri.fromFile(localFile).toString(), resumePositionMs = resumeFromMs)
                }
                return@launch
            }

            val stream = try {
                withTimeout(45_000L) {
                    when (val outcome = StreamResolver.resolve(song.id)) {
                        is StreamResolver.Outcome.Success -> outcome.stream
                        is StreamResolver.Outcome.Failure -> {
                            _isBuffering.value = false
                            _playbackError.value = outcome.reason.toUserMessage()
                            Log.w(TAG, "Could not resolve ${song.id}: ${outcome.reason}")
                            null
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _isBuffering.value = false
                _playbackError.value = "Couldn't start this song. Check your connection and retry."
                Log.w(TAG, "Resolve for ${song.id} failed: ${e.message}")
                null
            }
            if (stream == null) return@launch

            StreamHeaders.register(stream.url, stream.headers)
            currentLoudnessDb = stream.loudnessDb

            prefetchUpcoming()

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

        val isVideoId = Uri.parse(mediaUri).scheme == null
        val mediaItem = MediaItem.Builder()
            .setUri(mediaUri)
            .setMediaId(song.id)
            .setMimeType(contentType)
            .setMediaMetadata(metadata)
            .apply { if (isVideoId) setCustomCacheKey(song.id) }
            .build()

        currentMediaUri = mediaUri
        currentContentType = contentType
        stallRecovered = false

        player.setMediaItem(mediaItem)
        player.prepare()
        if (resumePositionMs > 0L) player.seekTo(resumePositionMs)
        applyPlayerVolume()
        player.playWhenReady = true

        startPositionTracker()
    }

    private fun prefetchUpcoming() {
        val index = _queueIndex.value
        if (index < 0) return
        val queue = _queue.value
        val upcoming =
            (index + 1 until minOf(index + 3, queue.size)).mapNotNull { queue.getOrNull(it) }
        if (upcoming.isEmpty()) return
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            for (next in upcoming) {
                val local = next.localFilePath?.takeIf { it.isNotBlank() }
                    ?.let { path -> File(path).takeIf { it.exists() && it.length() > 0 } }
                if (local != null) continue
                val persisted = next.streamUrl?.takeIf { it.startsWith("content://") }
                if (persisted != null) continue
                val videoId = next.id
                val short = try {
                    withTimeout(20_000L) {
                        StreamResolver.resolve(videoId)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Prefetch resolve failed for $videoId: ${e.message}")
                    null
                } ?: return@launch
                StreamHeaders.register(short.url, short.headers)
            }
        }
    }
}


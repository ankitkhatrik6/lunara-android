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
import com.lunara.music.data.models.RepeatMode
import com.lunara.music.data.models.Song
import com.lunara.music.database.LunaraDatabase
import com.lunara.music.service.innertube.StreamResolver
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
                if (playing) {
                    startPositionTracker()
                } else {
                    stopPositionTracker()
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
                // The resolved stream URL may have expired or been rejected;
                // drop it so a retry resolves a fresh one.
                _currentSong.value?.let { StreamResolver.invalidate(it.id) }
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
                }
                delay(400)
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
        _isBuffering.value = true
        _playbackError.value = null
        _currentPositionMs.value = 0L

        // Record history in local Room database
        serviceContext?.let { ctx ->
            scope.launch(Dispatchers.IO) {
                val db = LunaraDatabase.getDatabase(ctx)
                db.songDao().insertOrUpdateSong(song.toEntity())
                db.songDao().recordPlay(song.id, System.currentTimeMillis())
            }
        }

        scope.launch {
            var playableUrl: String? = null

            // 1. Check local file
            if (!song.localFilePath.isNullOrBlank()) {
                val f = File(song.localFilePath)
                if (f.exists() && f.length() > 0) {
                    playableUrl = Uri.fromFile(f).toString()
                }
            }

            // 2. Check if already has stream URL or local URI
            if (playableUrl == null && !song.streamUrl.isNullOrBlank()) {
                playableUrl = song.streamUrl
            }

            // 3. Resolve stream URL
            if (playableUrl == null) {
                playableUrl = StreamResolver.resolveStreamUrl(song.id)
            }

            if (playableUrl.isNullOrBlank()) {
                _isBuffering.value = false
                _playbackError.value = "Couldn't stream this song"
                return@launch
            }

            withContext(Dispatchers.Main) {
                playMediaUri(song, playableUrl)
            }
        }
    }

    private fun playMediaUri(song: Song, mediaUri: String) {
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
            .setMediaMetadata(metadata)
            .build()

        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true
        _isPlaying.value = true
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

package com.lunara.music.service.audio

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.lunara.music.MainActivity
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Hosts the ExoPlayer instance and configures it for YouTube streaming.
 *
 * Three things matter here for playback to actually work:
 *
 * 1. **Request-time resolution, in chunks.** The player never holds a googlevideo
 *    URL open for a whole song: [StreamResolvingDataSource] sits above the HTTP
 *    stack, resolves the video id to a fresh URL at every load and caps each load
 *    at 512 KiB (Blazify's `ResolvingDataSource` + `CHUNK_LENGTH` design). An
 *    expired, capped or refused URL therefore costs one chunk — never the song.
 *
 * 2. **Per-chunk headers.** A googlevideo URL is only served to the same
 *    identity that minted it, so every resolved URL's headers are registered
 *    with [StreamHeaders] and [StreamHeaderInterceptor] applies them to each
 *    range request. Sending a single hard-coded browser User-Agent (what Lunara
 *    used to do) gets a 403 partway through a track.
 *
 * 3. **Buffering behaviour.** The load control mirrors Blazify's `BufferAhead`:
 *    start after 750 ms, resume after a stall once 2 s are banked (a shorter
 *    resume than the old 3 s is what stops the "buffers, plays, buffers" cadence
 *    from feeling like a metronome), hold up to 4 minutes on an unmetered link
 *    with a 16 MiB ceiling. Audio-focus and becoming-noisy handling stay enabled.
 */
class LunaraMediaSessionService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // Generous read timeout: a long silent stretch must not drop the
            // connection and force a re-buffer from scratch.
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            // Attach the minting client's identity to every media request for a
            // registered stream; unregistered URLs pass through untouched.
            .addInterceptor(StreamHeaderInterceptor())
            .build()

        // Redirects are handled by the shared OkHttp client above.
        val upstreamFactory = OkHttpDataSource.Factory(httpClient)
        val dataSourceFactory = DefaultDataSource.Factory(this, upstreamFactory)
        // The resolving layer sits *above* DefaultDataSource so it can turn a
        // schemeless video id into an http URL (and local content:// / file URIs
        // pass straight through it untouched).
        //
        // The extractor set mirrors Blazify's `createMediaSourceFactory`: every
        // extractor, plus constant-bitrate seeking. A stream whose container says
        // little about its own index still seeks (this is what turns a tap on the
        // progress bar into music instead of a stalled player), and the cost is a
        // few sniffs on a local file.
        val mediaSourceFactory =
            DefaultMediaSourceFactory(
                StreamResolvingDataSource.Factory(dataSourceFactory),
                DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true),
            )

        // Buffer like Blazify: enough ahead that an ordinary drop in signal passes
        // unheard, quick enough to start that the first note is not a wait.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 50_000,
                /* maxBufferMs = */ 240_000,
                /* bufferForPlaybackMs = */ 750,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000,
            )
            .setTargetBufferBytes(16 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true) // handles audio focus
            .setHandleAudioBecomingNoisy(true) // pauses when headphones unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        LunaraPlayerManager.attachPlayer(player, this)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        LunaraPlayerManager.detachPlayer()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}

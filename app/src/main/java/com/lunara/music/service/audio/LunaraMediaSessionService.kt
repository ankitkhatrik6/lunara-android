package com.lunara.music.service.audio

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.lunara.music.MainActivity
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Hosts the ExoPlayer instance and configures it for YouTube streaming.
 *
 * Two things matter here for playback to actually work:
 *
 * 1. **Per-track headers.** A googlevideo URL is only served to the same
 *    identity that minted it, so [LunaraPlayerManager] registers the resolved
 *    stream's headers with [StreamHeaders] and this data source applies them to
 *    every media request. Sending a single hard-coded browser User-Agent (what
 *    Lunara used to do) gets a 403 partway through a track.
 *
 * 2. **Buffering behaviour.** The load control is sized for progressive
 *    network audio, and audio-focus/ becoming-noisy handling stay enabled.
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
            .build()

        // Redirects are handled by the shared OkHttp client below.
        val upstreamFactory = OkHttpDataSource.Factory(httpClient)
        val headerAware = HeaderAwareDataSource.Factory(upstreamFactory)

        // Wrap with DefaultDataSource so local (file:// and content://) tracks
        // from the device library keep working alongside the HTTP streams.
        val dataSourceFactory = DefaultDataSource.Factory(this, headerAware)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        // Buffer generously: buffering is what keeps a variable connection from
        // stalling mid-track.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 120_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000,
            )
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

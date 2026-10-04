package com.lunara.music.service.audio

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.lunara.music.MainActivity
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class LunaraMediaSessionService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // YouTube audio streams are served from googleusercontent/overture hosts
        // that expect a browser-like User-Agent; OkHttp also gives us connection
        // reuse and proper timeouts for streaming.
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val upstreamFactory = OkHttpDataSource.Factory(httpClient)
            .setUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            )
            .setDefaultRequestProperties(mapOf("Referer" to "https://music.youtube.com/"))

        // Wrap with DefaultDataSource so local (file:// and content://) tracks
        // from the device library keep working alongside the HTTP streams.
        val dataSourceFactory = DefaultDataSource.Factory(this, upstreamFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true) // handles audio focus
            .setHandleAudioBecomingNoisy(true) // pauses when headphones unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK)
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        LunaraPlayerManager.detachPlayer()
        super.onDestroy()
    }
}

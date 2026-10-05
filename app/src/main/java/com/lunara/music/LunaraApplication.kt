package com.lunara.music

import android.app.Application
import android.util.Log
import com.lunara.extractor.StreamResolver
import com.lunara.music.service.audio.LunaraPlayerManager
import com.lunara.music.service.innertube.YouTubeSession
import com.lunara.music.service.lyrics.LyricsService

class LunaraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            YouTubeSession.init(this)
            // Warms the visitor id and the BotGuard token generator together. Both are
            // slow on a cold start and both are needed by the first song, so paying for
            // them here is what makes that song start promptly rather than after a
            // visible pause while the WebView spins up.
            StreamResolver.init(this)
            LyricsService.init(this)
            LunaraPlayerManager.init(this)
            Log.d("LunaraApplication", "Lunara initialized successfully")
        } catch (e: Exception) {
            Log.e("LunaraApplication", "Error during app initialization: ${e.message}", e)
        }
    }
}

package com.lunara.music

import android.app.Application
import android.util.Log
import com.lunara.extractor.StreamResolver
import com.lunara.music.data.models.streamQualityFor
import com.lunara.music.database.LunaraDatabase
import com.lunara.music.service.audio.LunaraPlayerManager
import com.lunara.music.service.innertube.YouTubeSession
import com.lunara.music.service.lyrics.LyricsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
            applyStoredPlaybackPreferences()
            Log.d("LunaraApplication", "Lunara initialized successfully")
        } catch (e: Exception) {
            Log.e("LunaraApplication", "Error during app initialization: ${e.message}", e)
        }
    }

    /**
     * Reads the playback preferences the settings screen persists, so the first song
     * after a launch plays by the user's last choices rather than by built-in
     * defaults: stream selection quality and volume normalization. Off the main
     * thread, and never fatal — a database that cannot be read must not stop the app
     * from starting, the defaults simply stand.
     */
    private fun applyStoredPlaybackPreferences() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val prefs = LunaraDatabase.getDatabase(this@LunaraApplication).userPrefDao()
                StreamResolver.setPreferredQuality(streamQualityFor(prefs.getPref("audio_quality")))
                // Absent means never toggled: normalization ships on, which is how
                // InnerTune ships it, and can be turned off in one tap.
                LunaraPlayerManager.setNormalizationEnabled(prefs.getPref("normalize_audio") != "false")
            }.onFailure {
                Log.w("LunaraApplication", "Could not apply stored playback preferences: ${it.message}")
            }
        }
    }
}

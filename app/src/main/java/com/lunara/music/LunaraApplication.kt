package com.lunara.music

import android.app.Application
import android.util.Log
import com.lunara.music.service.audio.LunaraPlayerManager
import com.lunara.music.service.innertube.StreamResolver

class LunaraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            StreamResolver.init()
            LunaraPlayerManager.init(this)
            Log.d("LunaraApplication", "Lunara initialized successfully")
        } catch (e: Exception) {
            Log.e("LunaraApplication", "Error during app initialization: ${e.message}", e)
        }
    }
}

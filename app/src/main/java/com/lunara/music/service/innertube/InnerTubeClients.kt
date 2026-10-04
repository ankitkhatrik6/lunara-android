package com.lunara.music.service.innertube

/**
 * Minimal InnerTube client descriptor plus the client identities Lunara uses to
 * obtain playable audio streams.
 *
 * These are the same public client identities the Blazify project relies on.
 * Clients that return direct audio URLs (no signature cipher and no PoToken)
 * are tried first, which keeps playback working without a JavaScript engine.
 */
data class InnerTubeClient(
    val clientName: String,
    val clientVersion: String,
    val clientId: String,
    val userAgent: String,
    val apiKey: String,
    val osName: String? = null,
    val osVersion: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val androidSdkVersion: String? = null,
    val buildId: String? = null,
    val isEmbedded: Boolean = false,
) {
    fun toClientContext(visitorData: String?, hl: String, gl: String): org.json.JSONObject =
        org.json.JSONObject().apply {
            put("clientName", clientName)
            put("clientVersion", clientVersion)
            osName?.let { put("osName", it) }
            osVersion?.let { put("osVersion", it) }
            deviceMake?.let { put("deviceMake", it) }
            deviceModel?.let { put("deviceModel", it) }
            androidSdkVersion?.let { put("androidSdkVersion", it) }
            buildId?.let { put("buildId", it) }
            put("hl", hl)
            put("gl", gl)
            if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
        }
}

object InnerTubeClients {
    const val USER_AGENT_WEB =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    // Public API keys, one per client family.
    const val KEY_ANDROID = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"
    const val KEY_IOS = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc"
    const val KEY_WEB = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    const val KEY_WEB_REMIX = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX3"

    /** Video used only to warm the stream pipeline; never played. */
    const val WARMUP_VIDEO_ID = "dQw4w9WgXcQ"

    /**
     * Tried in order. The first four return direct (non-ciphered) audio URLs on
     * the current catalogue, so they need no JavaScript deobfuscation. WEB_REMIX
     * is kept last as a best effort fallback.
     */
    val STREAM_CLIENTS: List<InnerTubeClient> = listOf(
        InnerTubeClient(
            clientName = "ANDROID_VR",
            clientVersion = "1.43.32",
            clientId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.43.32 " +
                "(Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/107.0.5284.2)",
            apiKey = KEY_ANDROID,
            osName = "Android",
            osVersion = "12",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = "32",
            buildId = "SQ3A.220605.009.A1",
        ),
        InnerTubeClient(
            clientName = "IOS",
            clientVersion = "21.03.1",
            clientId = "5",
            userAgent = "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)",
            apiKey = KEY_IOS,
            osName = "iOS",
            osVersion = "18.2.22C152",
        ),
        InnerTubeClient(
            clientName = "ANDROID_CREATOR",
            clientVersion = "25.03.101",
            clientId = "14",
            userAgent = "com.google.android.apps.youtube.creator/25.03.101 " +
                "(Linux; U; Android 15; en_US; Pixel 9 Pro Fold; Build/AP3A.241005.015.A2; Cronet/132.0.6779.0)",
            apiKey = KEY_ANDROID,
            osName = "Android",
            osVersion = "15",
            deviceMake = "Google",
            deviceModel = "Pixel 9 Pro Fold",
            androidSdkVersion = "35",
            buildId = "AP3A.241005.015.A2",
        ),
        InnerTubeClient(
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientVersion = "2.0",
            clientId = "85",
            userAgent = "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/15.4 Safari/605.1.15",
            apiKey = KEY_WEB,
            isEmbedded = true,
        ),
        InnerTubeClient(
            clientName = "WEB_REMIX",
            clientVersion = "1.20260213.01.00",
            clientId = "67",
            userAgent = USER_AGENT_WEB,
            apiKey = KEY_WEB_REMIX,
        ),
    )
}
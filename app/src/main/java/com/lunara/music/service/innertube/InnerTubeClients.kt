package com.lunara.music.service.innertube

/**
 * An InnerTube client identity plus the exact HTTP request identity needed to
 * both mint a stream URL and later download from it.
 *
 * BlazifyExtractor keeps the client identity together with the request it has to
 * sign, because a googlevideo URL is only served to the same identity that
 * minted it. Splitting those apart is what makes a player stall.
 */
data class InnerTubeClient(
    val clientName: String,
    /** Numeric id required by the `X-YouTube-Client-Name` header. */
    val clientId: String,
    val clientVersion: String,
    val userAgent: String,
    val apiKey: String,
    val origin: String,
    val referer: String,
    val osName: String? = null,
    val osVersion: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val androidSdkVersion: String? = null,
    val buildId: String? = null,
    /**
     * True when YouTube throttles this client's Google Video Server (media)
     * requests to ~1 MiB unless a PO token is attached. Measured against the
     * live API: iOS media is capped, Android VR media is not.
     */
    val gvsRequiresPoToken: Boolean = false,
) {
    val baseUrl: String get() = origin

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

    /**
     * Headers the media CDN expects for a URL minted by this client. ExoPlayer
     * must send these verbatim; sending a browser User-Agent for an iOS-minted
     * URL is a reliable way to get a 403 halfway through a track.
     */
    fun mediaHeaders(): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        put("Referer", referer)
        put("Origin", origin)
        val visitor = YouTubeSession.visitorData
        if (!visitor.isNullOrBlank()) put("X-Goog-Visitor-Id", visitor)
    }
}

object InnerTubeClients {
    const val USER_AGENT_WEB =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"

    const val MUSIC_BASE = "https://music.youtube.com"
    const val YOUTUBE_BASE = "https://www.youtube.com"

    // Public API keys, one per client family.
    const val KEY_ANDROID = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"
    const val KEY_IOS = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc"
    const val KEY_WEB = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    const val KEY_WEB_REMIX = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX3"

    /** Video used only to warm the stream pipeline; never played. */
    const val WARMUP_VIDEO_ID = "dQw4w9WgXcQ"

    /**
     * Rotation order, rebuilt from live measurements rather than guesswork.
     *
     * ANDROID_VR leads because its media URLs are *not* subject to the 1 MiB
     * GVS throttle (yt-dlp's PO-Token Guide lists android_vr as "not
     * required"). iOS is a proven fallback but its media is capped without a PO
     * token, so it is flagged and every URL it produces is deep-validated.
     *
     * Clients that now answer LOGIN_REQUIRED / UNPLAYABLE for every catalogue
     * track (ANDROID_CREATOR, TVHTML5 at v2.0, WEB_REMIX on www) are
     * intentionally absent: keeping them only added four failed round trips to
     * every single play, which is what made the app look "stuck".
     */
    val STREAM_CLIENTS: List<InnerTubeClient> = listOf(
        InnerTubeClient(
            clientName = "ANDROID_VR",
            clientId = "28",
            clientVersion = "1.43.32",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.43.32 " +
                "(Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/107.0.5284.2)",
            apiKey = KEY_ANDROID,
            origin = YOUTUBE_BASE,
            referer = "$YOUTUBE_BASE/",
            osName = "Android",
            osVersion = "12",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = "32",
            buildId = "SQ3A.220605.009.A1",
        ),
        InnerTubeClient(
            clientName = "IOS",
            clientId = "5",
            clientVersion = "21.03.1",
            userAgent = "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)",
            apiKey = KEY_IOS,
            origin = YOUTUBE_BASE,
            referer = "$YOUTUBE_BASE/",
            osName = "iOS",
            osVersion = "18.2.22C152",
            gvsRequiresPoToken = true,
        ),
        InnerTubeClient(
            clientName = "ANDROID",
            clientId = "3",
            clientVersion = "20.10.38",
            userAgent = "com.google.android.youtube/20.10.38 " +
                "(Linux; U; Android 14; en_US; Pixel 8 Build/AP2A.240405.002; Cronet/130.0.6779.0) gzip",
            apiKey = KEY_ANDROID,
            origin = YOUTUBE_BASE,
            referer = "$YOUTUBE_BASE/",
            osName = "Android",
            osVersion = "14",
            androidSdkVersion = "34",
            gvsRequiresPoToken = true,
        ),
        InnerTubeClient(
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientId = "85",
            clientVersion = "7.20250312.18.00",
            userAgent = "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/15.4 Safari/605.1.15",
            apiKey = KEY_WEB,
            origin = YOUTUBE_BASE,
            referer = "$YOUTUBE_BASE/",
        ),
        InnerTubeClient(
            clientName = "WEB_EMBEDDED_PLAYER",
            clientId = "56",
            clientVersion = "1.20250310.01.00",
            userAgent = USER_AGENT_WEB,
            apiKey = KEY_WEB,
            origin = YOUTUBE_BASE,
            referer = "$YOUTUBE_BASE/",
            gvsRequiresPoToken = true,
        ),
    )
}
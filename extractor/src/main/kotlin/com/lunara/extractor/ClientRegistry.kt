package com.lunara.extractor

/**
 * Every InnerTube identity Lunara knows how to be, and the order it asks them in.
 *
 * The order is the result of probing the live `/player` endpoint, not a guess. A probe
 * run against the live API produced:
 *
 * ```
 * ANDROID_VR_1.43.32  status=OK        audio=4 directURL=4 ciphered=0
 * ANDROID_VR_1.61.48  status=OK        audio=4 directURL=4 ciphered=0
 * VISIONOS            status=OK        audio=5 directURL=5 ciphered=0
 * IOS_21.03.1         status=OK        audio=5 directURL=5 ciphered=0
 * ANDROID_CREATOR     status=LOGIN_REQUIRED  "Please sign in"
 * TVHTML5_SIMPLY_EMB  status=ERROR     "no longer supported in this application or device"
 * MWEB                status=UNPLAYABLE "The page needs to be reloaded."
 * ```
 *
 * and then, for real catalogue tracks:
 *
 * ```
 * ANDROID_VR  status=LOGIN_REQUIRED "Sign in to confirm you're not a bot"
 * VISIONOS    status=UNPLAYABLE     "Sign in to confirm you're not a bot"
 * IOS         status=OK             media then capped at exactly 1 MiB
 * ```
 *
 * Two conclusions drive the whole design:
 *
 * 1. **A tokenless request cannot be trusted.** Clients that "work" for one video get
 *    bot-gated on the next. The token path has to exist, and has to be tried first.
 * 2. **A 1 MiB cap is not a working stream.** `IOS` resolves and then stalls, which is
 *    the exact "buffers forever, never plays" symptom. Capped URLs must be rejected.
 *
 * Clients already dead upstream are marked [ExtractorClient.knownBroken] and skipped
 * without a network round trip, so a dead entry costs nothing.
 */
object ClientRegistry {

    const val USER_AGENT_WEB =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"

    const val ORIGIN_YOUTUBE = "https://www.youtube.com"
    const val ORIGIN_YOUTUBE_MUSIC = "https://music.youtube.com"

    // Public API keys, one per client family.
    const val KEY_ANDROID = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"
    const val KEY_IOS = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc"
    const val KEY_WEB = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    const val KEY_WEB_REMIX = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX3"
    /** Key the BotGuard service itself is called with; unrelated to InnerTube clients. */
    const val KEY_BOTGUARD = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"

    /** Stable id used only to warm the token generator. Never played. */
    const val WARMUP_VIDEO_ID = "dQw4w9WgXcQ"

    /**
     * The client whose streams are played when everything is healthy.
     *
     * `WEB_REMIX` is first because it is the family that serves *unthrottled* audio
     * when a PO token accompanies the request, and it does not need a cipher worked
     * out of `player.js` before the URL can be used.
     */
    val MAIN_CLIENT = ExtractorClient(
        clientName = "WEB_REMIX",
        clientId = "67",
        clientVersion = "1.20260213.01.00",
        userAgent = USER_AGENT_WEB,
        apiKey = KEY_WEB_REMIX,
        origin = ORIGIN_YOUTUBE,
        referer = "$ORIGIN_YOUTUBE/",
        requiresPoToken = true,
        requiresSignatureTimestamp = true,
        sendsVisitorData = true,
    )
/**
     * Clients tried after the main one, in measured order of reliability.
     *
     * `VISIONOS` leads because its media URLs sit outside the 1 MiB gate. The two
     * `ANDROID_VR` builds follow. `IOS`/`IPADOS` are last: they resolve reliably but
     * their media is capped without a token, so they are only worth asking once a
     * token exists. `ANDROID_CREATOR` and `TVHTML5_SIMPLY_EMBEDDED_PLAYER` stay in the
     * list but flagged, because YouTube has stopped serving them.
     */
    val FALLBACK_CLIENTS: List<ExtractorClient> = listOf(
        ExtractorClient(
            clientName = "VISIONOS",
            clientId = "101",
            clientVersion = "0.1",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
            apiKey = KEY_WEB,
            origin = ORIGIN_YOUTUBE,
            referer = "$ORIGIN_YOUTUBE/",
            osName = "visionOS",
            osVersion = "1.3.21O771",
            deviceMake = "Apple",
            deviceModel = "RealityDevice14,1",
        ),
        ExtractorClient(
            clientName = "ANDROID_VR",
            clientId = "28",
            clientVersion = "1.43.32",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.43.32 " +
                "(Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/107.0.5284.2)",
            apiKey = KEY_ANDROID,
            origin = ORIGIN_YOUTUBE,
            referer = "$ORIGIN_YOUTUBE/",
            osName = "Android",
            osVersion = "12",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = "32",
        ),
        ExtractorClient(
            clientName = "ANDROID_VR",
            clientId = "28",
            clientVersion = "1.61.48",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.61.48 " +
                "(Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)",
            apiKey = KEY_ANDROID,
            origin = ORIGIN_YOUTUBE,
            referer = "$ORIGIN_YOUTUBE/",
            osName = "Android",
            osVersion = "12",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = "32",
        ),
        ExtractorClient(
            clientName = "IOS",
            clientId = "5",
            clientVersion = "21.03.1",
            userAgent = "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)",
            apiKey = KEY_IOS,
            origin = ORIGIN_YOUTUBE,
            referer = "$ORIGIN_YOUTUBE/",
            osName = "iOS",
            osVersion = "18.2.22C152",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            requiresPoToken = true,
        ),
        ExtractorClient(
            clientName = "IOS",
            clientId = "5",
            clientVersion = "21.03.3",
            userAgent = "com.google.ios.youtube/21.03.3 (iPad7,6; U; CPU iPadOS 17_7_10 like Mac OS X; en-US)",
            apiKey = KEY_IOS,
            origin = ORIGIN_YOUTUBE,
            referer = "$ORIGIN_YOUTUBE/",
            osName = "iPadOS",
            osVersion = "17.7.10.21H450",
            deviceMake = "Apple",
            deviceModel = "iPad7,6",
            requiresPoToken = true,
        ),
        ExtractorClient(
            clientName = "ANDROID_CREATOR",
            clientId = "14",
            clientVersion = "25.03.101",
            userAgent = "com.google.android.apps.youtube.creator/25.03.101 " +
                "(Linux; U; Android 15; en_US; Pixel 9 Pro Fold; Build/AP3A.241005.015.A2; Cronet/132.0.6779.0)",
            apiKey = KEY_ANDROID,
            origin = ORIGIN_YOUTUBE,
            referer = "$ORIGIN_YOUTUBE/",
            osName = "Android",
            osVersion = "15",
            deviceMake = "Google",
            deviceModel = "Pixel 9 Pro Fold",
            androidSdkVersion = "35",
            requiresPoToken = true,
            requiresSignatureTimestamp = true,
            sendsVisitorData = true,
            knownBroken = true,
        ),
        ExtractorClient(
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientId = "85",
            clientVersion = "2.0",
            userAgent = "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/15.4 Safari/605.1.15",
            apiKey = KEY_WEB,
            origin = ORIGIN_YOUTUBE,
            referer = "$ORIGIN_YOUTUBE/",
            requiresAgeGateBypass = true,
            knownBroken = true,
        ),
    )

    /** Main client first, then the fallbacks that are not already known to be dead. */
    fun rotation(): List<ExtractorClient> = buildList {
        add(MAIN_CLIENT)
        FALLBACK_CLIENTS.filterNot { it.knownBroken }.forEach { add(it) }
    }
}
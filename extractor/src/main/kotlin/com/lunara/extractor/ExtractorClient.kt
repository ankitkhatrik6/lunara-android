package com.lunara.extractor

/**
 * One InnerTube client identity, plus everything needed to *use* the stream it mints.
 *
 * A googlevideo URL is only served to the same identity that produced it. That is not
 * a nicety: requesting an iOS-minted URL with a browser User-Agent gets a `403`, and a
 * URL requested with the wrong identity but a lucky first byte gets throttled to 1 MiB.
 * So the identity, the API key, the context fields and the media headers all travel
 * together in one value and are never split apart.
 *
 * Measured behaviour (live probe of `/youtubei/v1/player`, this repository):
 * - `ANDROID_VR`, `VISIONOS`, `ANDROID_CREATOR`, `TVHTML5_SIMPLY_EMBEDDED_PLAYER`
 *   now answer `LOGIN_REQUIRED` / "no longer supported" without a PO token.
 * - `IOS` resolves to an unciphered URL, but the media server caps it at 1 MiB unless
 *   a BotGuard PO token accompanies the request.
 * - `WEB_REMIX` refuses outright without a PO token and a signature timestamp.
 *
 * Which is why [requiresPoToken] and [requiresSignatureTimestamp] exist: the resolver
 * uses them to decide the order clients are tried in, so the ones that can only ever
 * work with a token are never asked first and fail slowly.
 */
data class ExtractorClient(
    val clientName: String,
    /** Numeric id required by the `X-YouTube-Client-Name` header and by some responses. */
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
    /** YouTube refuses this client unless a BotGuard PO token is attached. */
    val requiresPoToken: Boolean = false,
    /** The response is only usable when asked with a `signatureTimestamp`. */
    val requiresSignatureTimestamp: Boolean = false,
    /** Only usable by a signed-in session; skipped entirely when logged out. */
    val loginRequired: Boolean = false,
    /** Sends `X-Goog-Visitor-Id`, which some client families insist on seeing. */
    val sendsVisitorData: Boolean = false,
    /** Content is only served with a `contentCheckOk`/`racyCheckOk` acknowledgement. */
    val requiresAgeGateBypass: Boolean = false,
    /**
     * Disabled by default because a live probe showed YouTube has stopped honouring
     * it. Kept so it can be re-enabled the day that changes, and so the failure is a
     * recorded fact rather than a guess.
     */
    val knownBroken: Boolean = false,
) {
    val displayName: String get() = "$clientName $clientVersion"

    /** The `context.client` object sent with every InnerTube request. */
    fun toClientContext(visitorData: String?, hl: String, gl: String): String = buildString {
        append("{")
        append("\"clientName\":").append(json(clientName)).append(',')
        append("\"clientVersion\":").append(json(clientVersion)).append(',')
        if (osName != null) append("\"osName\":").append(json(osName)).append(',')
        if (osVersion != null) append("\"osVersion\":").append(json(osVersion)).append(',')
        if (deviceMake != null) append("\"deviceMake\":").append(json(deviceMake)).append(',')
        if (deviceModel != null) append("\"deviceModel\":").append(json(deviceModel)).append(',')
        if (androidSdkVersion != null) append("\"androidSdkVersion\":").append(json(androidSdkVersion)).append(',')
        append("\"hl\":").append(json(hl)).append(',')
        append("\"gl\":").append(json(gl))
        if (!visitorData.isNullOrBlank() && sendsVisitorData) {
            append(",\"visitorData\":").append(json(visitorData))
        }
        append("}")
    }

    /**
     * Headers the media CDN requires for a URL minted by this client.
     *
     * ExoPlayer must send these verbatim. This is why the resolved stream carries its
     * own headers rather than a single hard-coded User-Agent for everything.
     */
    fun mediaHeaders(visitorData: String? = null): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        put("Origin", origin)
        put("Referer", referer)
        if (!visitorData.isNullOrBlank() && sendsVisitorData) {
            put("X-Goog-Visitor-Id", visitorData)
        }
    }

    private fun json(value: String): String {
        val sb = StringBuilder(value.length + 8)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
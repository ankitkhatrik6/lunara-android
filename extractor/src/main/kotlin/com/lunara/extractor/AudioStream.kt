package com.lunara.extractor

import android.net.Uri

/**
 * A resolved audio stream, carrying everything needed to actually play it.
 *
 * More than a bare URL on purpose. A googlevideo URL is only served to the identity
 * that minted it, so the URL, the headers that identity requires, the container type
 * and the expiry have to travel as one value. Splitting them is what produces streams
 * that resolve cleanly and then stall halfway through.
 */
data class AudioStream(
    val url: String,
    val itag: Int,
    val mimeType: String,
    val bitrate: Int,
    val contentLength: Long,
    /** Which client minted this, and therefore which identity must request it. */
    val clientName: String,
    val headers: Map<String, String>,
    /** Epoch millis after which the CDN is guaranteed to refuse this URL. */
    val expiresAtMs: Long,
    val approxDurationMs: Long = 0L,
    val audioQuality: String? = null,
    val audioChannels: Int = 2,
    val isLiveStream: Boolean = false,
    /**
     * The raw `signatureCipher`/`cipher` payload when the response withheld the URL.
     *
     * A ciphered format travels unresolved on purpose: unscrambling it means running
     * the site's own player script, which lives in the resolver rather than in the
     * layer that merely reports what `/player` said. Null once resolved.
     */
    val signatureCipher: String? = null,
) {
    val isExpired: Boolean
        get() = expiresAtMs > 0L && expiresAtMs <= System.currentTimeMillis()

    /**
     * `audio/mp4` or `audio/webm`, which is what ExoPlayer needs to choose between its
     * MP4 and Matroska extractors. Guessing wrong here means a URL that works being
     * reported as an unsupported format, so the raw mime type is never passed through
     * with its codec parameters attached.
     */
    val containerMimeType: String
        get() = when {
            mimeType.startsWith("audio/webm", ignoreCase = true) -> "audio/webm"
            mimeType.startsWith("audio/mp4", ignoreCase = true) -> "audio/mp4"
            mimeType.startsWith("audio/ogg", ignoreCase = true) -> "audio/ogg"
            mimeType.isNotBlank() -> mimeType.substringBefore(';').trim()
            else -> "audio/mp4"
        }

    val isOpus: Boolean
        get() = mimeType.contains("opus", ignoreCase = true)

    /**
     * Whether the CDN will still be serving this when the player asks.
     *
     * Five minutes of margin, because a URL handed out at the exact moment it dies is
     * a URL that resolves and then stalls.
     */
    fun withSafeLifetime(): AudioStream =
        if (expiresAtMs <= 0L) this
        else copy(expiresAtMs = expiresAtMs - EXPIRY_SAFETY_MS)

    companion object {
        private const val EXPIRY_SAFETY_MS = 5 * 60 * 1000L

        /** googlevideo embeds its expiry as unix seconds in an `expire` parameter. */
        fun expiryFromUrl(url: String): Long {
            val raw = runCatching { Uri.parse(url).getQueryParameter("expire") }.getOrNull()
                ?: return 0L
            val seconds = raw.toLongOrNull() ?: return 0L
            // A sane googlevideo expiry is a unix timestamp well past 2020. Anything
            // smaller is not one, and treating it as an instant would expire every
            // stream immediately.
            return if (seconds > 1_600_000_000L) seconds * 1000L else 0L
        }
    }
}

/** How hard the extractor should work to find a stream. */
enum class StreamQuality {
    /** Lowest bitrate that still sounds acceptable. Saves data. */
    LOW,

    /** Best available, up to ~256 kbps Opus. */
    HIGH,

    /** Best that suits the current connection. */
    AUTO,
}

/**
 * Why a resolve produced nothing.
 *
 * Distinguishing these matters to the user: "this track is not available" and "the
 * network is being awkward" are different problems, and showing one message for both
 * is how a broken app looks like a working one that found no music.
 */
sealed class ResolveFailure {
    /** YouTube itself refused the track. Trying again will not help. */
    data class Unavailable(val reason: String) : ResolveFailure()

    /** Every client was refused, most likely a bot check or an expired identity. */
    data object Blocked : ResolveFailure()

    /** Clients answered, but every URL they produced was capped or dead. */
    data object NoPlayableStream : ResolveFailure()

    /** The network never produced an answer. */
    data class Network(val detail: String) : ResolveFailure()
}
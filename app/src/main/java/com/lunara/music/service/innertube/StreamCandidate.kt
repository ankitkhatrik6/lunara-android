package com.lunara.music.service.innertube

import android.net.Uri

/**
 * A fully resolved, ready-to-play YouTube audio stream.
 *
 * BlazifyExtractor hands playback more than a bare URL: the stream identity
 * (which InnerTube client minted it), the HTTP headers that identity requires,
 * the mime type (so ExoPlayer picks the right extractor) and the expiry taken
 * from the googlevideo `expire` parameter. Carrying all of that together is what
 * stops playback from silently degrading into an endless buffer.
 */
data class StreamCandidate(
    val url: String,
    val mimeType: String,
    val bitrate: Int,
    val contentLength: Long,
    val clientName: String,
    val headers: Map<String, String>,
    /** Epoch millis after which this URL is guaranteed to be rejected. */
    val expiresAtMs: Long,
    /** Where this candidate came from: "innertube" or a mirror host. */
    val source: String,
) {
    val isExpired: Boolean
        get() = expiresAtMs > 0L && expiresAtMs <= System.currentTimeMillis()

    /**
     * `video/webm` or `audio/mp4` — ExoPlayer uses the container to choose
     * between its Matroska and MP4 extractors. Getting this right matters for
     * Opus/WebM, which ExoPlayer will not sniff out of a bare URL reliably.
     */
    val contentType: String
        get() = when {
            mimeType.startsWith("audio/webm") -> "audio/webm"
            mimeType.startsWith("audio/mp4") -> "audio/mp4"
            mimeType.startsWith("audio/ogg") -> "audio/ogg"
            mimeType.isNotBlank() -> mimeType.substringBefore(';').trim()
            else -> "audio/mp4"
        }

    /** Refresh a little before the real deadline so we never hand out a dead URL. */
    fun withSafeLifetime(): StreamCandidate =
        if (expiresAtMs <= 0L) this
        else copy(expiresAtMs = expiresAtMs - EXPIRY_SAFETY_MS)

    companion object {
        private const val EXPIRY_SAFETY_MS = 5 * 60 * 1000L

        /** googlevideo embeds the expiry as a unix-seconds `expire` parameter. */
        fun expiryFromUrl(url: String): Long {
            val raw = runCatching {
                Uri.parse(url).getQueryParameter("expire")
            }.getOrNull() ?: return 0L
            val seconds = raw.toLongOrNull() ?: return 0L
            return if (seconds > 1_000_000_000L) seconds * 1000L else 0L
        }
    }
}
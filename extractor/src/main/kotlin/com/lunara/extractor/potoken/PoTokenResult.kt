package com.lunara.extractor.potoken

/**
 * Raised when the BotGuard VM could not be driven.
 *
 * Distinct from [BadWebViewException] on purpose. A challenge or program that fails is
 * transient (YouTube rotated something); a WebView whose *own* bootstrap JavaScript
 * throws is permanently broken for the session, and retrying it every track would cost
 * seconds each time for a result that can never change.
 */
class PoTokenException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The WebView implementation itself is unusable; tokens are off for this session. */
class BadWebViewException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The two tokens one resolve needs.
 *
 * @param playerRequestPoToken session-scoped, sent as `serviceIntegrityDimensions.poToken`
 *   so the catalogue will answer at all.
 * @param streamingDataPoToken track-scoped, folded into the media path so the content
 *   server serves the whole file rather than the first megabyte.
 */
data class PoTokenResult(
    val playerRequestPoToken: String,
    val streamingDataPoToken: String,
)
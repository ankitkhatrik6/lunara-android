package com.lunara.extractor

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The anonymous identity this app presents to InnerTube.
 *
 * A visitor id is not decoration. Requests without one are answered
 * `LOGIN_REQUIRED`, and requests carrying a *stale* one are refused just as firmly as
 * requests carrying none — which is why an identity that stops working has to be
 * replaced rather than retried. A user whose session quietly expired sees "Video
 * unavailable" on a library that played yesterday, and reinstalling fixes it only
 * because it throws the old identity away.
 */
object SessionStore {

    private const val TAG = "LunaraSession"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val lock = Mutex()

    @Volatile
    private var visitorData: String? = null

    @Volatile
    private var lastRenewedAt = 0L

    /**
     * How often the identity may be replaced.
     *
     * Rate limited because a machine that cannot reach the catalogue at all would
     * otherwise mint identities as fast as a user taps play, which is indistinguishable
     * from a bot and gets the whole app blocked faster.
     */
    private const val RENEW_COOLDOWN_MS = 3 * 60 * 1000L

    /** Called when the identity changes, so a host app can persist it. */
    @Volatile
    var onIdentityRenewed: ((String) -> Unit)? = null

    fun current(): String? = visitorData

    /** Returns a visitor id, minting one if needed. */
    suspend fun ensure(): String? {
        visitorData?.takeIf { it.isNotBlank() }?.let { return it }
        return lock.withLock {
            visitorData?.takeIf { it.isNotBlank() }?.let { return@withLock it }
            mint().also { visitorData = it }
        }
    }

    /**
     * Replaces the identity when it looks stale.
     *
     * Returns true when a new one was minted. Call this when every client has failed at
     * once: that pattern means the session, not the track. Retrying without renewing
     * repeats the same refusal for every remaining client.
     */
    suspend fun renewIfStale(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastRenewedAt < RENEW_COOLDOWN_MS) return false
        lastRenewedAt = now

        val fresh = mint() ?: return false
        Log.i(TAG, "Replaced a stale session identity")
        visitorData = fresh
        runCatching { onIdentityRenewed?.invoke(fresh) }
        return true
    }

    /**
     * Fetches a visitor id from the live page.
     *
     * Read out of the served HTML rather than asked for: InnerTube does not expose an
     * endpoint for it, and the value embedded in the page is the one the page itself
     * would use, which is what keeps the session self-consistent.
     */
    private suspend fun mint(): String? = withContext(Dispatchers.IO) {
        val candidates = listOf(
            ClientRegistry.ORIGIN_YOUTUBE_MUSIC to ClientRegistry.USER_AGENT_WEB,
            ClientRegistry.ORIGIN_YOUTUBE to ClientRegistry.USER_AGENT_WEB,
        )

        for ((origin, userAgent) in candidates) {
            val extracted = runCatching {
                val request = Request.Builder()
                    .url("$origin/")
                    .header("User-Agent", userAgent)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.string()?.let(::extractVisitorData)
                }
            }.getOrElse {
                Log.d(TAG, "Could not read a visitor id from $origin: ${it.message}")
                null
            }

            if (!extracted.isNullOrBlank()) {
                Log.d(TAG, "Minted a visitor id from $origin")
                return@withContext extracted
            }
        }
        null
    }

    /**
     * Pulls the visitor id out of a page's `ytcfg` blob.
     *
     * Matches only a well-formed base64-ish token. The page embeds several `null` and
     * `undefined` literals in the same neighbourhood, and accepting one of those would
     * produce an identity that is worse than none at all — an empty identity header is
     * answered with a refusal, whereas no header lets the server mint its own.
     */
    private fun extractVisitorData(html: String): String? {
        val marker = "\"visitorData\":\""
        var index = html.indexOf(marker)
        while (index != -1) {
            val start = index + marker.length
            val end = html.indexOf('"', start)
            if (end > start) {
                val candidate = html.substring(start, end)
                if (candidate.length > 20 && candidate.none { it.isWhitespace() } &&
                    candidate != "null" && candidate != "undefined"
                ) {
                    return candidate
                }
            }
            index = html.indexOf(marker, start)
        }
        return null
    }
}
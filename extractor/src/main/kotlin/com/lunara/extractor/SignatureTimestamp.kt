package com.lunara.extractor

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Reads the `sts` (signature timestamp) value out of the currently deployed player
 * script.
 *
 * Several client families will only return a usable `streamingData` block when the
 * request carries a `signatureTimestamp`. YouTube stamps it into the number
 * `player.js` is versioned by, so it is read from the same page that serves the script
 * rather than being cached indefinitely — a value cached past a rotation is worse than
 * no value, because the request looks legitimate and is refused for a reason that is
 * not visible in the response.
 */
object SignatureTimestamp {

    private const val TAG = "LunaraSignatureTs"
    private const val IFRAME_API = "https://www.youtube.com/iframe_api"
    private const val TTL_MS = 6 * 60 * 60 * 1000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cached: Pair<Int, Long>? = null

    /** Returns a fresh-enough signature timestamp, or null when one cannot be read. */
    suspend fun get(): Int? = withContext(Dispatchers.IO) {
        cached?.let { (value, fetchedAt) ->
            if (System.currentTimeMillis() - fetchedAt < TTL_MS) return@withContext value
        }

        val html = runCatching {
            val request = Request.Builder()
                .url(IFRAME_API)
                .header("User-Agent", ClientRegistry.USER_AGENT_WEB)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.getOrElse {
            Log.d(TAG, "Could not reach the iframe API: ${it.message}")
            null
        } ?: return@withContext null

        val stamp = extract(html)
        if (stamp != null) cached = stamp to System.currentTimeMillis()
        stamp
    }

    /**
     * Finds `sts` in the page.
     *
     * The page assigns it as `player_response=...` plus a literal `,<digits>,` in the
     * surrounding JSON, and also in a `signatureTimestamp:` field. Both are tried
     * because which one is present has changed between player revisions.
     */
    private fun extract(html: String): Int? {
        labelled(html).firstOrNull()?.let { return it }
        return fromPlayerResponse(html)
    }

    /** Reads the `signatureTimestamp` literal when the page names it explicitly. */
    private fun labelled(html: String): Sequence<Int> = Regex(
        """["']?signatureTimestamp["']?\s*[:=]\s*["']?(\d+)""",
    ).findAll(html).mapNotNull { it.groupValues[1].toIntOrNull() }

    /**
     * Matches the `sts` embedded in `ytcfg`'s `PLAYER_RESPONSE`.
     *
     * It sits in a fixed numeric run after the field name, so the digits directly
     * following it are the stamp rather than any part of the surrounding payload.
     */
    private fun fromPlayerResponse(html: String): Int? {
        val marker = "PLAYER_RESPONSE"
        val start = html.indexOf(marker)
        if (start == -1) return null
        val window = html.substring(start, minOf(html.length, start + 4000))
        // A run of digits bounded by a comma on both sides, long enough not to be a
        // stray view count.
        return Regex(""",(\d{5,8}),""").find(window)?.groupValues?.get(1)?.toIntOrNull()
    }
}
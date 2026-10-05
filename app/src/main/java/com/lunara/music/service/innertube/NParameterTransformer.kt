package com.lunara.music.service.innertube

import android.net.Uri
import android.util.Log

/**
 * The `n` (throttling) query parameter transform.
 *
 * When YouTube answers a player request it prefixes the stream URL with an `n`
 * parameter that is *not* a valid throttle signature. Requesting the URL as-is
 * yields a `403` from Google Video Server, or — worse — a stream that only
 * delivers the first megabyte. The real value is derived by running the
 * throttling function that ships inside `player.js`, so every stream that needs
 * it has to be run through this transform before it is ever played.
 *
 * This is the counterpart of the signature decipher in [PlayerCipher]: that one
 * fixes `s`/`sp`, this one fixes `n`.
 */
object NParameterTransformer {
    private const val TAG = "NParameterTransformer"

    /**
     * Rewrites the `n` parameter of [url] using [transform].
     *
     * [transform] is a suspending lambda because resolving the throttling
     * function may require fetching player.js on first use.
     *
     * Returns the original URL when there is no `n` parameter, when the
     * transform is unavailable, or when anything goes wrong — a URL without a
     * working transform is still worth handing to the validator, which will
     * reject it if Google Video Server actually refuses to serve it.
     */
    suspend fun apply(url: String, transform: suspend (String) -> String?): String {
        val n = runCatching { Uri.parse(url).getQueryParameter("n") }.getOrNull()
        if (n.isNullOrBlank()) return url

        val transformed = runCatching { transform(n) }.getOrNull()
        if (transformed.isNullOrBlank()) {
            Log.d(TAG, "No throttling transform available for n=$n")
            return url
        }
        if (transformed == n) return url

        Log.d(TAG, "Applied throttling transform to the n parameter")
        return url.replace(Regex("([?&]n=)[^&]*") { m -> m.groupValues[1] + transformed })
    }
}
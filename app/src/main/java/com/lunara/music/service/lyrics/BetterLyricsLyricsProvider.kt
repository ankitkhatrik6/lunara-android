package com.lunara.music.service.lyrics

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Better Lyrics aggregator (https://lyrics.api.dacubeking.com), matched by the
 * exact YouTube video id. The API is fronted by Cloudflare Turnstile: the
 * challenge is solved in an invisible WebView, exchanged for a JWT, and the
 * lyrics are streamed back over SSE.
 *
 * Requires [init] with an application context before use.
 */
object BetterLyricsLyricsProvider : LyricsProvider {
    override val name = "BetterLyrics"
    private const val TAG = "BetterLyricsProvider"
    private const val API = "https://lyrics.api.dacubeking.com/"
    private const val SOLVE_TIMEOUT_MS = 45_000L
    private const val SOLVE_BACKOFF_MS = 120_000L

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedJwt: String? = null

    @Volatile
    private var lastSolveFailureAt = 0L

    private val jwtMutex = Mutex()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        durationSeconds: Int,
        album: String?,
    ): String? = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext null
        if (id.isBlank()) return@withContext null
        val jwt = ensureJwt(context) ?: return@withContext null

        val form = mutableMapOf(
            "videoId" to id,
            "song" to title,
            "artist" to artist,
            "alwaysFetchMetadata" to "false",
            "token" to jwt
        )
        if (durationSeconds > 0) form["duration"] = durationSeconds.toString()
        if (!album.isNullOrBlank()) form["album"] = album

        val body = LyricsHttp.postForm(API + "v2/lyrics", form) ?: return@withContext null
        pickBest(parseSse(body))
    }

    private suspend fun ensureJwt(context: Context): String? = jwtMutex.withLock {
        cachedJwt?.takeIf { !isExpired(it) }?.let { return it }
        if (System.currentTimeMillis() - lastSolveFailureAt < SOLVE_BACKOFF_MS) return null

        val turnstile = solveTurnstile(context)
        if (turnstile == null) {
            lastSolveFailureAt = System.currentTimeMillis()
            return null
        }
        val jwt = exchangeToken(turnstile)
        if (jwt == null) {
            lastSolveFailureAt = System.currentTimeMillis()
            return null
        }
        cachedJwt = jwt
        Log.i(TAG, "Obtained fresh Better Lyrics JWT")
        jwt
    }

    private fun isExpired(token: String): Boolean = runCatching {
        val payload = token.split(".")[1]
        val decoded = String(
            Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        )
        val exp = JSONObject(decoded).optLong("exp", 0L)
        exp <= 0L || System.currentTimeMillis() / 1000 > exp - 60
    }.getOrDefault(true)

    private fun exchangeToken(turnstileToken: String): String? {
        val body = JSONObject().put("token", turnstileToken).toString()
        val response = LyricsHttp.postJson(
            API + "verify-turnstile",
            body,
            mapOf("Content-Type" to "application/json")
        ) ?: return null
        return runCatching {
            JSONObject(response).optString("jwt").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun solveTurnstile(context: Context): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val resumed = AtomicBoolean(false)
            var webView: WebView? = null
            val handler = Handler(Looper.getMainLooper())

            fun finish(token: String?) {
                if (resumed.compareAndSet(false, true)) {
                    handler.post {
                        runCatching { webView?.destroy() }
                        webView = null
                    }
                    cont.resume(token)
                }
            }

            try {
                val view = WebView(context.applicationContext)
                webView = view
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onMessage(raw: String) {
                            runCatching {
                                val json = JSONObject(raw)
                                when (json.optString("type")) {
                                    "turnstile-token" ->
                                        finish(json.optString("token").takeIf { it.isNotBlank() })
                                    "turnstile-error", "turnstile-timeout" -> finish(null)
                                }
                            }
                        }
                    },
                    "LunaraBridge"
                )
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(
                            """
                            window.addEventListener('message', function(e) {
                                try { LunaraBridge.onMessage(JSON.stringify(e.data)); } catch (err) {}
                            });
                            """.trimIndent(),
                            null
                        )
                    }
                }
                view.loadUrl(API + "challenge")
                handler.postDelayed({ finish(null) }, SOLVE_TIMEOUT_MS)
                cont.invokeOnCancellation { finish(null) }
            } catch (e: Exception) {
                finish(null)
            }
        }
    }

    private fun parseSse(body: String): Map<String, String> {
        val results = LinkedHashMap<String, String>()
        var event = ""
        val data = StringBuilder()

        fun field(json: JSONObject, key: String): String? {
            if (!json.has(key) || json.isNull(key)) return null
            return json.optString(key).takeIf { it.isNotBlank() && it != "null" }
        }

        fun flush() {
            val payload = data.toString()
            data.setLength(0)
            if (payload.isBlank() || payload == "[DONE]" || event != "provider") {
                event = ""
                return
            }
            runCatching {
                val json = JSONObject(payload)
                val provider = json.optString("provider")
                val res = json.optJSONObject("results") ?: return@runCatching
                field(res, "wordByWord")?.let { results["rich:$provider"] = it }
                field(res, "synced")?.let { results["sync:$provider"] = it }
                field(res, "plainLyrics")?.let { results["plain:$provider"] = it }
                field(res, "plain")?.let { results["plain:$provider"] = it }
            }
            event = ""
        }

        for (line in body.lineSequence()) {
            when {
                line.startsWith("event:") -> event = line.substringAfter(':').trim()
                line.startsWith("data:") -> data.append(line.substringAfter(':').trim())
                line.isBlank() -> {
                    flush()
                    if (results.containsKey("rich:musixmatch")) break
                }
            }
        }
        flush()
        return results
    }

    private fun pickBest(results: Map<String, String>): String? {
        val order = listOf(
            "rich:musixmatch",
            "sync:musixmatch", "sync:blyrics", "sync:binimum",
            "sync:portato", "sync:legato", "sync:lrclib"
        )
        for (key in order) {
            results[key]?.let { if (LyricsUtils.isSynced(it)) return it }
        }
        results.entries.firstOrNull { it.key.startsWith("sync:") && LyricsUtils.isSynced(it.value) }
            ?.let { return it.value }
        return results.entries.firstOrNull { it.key.startsWith("plain:") && it.value.length > 40 }?.value
    }
}
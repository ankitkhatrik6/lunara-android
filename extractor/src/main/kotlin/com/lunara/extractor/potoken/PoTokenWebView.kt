package com.lunara.extractor.potoken

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lunara.extractor.ClientRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Drives Google's BotGuard virtual machine inside a hidden WebView to mint PO tokens.
 *
 * This is the piece that makes playback work. Without a token the media CDN answers a
 * range request past 1 MiB with `403` — measured, see `botguard.html` — which ExoPlayer
 * experiences as an endless buffer.
 *
 * The sequence, in order:
 *  1. `.../api/jnn/v1/Create` returns a challenge naming an obfuscated VM.
 *  2. `botguard.html` evaluates it and snapshots the running VM.
 *  3. `.../api/jnn/v1/GenerateIT` trades that snapshot for an integrity token.
 *  4. The integrity token becomes a minter, reused for every later token.
 *
 * Starting a WebView is expensive and minting on a warm one is cheap, so exactly one
 * lives for the process and is replaced only when the renderer dies or the token
 * expires.
 */
class PoTokenWebView private constructor(
    context: Context,
    private val continuation: Continuation<PoTokenWebView>,
) {
    private val appContext = context.applicationContext
    private val webView = WebView(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Initialisation can fail down several paths at once (JS error, failed BotGuard
     * request, dead renderer). Resuming a plain continuation twice throws, so exactly
     * one failure is allowed through.
     */
    private val initResumed = AtomicBoolean(false)

    @Volatile
    private var closed = false

    /**
     * Set when the renderer died or a mint timed out.
     *
     * `onRenderProcessGone` is not delivered reliably on every OEM build, so callers
     * check this and rebuild immediately instead of waiting for a failure that may
     * never arrive.
     */
    @Volatile
    var isDead: Boolean = false
        private set

    /**
     * Pending mint requests, keyed by a per-call unique string.
     *
     * The key is suffixed with a counter rather than being the raw identifier: player
     * prefetch and playback can ask for the same video at once, and keying on the
     * identifier alone would drop one caller's continuation and hang it until timeout.
     */
    private val pending = ConcurrentHashMap<String, Continuation<String>>()
    private val requestCounter = AtomicLong()

    /** Epoch millis after which this instance must be rebuilt. */
    @Volatile
    var expiresAtMs: Long = 0L
        private set

    val isExpired: Boolean
        get() = expiresAtMs in 1 until System.currentTimeMillis()

    init {
        webView.settings.apply {
            javaScriptEnabled = true
            userAgentString = CHROME_USER_AGENT
            // The VM itself never fetches anything: all network I/O is done by OkHttp,
            // so blocking loads keeps a remotely-served script from steering the page.
            blockNetworkLoads = true
            domStorageEnabled = false
        }
        webView.addJavascriptInterface(this, JS_INTERFACE)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                val msg = m.message()
                if (msg != null && msg.contains("Uncaught")) {
                    val detail = "\"$msg\" (${m.sourceId()}:${m.lineNumber()})"
                    // Once initialised, an uncaught error came from Google's remotely
                    // served program, which is transient. Before that it came from our
                    // own bootstrap, which means this WebView is broken for good.
                    if (initResumed.get()) {
                        fail(PoTokenException("BotGuard program threw: $detail"))
                    } else {
                        fail(BadWebViewException("WebView is broken: $detail"))
                    }
                }
                return super.onConsoleMessage(m)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Transient: the OS killed the renderer under memory pressure. Reporting
                // this as a bad WebView would disable tokens for the whole session.
                fail(PoTokenException("WebView renderer was killed"))
                // Consume it, or the framework takes the app process down with it.
                return true
            }
        }
    }

    //region Initialisation

    /** Loads the harness and runs the BotGuard handshake. Call once, right after construction. */
    fun bootstrap() {
        scope.launch {
            val document = try {
                withContext(Dispatchers.IO) {
                    appContext.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
                }
            } catch (e: Exception) {
                fail(BadWebViewException("Could not load $ASSET_NAME", e))
                return@launch
            }
            // Kick the handshake off as soon as the document has parsed.
            val page = document.replaceFirst(
                "</script>",
                "\n$JS_INTERFACE.downloadAndRunBotguard()</script>",
            )
            webView.loadDataWithBaseURL(
                "https://www.youtube.com", page, "text/html", "utf-8", null,
            )
        }
    }

    /**
     * Asks the BotGuard service for a challenge and runs it in the page.
     * Called from JS once the document is ready.
     */
    @JavascriptInterface
    fun downloadAndRunBotguard() {
        botguardRequest("$BOTGUARD_BASE/Create", "[ ${quoteJs(REQUEST_KEY)} ]") { body ->
            val challenge = parseChallenge(body)
            webView.evaluateJavascript(
                """
                try {
                    var challenge = $challenge;
                    runBotGuard(challenge).then(function (result) {
                        this.webPoSignalOutput = result.webPoSignalOutput;
                        $JS_INTERFACE.onBotGuardRun(result.botguardResponse);
                    }, function (error) {
                        $JS_INTERFACE.onScriptError('' + error + (error && error.stack));
                    });
                } catch (error) {
                    $JS_INTERFACE.onScriptError('' + error + (error && error.stack));
                }
                """.trimIndent(),
                null,
            )
        }
    }

    /**
     * Trades the VM snapshot for an integrity token, then builds the minter.
     * Called from JS once the challenge has been executed.
     */
    @JavascriptInterface
    fun onBotGuardRun(botguardResponse: String) {
        botguardRequest(
            "$BOTGUARD_BASE/GenerateIT",
            "[ ${quoteJs(REQUEST_KEY)}, ${quoteJs(botguardResponse)} ]",
        ) { body ->
            val (integrityToken, ttlSeconds) = parseIntegrityToken(body)
            // Ten minutes of margin: a token used in its final minutes gets refused.
            expiresAtMs =
                System.currentTimeMillis() + (ttlSeconds - 600).coerceAtLeast(60) * 1000L

            webView.evaluateJavascript(
                """
                try {
                    createPoTokenMinter($integrityToken).then(function () {
                        $JS_INTERFACE.onReady();
                    }, function (error) {
                        $JS_INTERFACE.onScriptError('' + error + (error && error.stack));
                    });
                } catch (error) {
                    $JS_INTERFACE.onScriptError('' + error + (error && error.stack));
                }
                """.trimIndent(),
                null,
            )
        }
    }

    /** Initialisation finished: hand the live instance to whoever is waiting for it. */
    @JavascriptInterface
    fun onReady() {
        if (initResumed.compareAndSet(false, true)) {
            continuation.resume(this)
        }
    }

    /** A failure raised on the bootstrap path, before the instance is usable. */
    @JavascriptInterface
    fun onScriptError(error: String) {
        fail(if (initResumed.get()) PoTokenException(error) else BadWebViewException(error))
    }

    //endregion
//region Minting

    /**
     * Mints a base64 PO token for [identifier].
     *
     * [identifier] is the visitor session id for a session token, or a video id for a
     * token bound to one specific track.
     */
    suspend fun mint(identifier: String): String {
        check(!closed) { "PoTokenWebView is closed and must be rebuilt" }
        // A per-call unique key, not the bare identifier: player prefetch and playback
        // can ask for the same video at once, and keying on the identifier alone would
        // drop one caller's continuation and hang it until timeout.
        val key = "$identifier#${requestCounter.incrementAndGet()}"

        return try {
            withTimeout(MINT_TIMEOUT_MS) { mintInternal(identifier, key) }
        } catch (e: TimeoutCancellationException) {
            // A renderer that never answers is wedged. Marking it dead means the next
            // call rebuilds instead of waiting out this timeout again.
            isDead = true
            pending.remove(key)
            throw PoTokenException("Minting a token timed out after ${MINT_TIMEOUT_MS}ms", e)
        }
    }

    private suspend fun mintInternal(identifier: String, key: String): String =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                pending[key] = cont
                // The IIFE keeps `key` lexically scoped to this call: as a bare global a
                // concurrent mint would reassign it before this promise settled and
                // deliver this token to the other caller.
                webView.evaluateJavascript(
                    """
                    (function() {
                        var key = ${quoteJs(key)};
                        try {
                            obtainPoToken(${toJsUint8Array(identifier)}).then(function (token) {
                                $JS_INTERFACE.onMinted(key, Array.prototype.join.call(token, ','));
                            }, function (error) {
                                $JS_INTERFACE.onMintError(key, '' + error + (error && error.stack));
                            });
                        } catch (error) {
                            $JS_INTERFACE.onMintError(key, '' + error + (error && error.stack));
                        }
                    })();
                    """.trimIndent(),
                    null,
                )
            }
        }

    /** A token came back from the VM. */
    @JavascriptInterface
    fun onMinted(key: String, commaSeparatedBytes: String) {
        val token = runCatching {
            val bytes = commaSeparatedBytes.split(',')
                .filter { it.isNotBlank() }
                .map { it.trim().toInt().toByte() }
                .toByteArray()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrElse {
            pending.remove(key)?.resumeWithException(PoTokenException("Malformed token bytes", it))
            return
        }
        pending.remove(key)?.resume(token)
    }

    /**
     * A mint failed.
     *
     * Always transient: the minter already exists, so even a syntax error came from
     * Google's challenge data rather than from this WebView. Reporting it as a bad
     * WebView would disable tokens for the rest of the session.
     */
    @JavascriptInterface
    fun onMintError(key: String, error: String) {
        pending.remove(key)?.resumeWithException(PoTokenException(error))
    }

    //endregion
//region Plumbing

    /**
     * Posts to the BotGuard service and hands the body to [onBody].
     *
     * Done with OkHttp rather than in the page on purpose: the WebView runs with
     * `blockNetworkLoads`, so its JavaScript cannot reach the network, and a
     * remotely-served script must not decide what this app sends.
     */
    private fun botguardRequest(url: String, payload: String, onBody: (String) -> Unit) {
        scope.launch {
            try {
                val request = Request.Builder()
                    .url(url)
                    .post(payload.toRequestBody(JSON_PROTOBUF))
                    .header("User-Agent", CHROME_USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json+protobuf")
                    .header("x-goog-api-key", ClientRegistry.KEY_BOTGUARD)
                    .header("x-user-agent", "grpc-web-javascript/0.1")
                    .build()

                val (code, body) = withContext(Dispatchers.IO) {
                    http.newCall(request).execute().use { response ->
                        response.code to if (response.isSuccessful) response.body?.string() else null
                    }
                }

                // An empty 200 is a failure too: parsing "" downstream would produce a
                // far less obvious error than saying so here.
                if (body.isNullOrEmpty()) {
                    fail(PoTokenException("BotGuard request failed (HTTP $code, empty body)"))
                } else {
                    onBody(body)
                }
            } catch (e: Exception) {
                fail(
                    if (e is PoTokenException) e else PoTokenException("BotGuard request failed", e),
                )
            }
        }
    }

    /**
     * Pulls the challenge out of the Create response.
     *
     * Returns a JS literal because it is interpolated straight into
     * `evaluateJavascript`. Re-encoding through `JSONObject` would be wrong: the VM's
     * `program` field is bytecode that has to survive as an untouched string.
     */
    private fun parseChallenge(body: String): String = runCatching {
        val challenge = JSONArray(body).getJSONObject(0)
        val globalName = challenge.getString("globalName")
        val program = challenge.getString("program")
        val interpreter = challenge
            .getJSONObject("interpreterJavascript")
            .getString("privateDoNotAccessOrElseSafeScriptWrappedValue")
        """{"globalName":${quoteJs(globalName)},"program":${quoteJs(program)},"""
        """"interpreterJavascript":{"privateDoNotAccessOrElseSafeScriptWrappedValue":"""
        "${quoteJs(interpreter)}}}"
    }.getOrElse { throw PoTokenException("Malformed BotGuard challenge", it) }

    /**
     * Reads the integrity token and its lifetime out of the GenerateIT response.
     *
     * The token arrives as a JSON array of byte values, so it is rendered as a JS
     * `Uint8Array`: `createPoTokenMinter` passes it straight to BotGuard, which expects
     * bytes and not a base64 string.
     */
    private fun parseIntegrityToken(body: String): Pair<String, Long> = runCatching {
        val array = JSONArray(body)
        val ttlSeconds = array.optLong(1, DEFAULT_TTL_SECONDS)
        val bytesJson = array.optJSONArray(0) ?: throw PoTokenException("No integrity token")
        val bytes = ByteArray(bytesJson.length()) { bytesJson.getInt(it).toByte() }
        toJsUint8Array(bytes) to ttlSeconds
    }.getOrElse { throw PoTokenException("Malformed integrity token", it) }

    /** Renders [value] as a JavaScript `Uint8Array`, which is how BotGuard takes input. */
    private fun toJsUint8Array(value: String): String =
        toJsUint8Array(value.toByteArray(Charsets.UTF_8))

    private fun toJsUint8Array(bytes: ByteArray): String =
        "new Uint8Array([${bytes.joinToString(",") { (it.toInt() and 0xFF).toString() }}])"

    /**
     * Quotes a Kotlin string as a JavaScript string literal.
     *
     * Everything crossing into the page goes through this. The values are base64 tokens
     * and bytecode from the network, so they are escaped rather than assumed safe to
     * interpolate. `<`, `>` and `&` are escaped too, so no injected fragment can close
     * the surrounding `<script>` element.
     */
    private fun quoteJs(value: String): String {
        val sb = StringBuilder(value.length + 16)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '<' -> sb.append("\\u003c")
                '>' -> sb.append("\\u003e")
                '&' -> sb.append("\\u0026")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
/**
     * Tears this instance down and fails everything waiting on it.
     *
     * Every failure route goes through here, so a caller can never be left waiting on a
     * WebView that has already gone away.
     */
    private fun fail(error: Throwable) {
        isDead = true
        close()
        val waiting = pending.toMap()
        pending.clear()
        waiting.values.forEach { cont -> runCatching { cont.resumeWithException(error) } }
        if (initResumed.compareAndSet(false, true)) {
            runCatching { continuation.resumeWithException(error) }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        // WebView methods must run on the thread that created it, but failures can
        // arrive on the JavaBridge thread, so hand teardown over instead of letting it
        // throw and leak the instance.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyQuietly()
        } else {
            Handler(Looper.getMainLooper()).post { destroyQuietly() }
        }
    }

    private fun destroyQuietly() {
        // After a renderer crash several of these can throw; teardown must never crash.
        runCatching {
            webView.clearHistory()
            webView.loadUrl("about:blank")
            webView.onPause()
            webView.removeAllViews()
            webView.destroy()
        }
    }

    //endregion

    companion object {
        private const val ASSET_NAME = "botguard.html"
        private const val JS_INTERFACE = "PoTokenWebView"
        private const val BOTGUARD_BASE = "https://www.youtube.com/api/jnn/v1"

        /** BotGuard's client key. Not an InnerTube key, and not derived from the user. */
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"

        private const val CHROME_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"

        private const val DEFAULT_TTL_SECONDS = 21_600L

        /**
         * Bootstrap budget: two round trips plus VM startup. A healthy cold start is
         * 2-5s; 45s is slack for a slow device without leaving a user waiting on a
         * WebView that was never going to answer.
         */
        private const val BOOTSTRAP_TIMEOUT_MS = 45_000L

        /** A live renderer mints a token in well under a second. */
        private const val MINT_TIMEOUT_MS = 15_000L

        private val JSON_PROTOBUF = "application/json+protobuf".toMediaTypeOrNull()

        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        /** Builds a ready-to-use instance, or throws if the handshake cannot complete. */
        suspend fun create(context: Context): PoTokenWebView {
            var created: PoTokenWebView? = null
            return try {
                withTimeout(BOOTSTRAP_TIMEOUT_MS) {
                    withContext(Dispatchers.Main) {
                        suspendCancellableCoroutine { cont ->
                            val instance = PoTokenWebView(context, cont)
                            created = instance
                            instance.bootstrap()
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                closeQuietly(created)
                throw PoTokenException("BotGuard did not start within ${BOOTSTRAP_TIMEOUT_MS}ms", e)
            } catch (e: CancellationException) {
                // The caller went away; do not leak a half-started WebView.
                closeQuietly(created)
                throw e
            }
        }

        private suspend fun closeQuietly(instance: PoTokenWebView?) {
            if (instance == null) return
            withContext(NonCancellable + Dispatchers.Main) {
                instance.close()
            }
        }
    }
}

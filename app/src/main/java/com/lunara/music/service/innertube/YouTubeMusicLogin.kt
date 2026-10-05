package com.lunara.music.service.innertube

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * YouTube Music sign-in, in the same style as Blazify/InnerTune: the user signs
 * in inside an in-app WebView, the session cookie is captured from
 * [CookieManager], validated, and persisted by [YouTubeSession]. Every later
 * InnerTube call (browse, search, next, player) attaches the cookie and the
 * SAPISIDHASH authorization, which unlocks the personal library, history,
 * uploads, mixes and subscription-gated streams.
 */
object YouTubeMusicLogin {
    private const val TAG = "YouTubeMusicLogin"
    private const val START_URL =
        "https://accounts.google.com/ServiceLogin?service=youtube" +
            "&continue=https%3A%2F%2Fmusic.youtube.com%2F&hl=en"
    private const val MUSIC_URL = "https://music.youtube.com/"
    private const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX3"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class LoginResult(
        val cookie: String?,
        val accountLabel: String?,
        val signedIn: Boolean,
        val currentUrl: String?,
    )

    /**
     * Opens the Google sign-in page in a WebView that must be attached by the
     * caller (see the YouTube login screen). Reads back the live WebView so the
     * caller can drive progress UI; cookie capture happens on demand through
     * [captureSession].
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(context: Context, onUrlChanged: (String?) -> Unit): WebView {
        val webView = WebView(context.applicationContext)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.userAgentString = UA

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)
        try {
            cookies.removeSessionCookies(null)
        } catch (_: Exception) {
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                onUrlChanged(url)
            }
        }
        webView.loadUrl(START_URL)
        return webView
    }

    /**
     * Reads the YouTube session cookies captured by the WebView and validates
     * them. Returns the combined cookie header value, or null when no signed-in
     * session is present yet.
     */
    fun captureSession(): LoginResult? {
        val manager = CookieManager.getInstance()
        val parts = LinkedHashMap<String, String>()
        listOf(MUSIC_URL, "https://www.youtube.com/", "https://accounts.google.com/").forEach { url ->
            runCatching { manager.getCookie(url) }.getOrNull()
                ?.split(";")
                ?.forEach { piece ->
                    val equals = piece.indexOf('=')
                    if (equals > 0) {
                        parts[piece.substring(0, equals).trim()] = piece.substring(equals + 1).trim()
                    }
                }
        }
        if (parts.isEmpty() || (!parts.containsKey("SAPISID") && !parts.containsKey("SSID"))) {
            return null
        }
        val cookie = parts.entries.joinToString("; ") { "${it.key}=${it.value}" }
        return LoginResult(
            cookie = cookie,
            accountLabel = null,
            signedIn = true,
            currentUrl = null,
        )
    }

    /** Flush cookies to persistent storage so the session survives restarts. */
    fun flushCookies() {
        runCatching { CookieManager.getInstance().flush() }
    }

    /** Clears all YouTube cookies, used when signing out. */
    fun clearCookies(onDone: () -> Unit = {}) {
        runCatching {
            val manager = CookieManager.getInstance()
            manager.removeSessionCookies { manager.removeAllCookies { onDone() } }
        }.onFailure { onDone() }
    }
}

/**
 * Reads the signed-in account label from the InnerTube `account_menu` endpoint
 * using the stored cookie.
 */
object YouTubeAccountClient {
    private const val TAG = "YouTubeAccountClient"
    private const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX3"
    private const val JSON_MEDIA = "application/json; charset=utf-8"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun authHeaders(): Headers? {
        val cookie = YouTubeSession.cookie.value ?: return null
        val auth = YouTubeSession.authorizationHeader() ?: return null
        return Headers.Builder()
            .add("Cookie", cookie)
            .add("Authorization", auth)
            .add("Content-Type", "application/json")
            .add("X-Origin", "https://music.youtube.com")
            .add("Referer", "https://music.youtube.com/")
            .add("User-Agent", UA)
            .build()
    }

    private fun clientContext(): JSONObject = JSONObject()
        .put(
            "client",
            JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", "1.20260213.01.00")
                .put("hl", "en")
                .put("gl", "US")
                .put("visitorData", YouTubeSession.visitorData.orEmpty())
        )

    private fun postInnerTube(endpoint: String, body: JSONObject): JSONObject? {
        val headers = authHeaders() ?: return null
        val request = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/$endpoint?key=$API_KEY&prettyPrint=false")
            .post(body.toString().toRequestBody(JSON_MEDIA.toMediaType()))
            .headers(headers)
            .build()
        return try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) null
                else runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun accountLabel(): String? = withContext(Dispatchers.IO) {
        try {
            val json = postInnerTube(
                "account/account_menu",
                JSONObject().put("context", clientContext())
            ) ?: return@withContext null

            val header = json.optJSONObject("actions")
                ?.optJSONArray(0)
                ?.optJSONObject(0)
                ?.optJSONObject("openPopupAction")
                ?.optJSONObject("popup")
                ?.optJSONObject("multiPageMenuRenderer")
                ?.optJSONObject("header")
                ?.optJSONObject("activeAccountHeaderRenderer")
                ?: return@withContext null

            extractRunsText(header.optJSONObject("accountName"))
                ?: extractRunsText(header.optJSONObject("accountByline"))
        } catch (e: Exception) {
            Log.w(TAG, "account menu failed: ${e.message}")
            null
        }
    }

    suspend fun describeChannel(channelId: String): String? = withContext(Dispatchers.IO) {
        val json = postInnerTube(
            "browse",
            JSONObject()
                .put("context", clientContext())
                .put("browseId", channelId)
        ) ?: return@withContext null

        val header = json.optJSONObject("header")
            ?.optJSONObject("c4TabbedHeaderRenderer")
            ?: json.optJSONObject("header")
                ?.optJSONObject("musicVisualHeaderRenderer")
            ?: json.optJSONObject("header")
                ?.optJSONObject("musicImmersiveHeaderRenderer")
        val title = extractRunsText(header?.optJSONObject("title")).takeUnless { it.isNullOrBlank() }
        title ?: extractRunsText(
            json.optJSONObject("metadata")
                ?.optJSONObject("channelMetadataRenderer")
                ?.optJSONObject("title")
        )
    }

    suspend fun libraryContinuation(): String? = withContext(Dispatchers.IO) {
        val json = postInnerTube(
            "browse",
            JSONObject()
                .put("context", clientContext())
                .put("browseId", "FEmusic_library_landing")
        ) ?: return@withContext null
        val single = json.optJSONObject("contents")
            ?.optJSONObject("singleColumnBrowseResultsRenderer")
        val tabs = single?.optJSONArray("tabs") ?: return@withContext null
        tabs.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("endpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId")
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractRunsText(obj: JSONObject?): String? {
        if (obj == null) return null
        val runs = obj.optJSONArray("runs") ?: return obj.optString("simpleText").ifBlank { null }
        val builder = StringBuilder()
        for (i in 0 until runs.length()) {
            builder.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
        return builder.toString().trim().ifBlank { null }
    }
}

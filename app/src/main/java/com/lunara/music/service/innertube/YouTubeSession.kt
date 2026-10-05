package com.lunara.music.service.innertube

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Holds the YouTube Music visitor identity and, when the user signs in, their
 * YouTube session cookie (with SAPISIDHASH authorization).
 *
 * This mirrors Blazify's session model: an anonymous visitorData works for the
 * catalogue, while the stored cookie unlocks personal home mixes, library,
 * history, uploads, and subscription-gated streams.
 */
object YouTubeSession {
    private const val TAG = "YouTubeSession"
    private const val PREFS_NAME = "lunara_innertube_session"
    private const val KEY_COOKIE = "session_cookie"
    private const val KEY_VISITOR_DATA = "visitor_data"
    private const val KEY_ACCOUNT = "account_label"

    private var prefs: SharedPreferences? = null

    private val _cookie = MutableStateFlow<String?>(null)
    val cookie: StateFlow<String?> = _cookie.asStateFlow()

    private val _accountLabel = MutableStateFlow<String?>(null)
    val accountLabel: StateFlow<String?> = _accountLabel.asStateFlow()

    /** Non-blank visitorData minted by YouTube, or null when unknown. */
    @Volatile
    var visitorData: String? = null
        private set

    val isSignedIn: Boolean get() = !_cookie.value.isNullOrBlank()

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedCookie = prefs?.getString(KEY_COOKIE, null).orEmpty()
        _cookie.value = storedCookie.ifBlank { null }
        _accountLabel.value = prefs?.getString(KEY_ACCOUNT, null)?.ifBlank { null }
        visitorData = prefs?.getString(KEY_VISITOR_DATA, null)?.ifBlank { null }
    }

    fun signIn(cookie: String) {
        val normalised = cookie.trim()
        prefs?.edit()?.putString(KEY_COOKIE, normalised)?.apply()
        _cookie.value = normalised.ifBlank { null }
    }

    fun setAccountLabel(label: String?) {
        val value = label?.trim().orEmpty()
        prefs?.edit()?.putString(KEY_ACCOUNT, value)?.apply()
        _accountLabel.value = value.ifBlank { null }
    }

    fun signOut() {
        prefs?.edit()?.remove(KEY_COOKIE)?.remove(KEY_ACCOUNT)?.apply()
        _cookie.value = null
        _accountLabel.value = null
    }

    fun rememberVisitorData(value: String?) {
        if (value == null) {
            visitorData = null
            prefs?.edit()?.remove(KEY_VISITOR_DATA)?.apply()
            return
        }
        val cleaned = value.trim().orEmpty()
        if (cleaned.isBlank() || cleaned == "null" || cleaned == "undefined") return
        if (cleaned == visitorData) return
        visitorData = cleaned
        prefs?.edit()?.putString(KEY_VISITOR_DATA, cleaned)?.apply()
    }

    /**
     * Authorization header YouTube expects alongside the cookie, in the exact
     * Blazify/InnerTune form: `SAPISIDHASH <seconds>_<sha1>`.
     */
    fun authorizationHeader(origin: String = "https://music.youtube.com"): String? {
        val cookies = _cookie.value ?: return null
        val sapisid = parseCookie(cookies)["SAPISID"] ?: return null
        val seconds = System.currentTimeMillis() / 1000
        return "SAPISIDHASH ${seconds}_${sha1("$seconds $sapisid $origin")}"
    }

    private fun parseCookie(header: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        header.split(";").forEach { part ->
            val equals = part.indexOf('=')
            if (equals > 0) {
                map[part.substring(0, equals).trim()] = part.substring(equals + 1).trim()
            }
        }
        return map
    }

    private fun sha1(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    suspend fun describeAccount(): String? = withContext(Dispatchers.IO) {
        val name = _accountLabel.value
        if (!name.isNullOrBlank()) return@withContext name
        if (!isSignedIn) return@withContext null
        try {
            YouTubeAccountClient.accountLabel()?.also { setAccountLabel(it) }
        } catch (e: Exception) {
            Log.w(TAG, "account label lookup failed: ${e.message}")
            null
        }
    }
}
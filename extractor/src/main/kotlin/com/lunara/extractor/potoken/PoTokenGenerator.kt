package com.lunara.extractor.potoken

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Owns the single live [PoTokenWebView] and hands out token pairs.
 *
 * There is exactly one WebView for the whole process because building one is
 * expensive (2-5s cold) while minting on a warm one is well under a second. It is
 * rebuilt only when the renderer died or its integrity token expired.
 *
 * Every call is rate-limited by time rather than by a flag: once a real song has
 * asked for a token the cold cost is already being paid on the path that matters, so
 * a second background warm-up only takes the renderer away from it.
 */
object PoTokenGenerator {

    /** A healthy mint takes well under a second; 8s leaves slack without stalling playback. */
    private const val GENERATE_TIMEOUT_MS = 8_000L

    private val lock = Mutex()

    private var webView: PoTokenWebView? = null
    private var sessionId: String? = null

    /** Session-scoped token, minted once per session because it does not change per track. */
    private var streamingToken: String? = null

    /** Set when the WebView itself is unusable, so we stop paying to find that out. */
    private var webViewUnusable = false

    /**
     * Whether a WebView is available at all.
     *
     * A device with no WebView (some de-Googled builds) can still play the client
     * families that do not need a token, so this disables the token path rather than
     * playback.
     */
    private val webViewSupported: Boolean by lazy {
        runCatching { CookieManager.getInstance() }.isSuccess
    }

    /**
     * Returns the two tokens a resolve needs, or null when none can be produced.
     *
     * Null is a normal answer, not a failure: the caller then falls back to clients
     * that serve without one. Callers must never treat it as fatal.
     */
    fun tokensFor(videoId: String, visitorData: String?): PoTokenResult? {
        if (!webViewSupported || webViewUnusable) return null
        // BotGuard binds a token to a session, and a visitor session that has not been
        // minted yet has nothing to bind to.
        val session = visitorData?.takeIf { it.isNotBlank() } ?: return null

        return try {
            runBlocking {
                withTimeout(GENERATE_TIMEOUT_MS) { tokensForInternal(videoId, session) }
            }
        } catch (e: TimeoutCancellationException) {
            // The renderer can be culled by the OS under memory pressure, which leaves
            // the WebView call hung for good. Cap it so resolution falls through to the
            // no-token clients instead of blocking playback indefinitely.
            runBlocking { discardWebView() }
            null
        } catch (e: BadWebViewException) {
            webViewUnusable = true
            null
        } catch (e: Exception) {
            // Transient: a bad challenge, a dropped renderer, a network blip. Drop the
            // instance so the next attempt starts from a clean one.
            runBlocking { discardWebView() }
            null
        }
    }

    private suspend fun tokensForInternal(videoId: String, session: String): PoTokenResult? =
        lock.withLock {
            val instance = obtainWebView(session)
            // The session token must exist before any track token: BotGuard mints the
            // session token once and derives the per-track ones from that same context.
            val sessionToken = streamingToken ?: instance.mint(session).also { streamingToken = it }
            val trackToken = instance.mint(videoId)
            PoTokenResult(playerRequestPoToken = sessionToken, streamingDataPoToken = trackToken)
        }

    /**
     * Returns a ready WebView, rebuilding it only when it has to be.
     *
     * Rebuild conditions are checked in one place so a dead renderer is replaced before
     * the caller spends a timeout discovering it is gone.
     */
    private suspend fun obtainWebView(session: String): PoTokenWebView {
        val existing = webView
        if (existing != null && !existing.isDead && !existing.isExpired && sessionId == session) {
            return existing
        }

        existing?.close()
        val fresh = PoTokenWebView.create(appContext ?: error("PoTokenGenerator.init was never called"))
        webView = fresh
        sessionId = session
        streamingToken = null
        return fresh
    }

    private suspend fun discardWebView() {
        lock.withLock {
            webView?.close()
            webView = null
            sessionId = null
            streamingToken = null
        }
    }

    /**
     * Builds the generator ahead of the first song.
     *
     * Best-effort: BotGuard's cold start is the single largest fixed cost in starting
     * playback, so paying it while the user is still on the home screen is most of the
     * difference between a song that starts immediately and one that does not. Safe to
     * call without a visitor session — it simply does nothing.
     */
    fun prewarm(context: Context) {
        if (!webViewSupported || webViewUnusable) return
        appContext = context.applicationContext
        runCatching {
            runBlocking {
                withTimeout(GENERATE_TIMEOUT_MS) {
                    lock.withLock { obtainWebView("warmup") }
                }
            }
        }
    }

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
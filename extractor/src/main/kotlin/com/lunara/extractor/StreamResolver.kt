package com.lunara.extractor

import android.util.Log
import android.content.Context
import android.net.Uri
import com.lunara.extractor.cipher.CipherDeobfuscator
import com.lunara.extractor.potoken.PoTokenGenerator
import com.lunara.extractor.potoken.PoTokenResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns a video id into a stream that has been proven to serve data.
 *
 * The order of operations is the whole design, so it is worth stating plainly:
 *
 *  1. Mint a PO token. Without one most clients answer `LOGIN_REQUIRED`, and the ones
 *     that do answer cap their media at 1 MiB. This runs first because it is the only
 *     step that unlocks everything else.
 *  2. Ask each client in health order. A rested client is still tried; only later.
 *  3. Hand the first finalized stream to the player **without probing it** — Blazify's
 *     rule. A speculative probe on a separate connection is not evidence about the
 *     player's, and gates built on one kept rejecting streams the player reads fine
 *     (v2.2.2's first-byte check, v2.2.3's deep veto). Dead addresses are the data
 *     source's problem, and it heals them in place where the evidence is real.
 *  4. If every client refused, renew the session identity once and try again. Every
 *     client failing together is a stale identity, not a broken track, and this is the
 *     difference between a library that mends itself and one somebody must reinstall.
 *
 * Results are cached whole, headers included, because a googlevideo URL is only served
 * to the identity that minted it. Caching the URL alone is what sends a working URL to
 * the CDN with the wrong headers and produces a stream that starts and then stops.
 */
object StreamResolver {

    private const val TAG = "LunaraResolver"

    private const val MAX_CACHE_ENTRIES = 32

    /** The outcome of one resolve. */
    sealed interface Outcome {
        data class Success(val stream: AudioStream) : Outcome
        data class Failure(val reason: ResolveFailure) : Outcome
    }

    private val cache = object : LinkedHashMap<String, AudioStream>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AudioStream>?) =
            size > MAX_CACHE_ENTRIES
    }

    /**
     * One lock per track, so a slow resolve for one song never queues taps on
     * other songs behind it. The old single global lock meant tapping song B
     * while song A was still resolving (token mint + several client round
     * trips, easily 30s+) parked B's coroutine for the whole duration — with
     * the player scope on Main that read as a full-app hang -> ANR.
     */
    private val videoLocks = LinkedHashMap<String, Mutex>()
    private val videoLocksGuard = Any()

    private fun lockFor(videoId: String): Mutex = synchronized(videoLocksGuard) {
        // Bound the map so a long session can't grow it without limit.
        if (videoLocks.size > 64) {
            val oldest = videoLocks.keys.firstOrNull()
            if (oldest != null) videoLocks.remove(oldest)
        }
        videoLocks.getOrPut(videoId) { Mutex() }
    }

    private val warmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Scope for fire-and-forget maintenance, so [clear] stays callable from a callback. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Prepares the extraction pipeline.
     *
     * The visitor id and the token generator are warmed together, off the main thread.
     * Both are slow on a cold start and neither is needed to show the home screen, so
     * paying for them here is what makes the first song start promptly instead of
     * after a visible pause.
     */
    fun init(context: Context) {
        PoTokenGenerator.init(context)
        // Loads the player-config table and hands the cipher stack its context. Must
        // precede any resolve: the signature timestamp and the stream-URL fixups both
        // run through it, and the WebView machinery is lateinit without it.
        CipherDeobfuscator.initialize(context)
        ClientHealth.prewarm()
        warmupScope.launch {
            runCatching { SessionStore.ensure() }
            runCatching { PoTokenGenerator.prewarm(context) }
            // Building the cipher WebView parses ~2.8 MB of player JS and takes seconds;
            // warmed here the first song does not pay for it.
            runCatching { CipherDeobfuscator.prewarm() }
        }
    }

    /**
     * The quality the *user* chose, as opposed to the one a single caller asked for.
     *
     * Settings write here rather than threading a preference through every call site:
     * the data source, the player's pre-play resolve and the loading thread's fresh
     * resolve all read the same value, so one write changes the whole pipeline.
     */
    @Volatile
    private var preferredQuality: StreamQuality = StreamQuality.AUTO

    /** The quality new resolves will use. */
    fun preferredQuality(): StreamQuality = preferredQuality

    /**
     * Applies a new quality setting.
     *
     * The stream cache is dropped because every entry in it was chosen under the old
     * setting — keeping them would make the setting appear not to take effect until
     * each stale entry happened to expire. Blazify bypasses its cache on a quality
     * change for the same reason. Client health is left alone: it is evidence about
     * YouTube, not about which format was picked.
     */
    fun setPreferredQuality(quality: StreamQuality) {
        if (quality == preferredQuality) return
        preferredQuality = quality
        synchronized(cache) { cache.clear() }
    }

    /**
     * Resolves [videoId], or says why it could not be resolved.
     *
     * The failure is returned rather than thrown so the UI can say something true:
     * "this track is unavailable" and "the network is being awkward" are different
     * problems, and one message for both is how a broken app looks like a working one.
     */
    suspend fun resolve(
        videoId: String,
        quality: StreamQuality = preferredQuality,
    ): Outcome {
        if (videoId.isBlank()) return Outcome.Failure(ResolveFailure.Unavailable("Empty video id"))

        cached(videoId)?.let { return Outcome.Success(it) }

        // Per-track lock only (see lockFor), and the whole resolve is bounded:
        // token mint (8s) + several clients x (player call + probes) can
        // otherwise run for minutes while the UI shows an endless spinner.
        return try {
            withTimeout(RESOLVE_TIMEOUT_MS) {
                lockFor(videoId).withLock {
                    // Another caller may have resolved this while this one waited for the lock.
                    cached(videoId)?.let { return@withLock Outcome.Success(it) }

                    val outcome = resolveUncached(videoId, quality)
                    if (outcome is Outcome.Success) store(videoId, outcome.stream)
                    outcome
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Resolve for $videoId timed out after ${RESOLVE_TIMEOUT_MS}ms")
            Outcome.Failure(ResolveFailure.Network("Resolving took too long. Check your connection."))
        }
    }

    /**
     * One uncached resolve: token, rotation, self-healing retry. No cache reads or
     * writes; callers own those. Shared by [resolve] (locked, cached) and
     * [resolveFreshBlocking] (loading thread, cache bypassed).
     */
    private suspend fun resolveUncached(
        videoId: String,
        quality: StreamQuality = preferredQuality,
    ): Outcome {
        val visitorData = SessionStore.ensure()
        // One token set serves every client: it is bound to the session, not to a
        // track, so re-minting per client would cost seconds for nothing.
        val tokens = PoTokenGenerator.tokensFor(videoId, visitorData)
        // Without a token the media CDN serves the first megabyte and then refuses —
        // the song that dies at ~64s. A single warning line turns the next bug report
        // into a logcat that names the cause outright.
        if (tokens == null) {
            Log.w(TAG, "No PO token for $videoId — streams from this resolve may be capped at 1 MiB")
        }
        // The stamp of the player this cipher will decipher with: a signature minted
        // against one player generation and unscrambled by another is refused by the
        // CDN, and that refusal reports nothing. The iframe-API reader — same page
        // the player hash comes from — is the fallback when the script is unfetchable.
        val signatureTimestamp = if (tokens != null) {
            CipherDeobfuscator.signatureTimestamp() ?: SignatureTimestamp.get()
        } else {
            null
        }

        var outcome = resolveOnce(videoId, quality, visitorData, tokens, signatureTimestamp)

        // Every client failing together is the signature of a session the catalogue
        // has stopped recognising, not of a broken track. Renew once and retry: this
        // is what lets the app recover on its own after YouTube invalidates an
        // identity, instead of needing a reinstall.
        if (outcome is Outcome.Failure && outcome.reason is ResolveFailure.Blocked) {
            if (SessionStore.renewIfStale()) {
                Log.i(TAG, "Renewing the session and retrying $videoId")
                val freshVisitorData = SessionStore.ensure()
                val freshTokens = PoTokenGenerator.tokensFor(videoId, freshVisitorData)
                outcome = resolveOnce(
                    videoId, quality, freshVisitorData, freshTokens, signatureTimestamp,
                )
            }
        }
        return outcome
    }

    /** Whole-resolve budget: token + rotation + cipher work must never hang the UI. */
    private const val RESOLVE_TIMEOUT_MS = 60_000L

    /**
     * Budget for a heal running on the loading thread. It sits inside a user's
     * seek, so it is deliberately tight: a warm token WebView mints in well under a
     * second and the player round trip is 1-3 s. Anything slower than this is a
     * failure the player's retry policy should hear about quickly.
     */
    private const val FRESH_RESOLVE_TIMEOUT_MS = 15_000L

    /** One full pass over the client rotation. */
    private suspend fun resolveOnce(
        videoId: String,
        quality: StreamQuality,
        visitorData: String?,
        tokens: PoTokenResult?,
        signatureTimestamp: Int?,
    ): Outcome {
        var sawPlayable = false
        var sawRefusal = false
        var lastRefusalReason: String? = null

        for (client in ClientHealth.ordered()) {
            val response = InnerTubePlayerApi.fetch(
                client = client,
                videoId = videoId,
                visitorData = visitorData,
                poToken = tokens?.playerRequestPoToken,
                signatureTimestamp = signatureTimestamp,
            ) ?: continue

            if (!response.playable) {
                sawRefusal = true
                lastRefusalReason = response.reason
                // A bot check clears on its own. An `UNPLAYABLE` about the video itself
                // never will, and resting the client for it only costs other tracks.
                val retryable = response.reason?.contains("not a bot", ignoreCase = true) == true ||
                    response.status == "LOGIN_REQUIRED"
                ClientHealth.recordRefused(client, retryable)
                Log.d(TAG, "${client.displayName}: ${response.status} ${response.reason.orEmpty()}")
                continue
            }

            if (response.streams.isEmpty()) {
                sawRefusal = true
                ClientHealth.recordBadStream(client)
                continue
            }

            sawPlayable = true
            // Blazify's exact rule: finalize the first usable format and hand it over.
            // No speculative probe of any kind — a separate probe connection says
            // nothing about the player's own (v2.2.2's first-byte probe and v2.2.3's
            // deep veto both rejected streams the player would have read fine, and the
            // veto made the whole library unplayable). A URL that turns out dead is
            // the data source's job: it re-resolves in place and resumes at the exact
            // byte, which is where the real evidence lives.
            // Candidates are finalized lazily so the common case pays for one cipher,
            // not every format's.
            for (candidate in selectCandidates(response.streams, quality)) {
                if (candidate.isExpired) continue
                // A raw format is not yet an address the CDN will serve: the cipher has
                // to be unscrambled, the throttle solved and the streaming token attached.
                val stream = finalizeStream(candidate, client, videoId, tokens) ?: continue
                ClientHealth.recordSuccess(client)
                Log.i(
                    TAG,
                    "Resolved $videoId via ${client.displayName}: " +
                        "${stream.bitrate / 1000} kbps ${stream.containerMimeType}",
                )
                return Outcome.Success(stream)
            }

            // The client answered OK but produced no usable address at all (expired
            // formats, every cipher unsolvable). Rest it and tell the cipher its player
            // script may be behind — both mean "try something else next time".
            ClientHealth.recordBadStream(client)
            scope.launch { runCatching { CipherDeobfuscator.onStreamRejected() } }
            Log.d(TAG, "${client.displayName} produced no usable stream address")
        }

        return when {
            // Something resolved but nothing served data: a throttling problem, not a
            // missing track.
            sawPlayable -> Outcome.Failure(ResolveFailure.NoPlayableStream)

            // A refusal with a reason worth showing beats a generic one, but a bot check
            // is not the user's problem and reads as nonsense when shown to them.
            sawRefusal && lastRefusalReason != null &&
                !lastRefusalReason.contains("not a bot", ignoreCase = true) ->
                Outcome.Failure(ResolveFailure.Unavailable(lastRefusalReason))

            sawRefusal -> Outcome.Failure(ResolveFailure.Blocked)

            // Nothing answered at all: the network, not the catalogue.
            else -> Outcome.Failure(ResolveFailure.Network("No client answered"))
        }
    }

    /**
     * Orders a client's formats for this quality setting.
     *
     * All of them are kept as candidates rather than only the best, because "best" is a
     * guess and the next one down may be the only one the CDN will actually serve.
     *
     * The bitrate bands mirror the settings labels ("Data Saver (64 kbps)",
     * "Normal (128 kbps)", "High Quality (256 kbps)"): each setting prefers its own
     * class of stream and falls through to the rest rather than refusing them, and
     * `audio/mp4` wins within a band because every Android device decodes AAC while
     * Opus in WebM is a coin flip on older hardware.
     *
     * Internal rather than private so the band ordering is unit-testable without a
     * network — a plausible-looking comparator change would otherwise silently
     * disconnect the settings screen from what actually plays.
     */
    internal fun selectCandidates(
        streams: List<AudioStream>,
        quality: StreamQuality,
    ): List<AudioStream> = when (quality) {
        // The incoming order already is the measured one (mp4, then opus, by bitrate).
        StreamQuality.AUTO -> streams

        StreamQuality.HIGH -> streams.sortedWith(
            compareByDescending<AudioStream> { it.bitrate > LOW_BITRATE_CEILING }
                .thenByDescending { it.bitrate }
                .thenBy { it.containerMimeType != "audio/mp4" },
        )

        StreamQuality.NORMAL -> streams.sortedWith(
            compareByDescending<AudioStream> { it.bitrate <= LOW_BITRATE_CEILING }
                .thenByDescending { it.bitrate }
                .thenBy { it.containerMimeType != "audio/mp4" },
        )

        StreamQuality.LOW -> streams.sortedWith(
            compareBy<AudioStream> { it.bitrate > LOW_BITRATE_CEILING }
                .thenBy { it.bitrate },
        )
    }

    /**
     * Turns one raw format into the address the CDN will actually serve.
     *
     * Three things stand between what `/player` handed back and a URL that plays, and
     * order matters:
     *
     *  1. A signature cipher is unscrambled through the site's own player script.
     *     Bounded, because a player shape the cipher cannot read never finishes, and
     *     one unusable candidate must not stall the rest of the rotation.
     *  2. The throttle (`n`) parameter is solved for web clients: the content server
     *     serves a whole song only to whoever solves it and 403s everybody else.
     *  3. The streaming PO token is attached as `pot=`, without which the media server
     *     serves the first megabyte and then stops — a stream that starts and dies.
     *
     * Returns null when the format cannot be finished (an unsolvable cipher), which
     * removes only that candidate; the next one may be a plain direct URL.
     */
    private suspend fun finalizeStream(
        stream: AudioStream,
        client: ExtractorClient,
        videoId: String,
        tokens: PoTokenResult?,
    ): AudioStream? {
        var url = stream.url

        stream.signatureCipher?.let { cipher ->
            val deciphered = withTimeoutOrNull(CIPHER_TIMEOUT_MS) {
                CipherDeobfuscator.deobfuscateStreamUrl(cipher, videoId)
            }
            if (deciphered == null) {
                Log.d(TAG, "Signature cipher did not resolve for $videoId — skipping candidate")
                return null
            }
            url = deciphered
        }

        if (client.clientName in WEB_CLIENTS) {
            val original = url
            if ("&n=" in original || "?n=" in original || "/n/" in original) {
                // Unchanged means it could not be done, not that there was nothing to
                // do — the transform hands the address back as it found it when the
                // player script is in a shape it cannot read, and that address still
                // carries its throttle. One retry covers the first attempt landing
                // while the shared WebView was being rebuilt after a renderer death.
                val solved = CipherDeobfuscator.transformNParamInUrl(original)
                url = if (solved != original) {
                    solved
                } else {
                    val retried = if ("/n/" in original) {
                        CipherDeobfuscator.transformNParamInPath(original)
                    } else {
                        CipherDeobfuscator.transformNParamInUrl(original)
                    }
                    if (retried != original) {
                        retried
                    } else {
                        Log.w(TAG, "n-transform unavailable for $videoId")
                        original
                    }
                }
            }

            tokens?.streamingDataPoToken
                ?.takeIf { it.isNotBlank() && "pot=" !in url }
                ?.let { pot ->
                    val separator = if ('?' in url) '&' else '?'
                    // Blazify's exact rule: the parameter name is literally "pot", in
                    // front of the encoded token value. A bare "<token>=<token>" (the
                    // token acting as its own parameter name) is what serves the first
                    // megabyte and then 403s, which stops playback at ~1:04.
                    url = "$url$separatorpot=${Uri.encode(pot)}"
                }
        }

        return if (url == stream.url) stream else stream.copy(url = url, signatureCipher = null)
    }

    /**
     * The client family whose media addresses carry the throttle `n` and accept a
     * `pot=` parameter. The native clients in the rotation mint addresses with neither.
     */
    private val WEB_CLIENTS = setOf("WEB", "WEB_REMIX", "WEB_CREATOR", "TVHTML5")

    /** A cipher that never answers is a candidate to skip, not a resolve to abandon. */
    private const val CIPHER_TIMEOUT_MS = 2_500L

    /** Above this a "low" stream stops saving anything a listener would notice. */
    private const val LOW_BITRATE_CEILING = 130_000

    /**
     * Cache access is deliberately *not* suspending.
     *
     * Every caller that needs to drop a stream is a callback — a player error, a stall
     * watchdog — and forcing those into a coroutine to evict a map entry would make
     * the eviction easy to forget at exactly the moment it matters most. Resolution
     * still holds [resolveLock], because that does real network work.
     */
    private fun cached(videoId: String): AudioStream? =
        synchronized(cache) { cache[videoId]?.takeIf { !it.isExpired } }

    private fun store(videoId: String, stream: AudioStream) =
        synchronized(cache) { cache[videoId] = stream }

    /**
     * Drops a cached stream.
     *
     * Called after playback fails, so a dead URL is never replayed and the next attempt
     * goes back to the network for a fresh one.
     */
    fun invalidate(videoId: String) {
        synchronized(cache) { cache.remove(videoId) }
    }

    /**
     * Resolves [videoId] on the player's loading thread, bypassing the cache.
     *
     * The loading thread cannot suspend, so this runs a bounded blocking resolve —
     * [FRESH_RESOLVE_TIMEOUT_MS], tight because this budget sits inside a user's seek:
     * with a warm token WebView the real cost is one player round trip (1-3 s), and a
     * replacement that cannot be minted in time must fail into the player's retry
     * policy quickly instead of leaving the UI spinning. The cache is dropped first
     * and the returned stream — if any — is stored, so the retry that follows heals
     * in place on a fresh URL instead of looping on the same dead one.
     */
    fun resolveFreshBlocking(videoId: String): AudioStream? {
        invalidate(videoId)
        return try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(FRESH_RESOLVE_TIMEOUT_MS) { resolveUncached(videoId) }
            }?.let { outcome ->
                when (outcome) {
                    is Outcome.Success -> {
                        store(videoId, outcome.stream)
                        outcome.stream
                    }
                    is Outcome.Failure -> {
                        Log.w(TAG, "Fresh resolve failed for $videoId: ${outcome.reason}")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fresh resolve crashed for $videoId: ${e.message}")
            null
        }
    }

    /** Clears everything, for a settings change or a manual retry of everything. */
    fun clear() {
        synchronized(cache) { cache.clear() }
        scope.launch { ClientHealth.reset() }
    }
}
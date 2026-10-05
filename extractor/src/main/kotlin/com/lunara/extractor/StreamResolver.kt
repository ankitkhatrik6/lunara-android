package com.lunara.extractor

import android.util.Log
import android.content.Context
import com.lunara.extractor.potoken.PoTokenGenerator
import com.lunara.extractor.potoken.PoTokenResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Turns a video id into a stream that has been proven to serve data.
 *
 * The order of operations is the whole design, so it is worth stating plainly:
 *
 *  1. Mint a PO token. Without one most clients answer `LOGIN_REQUIRED`, and the ones
 *     that do answer cap their media at 1 MiB. This runs first because it is the only
 *     step that unlocks everything else.
 *  2. Ask each client in health order. A rested client is still tried; only later.
 *  3. Take the first stream whose *first bytes* arrive. See [StreamValidator] for why
 *     that is the only check made here.
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
     * Serialises resolution so a burst of taps on one track costs one resolve, not one
     * per tap.
     */
    private val resolveLock = Mutex()

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
        ClientHealth.prewarm()
        warmupScope.launch {
            runCatching { SessionStore.ensure() }
            runCatching { PoTokenGenerator.prewarm(context) }
        }
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
        quality: StreamQuality = StreamQuality.AUTO,
    ): Outcome {
        if (videoId.isBlank()) return Outcome.Failure(ResolveFailure.Unavailable("Empty video id"))

        cached(videoId)?.let { return Outcome.Success(it) }

        return resolveLock.withLock {
            // Another caller may have resolved this while this one waited for the lock.
            cached(videoId)?.let { return@withLock Outcome.Success(it) }

            val visitorData = SessionStore.ensure()
            // One token set serves every client: it is bound to the session, not to a
            // track, so re-minting per client would cost seconds for nothing.
            val tokens = PoTokenGenerator.tokensFor(videoId, visitorData)
            val signatureTimestamp = if (tokens != null) SignatureTimestamp.get() else null

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

            if (outcome is Outcome.Success) store(videoId, outcome.stream)
            outcome
        }
    }
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
            for (candidate in selectCandidates(response.streams, quality)) {
                if (candidate.isExpired) continue
                if (StreamValidator.isPlayable(candidate)) {
                    ClientHealth.recordSuccess(client)
                    Log.i(
                        TAG,
                        "Resolved $videoId via ${client.displayName}: " +
                            "${candidate.bitrate / 1000} kbps ${candidate.containerMimeType}",
                    )
                    return Outcome.Success(candidate)
                }
            }

            // The client said OK and every URL it produced was refused on contact. That
            // is worse than a refusal: the user waited for a stream that was never
            // going to play.
            ClientHealth.recordBadStream(client)
            Log.d(TAG, "${client.displayName} resolved but every URL it returned was refused")
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
     */
    private fun selectCandidates(
        streams: List<AudioStream>,
        quality: StreamQuality,
    ): List<AudioStream> = when (quality) {
        StreamQuality.HIGH, StreamQuality.AUTO -> streams
        StreamQuality.LOW -> streams.sortedWith(
            compareBy<AudioStream> { it.bitrate > LOW_BITRATE_CEILING }
                .thenBy { it.bitrate },
        )
    }

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

    /** Clears everything, for a settings change or a manual retry of everything. */
    fun clear() {
        synchronized(cache) { cache.clear() }
        scope.launch { ClientHealth.reset() }
    }
}
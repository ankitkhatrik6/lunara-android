package com.lunara.extractor

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Remembers which clients are currently working, so a healthy one is tried first.
 *
 * Why this exists
 * ---------------
 * YouTube's answer depends on more than the request: it depends on how this device has
 * been behaving recently. A client that answered five minutes ago may be refused now,
 * and one that has been bot-checked may work again an hour later.
 *
 * Rotating blindly through every client on every play costs a round trip per client on
 * every song, and the user waits through all of them. Rotating through the *same*
 * order wastes the same round trips even when the answer was the first one.
 *
 * So each client carries a score that moves with evidence:
 *  - it answered `OK` with a playable stream: promoted
 *  - it was refused or bot-checked: rested for a while
 *  - it produced a stream that would not play: rested for longer
 *
 * The ordering is a suggestion, not a rule. A rested client is still tried when
 * nothing else works, because a track that plays badly beats a track that does not
 * play at all.
 */
object ClientHealth {

    private const val TAG = "LunaraClientHealth"

    /** How long a refused client is left alone before it is tried again. */
    private const val REFUSED_REST_MS = 4 * 60 * 1000L

    /**
     * Longer than a refusal, because a client that answers `OK` and then hands back a
     * stream that will not play is worse than one that says no: it costs the user a
     * silent buffer instead of a quick move on to the next candidate.
     */
    private const val BAD_STREAM_REST_MS = 10 * 60 * 1000L

    /** Score gained per confirmed good stream. */
    private const val SUCCESS_POINTS = 2

    /** Points lost per refusal. Repeated refusals compound, so a bad client is left. */
    private const val REFUSED_POINTS = 3

    /** Points lost per stream that resolved but would not play. */
    private const val BAD_STREAM_POINTS = 5

    private data class State(
        var score: Int = 0,
        var restedUntil: Long = 0L,
        var consecutiveFailures: Int = 0,
    )

    private val states = LinkedHashMap<String, State>()
    private val lock = Mutex()

    private fun stateFor(key: String): State = states.getOrPut(key) { State() }

    /**
     * The clients to try, best first, with rested ones moved to the back.
     *
     * Order within each group is the registry's measured order, which is the tie-break
     * that keeps behaviour predictable when two clients are equally healthy.
     */
    fun ordered(): List<ExtractorClient> {
        val now = System.currentTimeMillis()
        return ClientRegistry.rotation().sortedWith(
            compareBy<ExtractorClient> { stateFor(it.displayName).restedUntil > now }
                .thenByDescending { stateFor(it.displayName).score },
        )
    }

    /** Records that [client] produced a stream which passed its first-byte check. */
    suspend fun recordSuccess(client: ExtractorClient) = lock.withLock {
        val state = stateFor(client.displayName)
        state.score = (state.score + SUCCESS_POINTS).coerceAtMost(20)
        state.consecutiveFailures = 0
        state.restedUntil = 0L
    }

    /**
     * Records that [client] refused the request outright.
     *
     * `retryable` distinguishes a bot check — which clears on its own — from a hard
     * refusal like a removed video, which no amount of waiting will fix.
     */
    suspend fun recordRefused(client: ExtractorClient, retryable: Boolean) = lock.withLock {
        val state = stateFor(client.displayName)
        state.score = (state.score - REFUSED_POINTS).coerceAtLeast(-20)
        state.consecutiveFailures++
        if (retryable) {
            // Back off further the more often the same client has refused, so a client
            // that is being actively blocked stops being tried within one song.
            val multiplier = state.consecutiveFailures.coerceAtMost(4)
            state.restedUntil = System.currentTimeMillis() + REFUSED_REST_MS * multiplier
        }
        Log.d(TAG, "${client.displayName} refused (score=${state.score})")
    }

    /** Records that [client] answered but the stream it produced would not play. */
    suspend fun recordBadStream(client: ExtractorClient) = lock.withLock {
        val state = stateFor(client.displayName)
        state.score = (state.score - BAD_STREAM_POINTS).coerceAtLeast(-20)
        state.consecutiveFailures++
        state.restedUntil = System.currentTimeMillis() + BAD_STREAM_REST_MS
        Log.d(TAG, "${client.displayName} produced an unplayable stream (score=${state.score})")
    }

    /** Forgets everything, so the next play starts from the measured order again. */
    suspend fun reset() = lock.withLock { states.clear() }

    /**
     * Pays the token generator's cold cost before the first song needs it.
     *
     * Fired from [LunaraExtractor.init] rather than awaited: the app is usable while
     * this runs, and blocking startup on a WebView would delay the home screen for a
     * cost the user only notices later.
     */
    fun prewarm() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { SessionStore.ensure() }
        }
    }
}
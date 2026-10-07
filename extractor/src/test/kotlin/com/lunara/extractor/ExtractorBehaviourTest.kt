package com.lunara.extractor

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks in the behaviours the streaming fix depends on.
 *
 * Each test corresponds to something measured against the live API that a
 * plausible-looking change could silently break again, so these are written to fail
 * loudly rather than to describe the shape of the code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExtractorBehaviourTest {

    //region Container and codec selection

    /**
     * The container must be reported without its codec parameters.
     *
     * ExoPlayer picks its extractor from this string. Handing it `audio/webm;
     * codecs="opus"` makes it look for a file type that does not exist, and a stream
     * that works is reported as an unsupported format.
     */
    @Test
    fun `container strips codec parameters`() {
        assertEquals("audio/webm", stream(mime = "audio/webm; codecs=\"opus\"").containerMimeType)
        assertEquals("audio/mp4", stream(mime = "audio/mp4; codecs=\"mp4a.40.2\"").containerMimeType)
    }

    /** A blank mime type still has to produce something ExoPlayer can act on. */
    @Test
    fun `container falls back to mp4 when mime is blank`() {
        assertEquals("audio/mp4", stream(mime = "").containerMimeType)
    }

    @Test
    fun `opus is detected from the mime type`() {
        assertTrue(stream(mime = "audio/webm; codecs=\"opus\"").isOpus)
        assertFalse(stream(mime = "audio/mp4; codecs=\"mp4a.40.2\"").isOpus)
    }

    //endregion

    //region Expiry

    /**
     * A stream handed out seconds before it dies is a stream that starts and stalls.
     * Five minutes of margin is what keeps that off the playback path.
     */
    @Test
    fun `safe lifetime pulls expiry back before the real deadline`() {
        val now = System.currentTimeMillis()
        val expiringSoon = stream(expiresAtMs = now + 60_000)
        assertTrue(
            "a URL expiring in 60s must not be handed out as-is",
            expiringSoon.withSafeLifetime().expiresAtMs < now,
        )
    }

    /** A stream with no known expiry is not shortened, because there is nothing to shorten. */
    @Test
    fun `safe lifetime leaves an unknown expiry alone`() {
        assertEquals(0L, stream(expiresAtMs = 0L).withSafeLifetime().expiresAtMs)
    }

    @Test
    fun `expiry is read from the googlevideo expire parameter`() {
        // 2026-01-01T00:00:00Z, the kind of value googlevideo actually carries.
        val expirySeconds = 1_767_225_600L
        val url = "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=$expirySeconds&id=x"
        assertEquals(expirySeconds * 1000L, AudioStream.expiryFromUrl(url))
    }

    /**
     * A small `expire` is not a timestamp.
     *
     * Treating one as an instant would expire every stream the moment it is created,
     * which is exactly the "nothing ever plays" failure the extractor exists to fix.
     */
    @Test
    fun `a non-timestamp expire value is ignored`() {
        assertEquals(0L, AudioStream.expiryFromUrl("https://x.googlevideo.com/v?expire=3600"))
        assertEquals(0L, AudioStream.expiryFromUrl("https://x.googlevideo.com/v?expire=abc"))
        assertEquals(0L, AudioStream.expiryFromUrl("https://x.googlevideo.com/v"))
    }

    @Test
    fun `isExpired respects the deadline`() {
        assertTrue(stream(expiresAtMs = System.currentTimeMillis() - 1).isExpired)
        assertFalse(stream(expiresAtMs = System.currentTimeMillis() + 600_000).isExpired)
    }

    //endregion
//region Client registry

    /**
     * The main client must be one that can actually serve unthrottled audio.
     *
     * Leading with a client that needs a cipher worked out of `player.js` means every
     * play pays a round trip that cannot succeed, measured at sixteen seconds a track
     * before this was fixed.
     */
    @Test
    fun `the main client is a po-token client`() {
        assertTrue(
            "the main client must be able to serve unthrottled audio",
            ClientRegistry.MAIN_CLIENT.requiresPoToken,
        )
    }

    /** Clients YouTube has stopped serving must not each cost a network round trip. */
    @Test
    fun `the rotation excludes clients already known to be dead`() {
        val rotation = ClientRegistry.rotation()
        assertTrue(rotation.isNotEmpty())
        assertTrue(
            "a client YouTube no longer serves must not be in the rotation",
            rotation.none { it.knownBroken },
        )
    }

    /**
     * The rotation must start at the main client.
     *
     * Health scoring reorders it at runtime, but the first attempt after a cold start
     * is what a user waiting on the first song actually experiences.
     */
    @Test
    fun `the rotation begins with the main client`() {
        assertEquals(ClientRegistry.MAIN_CLIENT, ClientRegistry.rotation().first())
    }

    //endregion

    //region Client identity

    /**
     * The context must carry the device fields the client claims to be.
     *
     * `ANDROID_VR` is refused unless the request describes the same device its User
     * Agent names, so an incomplete context is worse than a redundant field.
     */
    @Test
    fun `client context includes the fields a device client needs`() {
        val context = ClientRegistry.FALLBACK_CLIENTS
            .first { it.clientName == "ANDROID_VR" }
            .toClientContext(visitorData = "VD", hl = "en", gl = "US")

        assertTrue(context.contains("\"clientName\":\"ANDROID_VR\""))
        assertTrue(context.contains("\"clientVersion\""))
        assertTrue(context.contains("\"osName\":\"Android\""))
        assertTrue(context.contains("\"deviceMake\":\"Oculus\""))
        assertTrue(context.contains("\"androidSdkVersion\""))
    }

    /**
     * A client that does not use a visitor id must not send one.
     *
     * Sending `X-Goog-Visitor-Id` to a family that never asked for it is a mismatch
     * between the identity that minted a URL and the one requesting it.
     */
    @Test
    fun `visitor data is only sent to clients that use it`() {
        val sendsIt = ClientRegistry.MAIN_CLIENT.toClientContext("VD", "en", "US")
        assertTrue(sendsIt.contains("\"visitorData\":\"VD\""))

        val ignoresIt = ClientRegistry.FALLBACK_CLIENTS
            .first { !it.sendsVisitorData }
            .toClientContext("VD", "en", "US")
        assertFalse(ignoresIt.contains("visitorData"))
    }

    /** Every media request must be signed with the identity that minted the URL. */
    @Test
    fun `media headers carry the minting identity`() {
        val client = ClientRegistry.MAIN_CLIENT
        val headers = client.mediaHeaders("VD")
        assertEquals(client.userAgent, headers["User-Agent"])
        assertEquals(client.origin, headers["Origin"])
        assertEquals(client.referer, headers["Referer"])
    }

    /**
     * The context is interpolated into a JSON body, so a value containing a quote must
     * not be able to break out of it.
     */
    @Test
    fun `client context escapes a hostile visitor id`() {
        val context = ClientRegistry.MAIN_CLIENT.toClientContext("V\",\"evil\":\"1", "en", "US")
        assertTrue(context.contains("\\\"evil\\\""))
        assertFalse(context.contains("\"evil\":\"1\""))
    }

    //endregion

    //region Client health

    /**
     * A rested client must stay in the rotation.
     *
     * The mid-song heal rests the minting client whenever a URL dies on contact. If
     * resting *removed* clients, a network where streams die often would rest every
     * client in turn and the next resolve would have nobody to ask — a resolve that
     * fails outright is the "buffers a few seconds, then never starts" failure. The
     * order changes with health; the membership never does.
     */
    @Test
    fun `rested clients stay in the rotation so a resolve always has someone to ask`() =
        runBlocking {
            val all = ClientRegistry.rotation()
            try {
                all.forEach { ClientHealth.recordBadStream(it) }
                ClientHealth.recordRefused(ClientRegistry.MAIN_CLIENT, retryable = true)
                assertEquals(all.toSet(), ClientHealth.ordered().toSet())
            } finally {
                ClientHealth.reset()
            }
        }

    //endregion

    /** A stream is described once here so each test states only what it is about. */
    private fun stream(
        mime: String = "audio/mp4; codecs=\"mp4a.40.2\"",
        expiresAtMs: Long = System.currentTimeMillis() + 3_600_000L,
    ) = AudioStream(
        url = "https://rr3---sn-abc.googlevideo.com/videoplayback?id=x",
        itag = 140,
        mimeType = mime,
        bitrate = 128_000,
        contentLength = 3_449_447L,
        clientName = "TEST",
        headers = mapOf("User-Agent" to "test"),
        expiresAtMs = expiresAtMs,
    )
}
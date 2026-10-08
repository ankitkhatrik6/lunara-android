package com.lunara.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The media header contract, family by family — ported from Metrolist's innertubex
 * `InnerTubeExtractor.buildHeaders`, which is what production YouTube Music playback
 * actually sends.
 *
 * The header-silent rule is the one that matters most for playback: a header on a
 * request the CDN expects to be anonymous is a mismatched identity, and a mismatched
 * identity is what gets a stream capped at the first megabyte — the "plays for about
 * a minute then stops" failure.
 */
class MediaHeadersTest {

    private fun client(name: String) = ExtractorClient(
        clientName = name,
        clientId = "1",
        clientVersion = "1.0.0",
        userAgent = "test-agent",
        apiKey = "key",
        origin = "https://www.youtube.com",
        referer = "https://www.youtube.com/",
    )

    @Test
    fun `header-silent clients get no media headers at all`() {
        for (name in listOf("ANDROID_VR", "VISIONOS", "TVHTML5_SIMPLY")) {
            assertEquals(
                "$name must be header-silent",
                emptyMap<String, String>(),
                client(name).mediaHeaders(),
            )
        }
    }

    @Test
    fun `device clients send ua and language but no origin or referer`() {
        val headers = client("IOS").mediaHeaders()
        assertEquals("test-agent", headers["User-Agent"])
        assertEquals("*/*", headers["Accept"])
        assertEquals("en-US,en;q=0.9", headers["Accept-Language"])
        assertFalse("IOS must not send an Origin", headers.containsKey("Origin"))
        assertFalse("IOS must not send a Referer", headers.containsKey("Referer"))
        assertFalse(headers.containsKey("X-Goog-Visitor-Id"))
    }

    @Test
    fun `web remix signs media with youtube music`() {
        val headers = client("WEB_REMIX").mediaHeaders()
        assertEquals("https://music.youtube.com", headers["Origin"])
        assertEquals("https://music.youtube.com/", headers["Referer"])
    }

    @Test
    fun `web families sign media with youtube`() {
        for (name in listOf("WEB", "WEB_EMBEDDED_PLAYER")) {
            val headers = client(name).mediaHeaders()
            assertEquals(name, "https://www.youtube.com", headers["Origin"])
            assertEquals(name, "https://www.youtube.com/", headers["Referer"])
        }
    }

    @Test
    fun `embedded tv client keeps its ua but gets no web origin`() {
        // TVHTML5_SIMPLY_EMBEDDED_PLAYER is NOT TVHTML5_SIMPLY: only the latter is
        // header-silent, and neither of them signs with a web Origin.
        val headers = client("TVHTML5_SIMPLY_EMBEDDED_PLAYER").mediaHeaders()
        assertEquals("test-agent", headers["User-Agent"])
        assertFalse(headers.containsKey("Origin"))
        assertFalse(headers.containsKey("Referer"))
    }

    @Test
    fun `registry clients keep their real user agents`() {
        val headers = ClientRegistry.MAIN_CLIENT.mediaHeaders()
        assertEquals(ClientRegistry.MAIN_CLIENT.userAgent, headers["User-Agent"])
        assertTrue(headers.containsKey("Accept-Language"))
    }
}

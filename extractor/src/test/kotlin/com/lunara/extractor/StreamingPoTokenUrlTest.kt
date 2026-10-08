package com.lunara.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `pot=` transport, pinned to Metrolist's innertubex `withPoToken` behaviour.
 *
 * Both properties asserted here are load-bearing for full-track playback:
 *
 *  - No space may slip in before `pot=` — `& pot=` is an unrecognised parameter and
 *    the CDN then treats the request as anonymous, serving only the first megabyte
 *    (~1 minute of audio) before the stream stops.
 *  - The token's bytes must survive exactly: standard base64 `+`, `/`, `=` travel as
 *    `%2B`, `%2F`, `%3D` and decode back to the issued token. Rewriting the alphabet
 *    (base64url) hands the server bytes it never issued.
 */
class StreamingPoTokenUrlTest {

    private val base = "https://redirector.googlevideo.com/videoplayback?expire=1&id=2"

    @Test
    fun `token sits directly behind the separator with no space`() {
        val url = base.withStreamingPoToken("TOKEN")
        assertTrue(url.endsWith("&pot=TOKEN"))
        assertFalse("no space before pot=", url.contains(" pot="))
        assertFalse("no encoded space before pot=", url.contains("%20pot="))
    }

    @Test
    fun `first parameter uses the question mark separator`() {
        val url = "https://example.com/videoplayback".withStreamingPoToken("TOKEN")
        assertEquals("https://example.com/videoplayback?pot=TOKEN", url)
    }

    @Test
    fun `standard base64 bytes are percent-encoded not rewritten`() {
        val url = base.withStreamingPoToken("Ab+c/d==")
        assertTrue(url.endsWith("&pot=Ab%2Bc%2Fd%3D%3D"))
        assertFalse("base64url rewriting is forbidden", url.contains("Ab-c_d"))
    }

    @Test
    fun `unreserved characters pass through untouched`() {
        val url = base.withStreamingPoToken("AZaz09-._~")
        assertTrue(url.endsWith("&pot=AZaz09-._~"))
    }

    @Test
    fun `an existing pot parameter is left alone`() {
        val withQuery = "$base&pot=FIRST"
        assertEquals(withQuery, withQuery.withStreamingPoToken("SECOND"))
        val firstParam = "https://example.com/v?pot=FIRST"
        assertEquals(firstParam, firstParam.withStreamingPoToken("SECOND"))
    }

    @Test
    fun `blank or missing tokens change nothing`() {
        assertEquals(base, base.withStreamingPoToken(null))
        assertEquals(base, base.withStreamingPoToken(""))
        assertEquals(base, base.withStreamingPoToken("   "))
    }

    @Test
    fun `fragment stays at the very end of the url`() {
        val url = "https://example.com/v?x=1#frag".withStreamingPoToken("T")
        assertEquals("https://example.com/v?x=1&pot=T#frag", url)
    }
}

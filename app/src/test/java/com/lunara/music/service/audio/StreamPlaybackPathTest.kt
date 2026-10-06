package com.lunara.music.service.audio

import android.net.Uri
import androidx.media3.datasource.DataSpec
import com.lunara.extractor.ResolveFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the playback path Lunara shares with Blazify's streaming design:
 * chunked request-time resolution, identity-header bookkeeping, and the
 * failure wording the player shows when a resolve is refused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamPlaybackPathTest {

    @Before
    fun setUp() {
        StreamHeaders.clear()
    }

    @Test
    fun `chunk spec keeps position and key and caps length at 512 KiB`() {
        val spec = DataSpec.Builder()
            .setUri(Uri.parse("dQw4w9WgXcQ"))
            .setKey("dQw4w9WgXcQ")
            .setPosition(4096L)
            .build()

        val chunk = StreamResolvingDataSource.chunkSpecFor(
            spec,
            "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1&id=x",
        )

        assertEquals(4096L, chunk.position)
        assertEquals(512L * 1024L, chunk.length)
        assertEquals("dQw4w9WgXcQ", chunk.key)
        assertEquals("https", chunk.uri.scheme)
        assertTrue(chunk.uri.toString().startsWith("https://rr3---sn-abc.googlevideo.com/"))
    }

    @Test
    fun `stream headers match the exact url first`() {
        val headers = mapOf("User-Agent" to "agent-a")
        StreamHeaders.register("https://gv.example.com/v?expire=1", headers)

        assertEquals(
            headers,
            StreamHeaders.lookup("https://gv.example.com/v?expire=1"),
        )
    }

    @Test
    fun `stream headers fall back to the same host when the query differs`() {
        val headers = mapOf("User-Agent" to "agent-a")
        StreamHeaders.register(
            "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=1&id=x",
            headers,
        )

        // OkHttp may normalise the URL between registration and the request itself;
        // the identity is per minting client, so the host still finds it.
        assertEquals(
            headers,
            StreamHeaders.lookup("https://rr1---sn-abc.googlevideo.com/videoplayback?expire=1&id=y"),
        )
        assertEquals(
            emptyMap<String, String>(),
            StreamHeaders.lookup("https://other.example.com/videoplayback?id=x"),
        )
    }

    @Test
    fun `stream headers evict the least recently used entry, never an arbitrary one`() {
        val urlAt = { i: Int -> "https://host$i.example.com/v?id=$i" }
        for (i in 0 until 20) {
            StreamHeaders.register(urlAt(i), mapOf("User-Agent" to "agent-$i"))
        }

        // The registry is bounded at 16: the oldest entry is gone, the newest stays,
        // and — unlike the old ConcurrentHashMap sweep — nothing in between was
        // dropped arbitrarily.
        assertEquals(emptyMap<String, String>(), StreamHeaders.lookup(urlAt(0)))
        assertEquals("agent-19", StreamHeaders.lookup(urlAt(19))["User-Agent"])
        assertEquals("agent-16", StreamHeaders.lookup(urlAt(16))["User-Agent"])
    }

    @Test
    fun `resolve failures say something a listener can act on`() {
        assertEquals(
            "This song isn't available on YouTube Music",
            ResolveFailure.Unavailable("Removed").toUserMessage(),
        )
        assertEquals(
            "YouTube is rate-limiting this device. Try again shortly.",
            ResolveFailure.Blocked.toUserMessage(),
        )
        assertEquals(
            "YouTube throttled the stream. Try again in a moment.",
            ResolveFailure.NoPlayableStream.toUserMessage(),
        )
        assertEquals(
            "No connection to YouTube. Check your network.",
            ResolveFailure.Network("timeout").toUserMessage(),
        )
    }
}

package com.lunara.extractor

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks the contract between the Audio Quality setting and the streams it picks.
 *
 * The settings screen writes a human label; `StreamResolver.selectCandidates` is
 * where that label becomes an ordering over real formats. Both sides are private to
 * their own modules' concerns, so these tests pin the ordering itself: a
 * plausible-looking comparator tweak that silently played Data Saver at 256 kbps —
 * or made "Normal" pick the one format the label promises it never would — is
 * exactly the class of regression that is invisible until a user pays for it in
 * mobile data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamQualitySelectionTest {

    /**
     * One video's worth of formats as the catalogue actually serves them: opus in
     * the low band, mp4 and opus side by side at 128k, and a 256k pair above the
     * ceiling. Deliberately shuffled so a test cannot pass by accident of input
     * order.
     */
    private val formats = listOf(
        stream(itag = 251, mime = "audio/webm; codecs=\"opus\"", bitrate = 256_000),
        stream(itag = 140, mime = "audio/mp4; codecs=\"mp4a.40.2\"", bitrate = 128_000),
        stream(itag = 250, mime = "audio/webm; codecs=\"opus\"", bitrate = 128_000),
        stream(itag = 249, mime = "audio/webm; codecs=\"opus\"", bitrate = 48_000),
        stream(itag = 301, mime = "audio/mp4; codecs=\"mp4a.40.2\"", bitrate = 256_000),
    )

    /** AUTO is the measured order the player API already produced; nothing to reorder. */
    @Test
    fun `auto keeps the incoming order untouched`() {
        assertEquals(formats, StreamResolver.selectCandidates(formats, StreamQuality.AUTO))
    }

    /** Data Saver means "the low band, and within it the lightest stream first". */
    @Test
    fun `data saver plays the low band first, lightest leading`() {
        val picked = StreamResolver.selectCandidates(formats, StreamQuality.LOW)

        assertEquals(listOf(249, 140, 250, 251, 301), picked.map { it.itag })
    }

    /** "Normal (128 kbps)" must lead with the 128k class and hold 256k in reserve. */
    @Test
    fun `normal leads with the 128 class and keeps higher bitrates as fallback`() {
        val picked = StreamResolver.selectCandidates(formats, StreamQuality.NORMAL)

        // 140 (128k mp4) beats 250 (128k opus) as the tie-break: AAC decodes
        // everywhere, Opus does not. The 48k stream follows inside the same band,
        // and the 256k pair is reachable but never chosen while a band member plays.
        assertEquals(listOf(140, 250, 249, 301, 251), picked.map { it.itag })
        assertEquals(128_000, picked.first().bitrate)
    }

    /** "High Quality (256 kbps)" must lead with the pair above the ceiling. */
    @Test
    fun `high leads with the 256 class, mp4 as the tie-break`() {
        val picked = StreamResolver.selectCandidates(formats, StreamQuality.HIGH)

        assertEquals(listOf(301, 251, 140, 250, 249), picked.map { it.itag })
        assertEquals(256_000, picked.first().bitrate)
    }

    /** No candidate may ever be dropped: the next one down may be the only one served. */
    @Test
    fun `every quality keeps every candidate`() {
        StreamQuality.entries.forEach { quality ->
            val picked = StreamResolver.selectCandidates(formats, quality)
            assertEquals("quality $quality dropped a candidate", formats.size, picked.size)
            assertEquals(formats.map { it.itag }.toSet(), picked.map { it.itag }.toSet())
        }
    }

    private fun stream(itag: Int, mime: String, bitrate: Int) = AudioStream(
        url = "https://rr3---sn-abc.googlevideo.com/videoplayback?id=$itag",
        itag = itag,
        mimeType = mime,
        bitrate = bitrate,
        contentLength = 3_449_447L,
        clientName = "TEST",
        headers = mapOf("User-Agent" to "test"),
        expiresAtMs = System.currentTimeMillis() + 3_600_000L,
    )
}
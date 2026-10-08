package com.lunara.music.service.audio

import com.lunara.extractor.StreamQuality
import com.lunara.music.data.models.streamQualityFor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the two playback preferences Lunara inherited from the InnerTune family:
 * volume normalization off YouTube's loudness figure, and the Audio Quality label
 * turning into an actual stream-selection decision. Both are pure functions on
 * purpose — the interesting failure is a silent arithmetic or mapping change, not
 * an Android interaction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackPreferencesTest {

    //region Volume normalization

    /** Six decibels down is half the amplitude — the factor must say so. */
    @Test
    fun `a loud track is attenuated by exactly its loudness`() {
        assertEquals(0.501f, normalizationGainFor(6.0, enabled = true), 0.005f)
        assertEquals(0.251f, normalizationGainFor(12.0, enabled = true), 0.005f)
    }

    /** Quiet tracks are left alone; boosting a mixed-quiet master only clips. */
    @Test
    fun `a quiet track is never boosted`() {
        assertEquals(1f, normalizationGainFor(-6.0, enabled = true), 0f)
        assertEquals(1f, normalizationGainFor(-20.0, enabled = true), 0f)
    }

    /** No figure (local file, unreported) and a disabled toggle both mean unity. */
    @Test
    fun `no figure and a disabled setting both mean unity`() {
        assertEquals(1f, normalizationGainFor(null, enabled = true), 0f)
        assertEquals(1f, normalizationGainFor(6.0, enabled = false), 0f)
    }

    //endregion

    //region Quality label mapping

    /** The three labels the settings dialog writes, each to the stream it promises. */
    @Test
    fun `stored labels map to the streams they promise`() {
        assertEquals(StreamQuality.HIGH, streamQualityFor("High Quality (256 kbps)"))
        assertEquals(StreamQuality.NORMAL, streamQualityFor("Normal (128 kbps)"))
        assertEquals(StreamQuality.LOW, streamQualityFor("Data Saver (64 kbps)"))
    }

    /** Nothing chosen yet must match what the dialog shows as selected: Normal. */
    @Test
    fun `an unset or unreadable label falls back to what the dialog shows`() {
        assertEquals(StreamQuality.NORMAL, streamQualityFor(null))
        assertEquals(StreamQuality.NORMAL, streamQualityFor(""))
        assertEquals(StreamQuality.NORMAL, streamQualityFor("something else"))
    }

    //endregion
}

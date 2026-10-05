package com.maxrave.media3.service.callback

import com.maxrave.domain.data.model.browse.album.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackResumptionQueueTest {
    @Test
    fun resumeRetainsTheCurrentTrackIndexAndSavedPosition() {
        val restored = playbackResumptionQueue(track("second"), listOf(track("first"), track("second")), "42000", "Queue")
        assertEquals(listOf("first", "second"), restored.tracks.map { it.videoId })
        assertEquals(1, restored.startIndex)
        assertEquals(42000L, restored.positionMs)
    }

    @Test
    fun incompleteSaveAddsCurrentTrackAndRemovesDuplicateOrEmptyIds() {
        val restored =
            playbackResumptionQueue(
                track("current"),
                listOf(track("next"), track(""), track("next")),
                "invalid",
                "Queue",
            )
        assertEquals(listOf("current", "next"), restored.tracks.map { it.videoId })
        assertEquals(0, restored.startIndex)
        assertEquals(0L, restored.positionMs)
    }

    @Test
    fun negativePositionCannotProduceAnInvalidSeek() {
        assertEquals(0L, playbackResumptionQueue(track("current"), emptyList(), "-20", "").positionMs)
    }

    private fun track(id: String) =
        Track(
            album = null,
            artists = null,
            duration = null,
            durationSeconds = null,
            isAvailable = true,
            isExplicit = false,
            likeStatus = null,
            thumbnails = null,
            title = id,
            videoId = id,
            videoType = null,
            category = null,
            feedbackTokens = null,
            resultType = null,
        )
}

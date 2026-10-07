package com.maxrave.domain.utils

import com.maxrave.domain.manager.*
import com.maxrave.domain.data.model.browse.album.Track
import kotlin.test.*
import com.maxrave.domain.extension.toGenericMediaItem
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.common.MERGING_DATA_TYPE
import kotlinx.serialization.json.Json

class PlayerPreferencesTest {
    private fun track(id: String) = Track(null, null, null, 180, true, false, null, null, id, id, "VIDEO", null, null, "video")
    private fun entry(id: String, position: Long = 30_000, timestamp: Long = 1) = VideoWatchEntry(track(id), position, 180_000, timestamp)

    @Test fun historyRetainsPositionsAndOnlyOneEntryPerVideo() {
        val result = updatedWatchHistory(listOf(entry("one"), entry("two")), entry("one", 60_000, 2))
        assertEquals(listOf("one", "two"), result.map { it.track.videoId })
        assertEquals(60_000L, result[0].resumePositionMs)
    }
    @Test fun delayedSaveCannotRewindHistory() {
        val recent = listOf(entry("one", 80_000, 3))
        assertEquals(recent, updatedWatchHistory(recent, entry("one", 10_000, 2)))
    }
    @Test fun historyIsBoundedAndBadStorageRecovers() {
        val entries = (1..120).map { entry(it.toString(), timestamp = it.toLong()) }
        assertEquals(100, updatedWatchHistory(entries, entry("new", timestamp = 121)).size)
        assertEquals(emptyList(), decodeWatchHistory("broken"))
        assertEquals(VideoFeedFilters(), decodeFeedFilters("broken"))
    }
    @Test fun completedVideosRestartAndUnfinishedOnesResume() {
        assertEquals(0L, entry("one", 176_000).resumePositionMs)
        assertEquals(0L, entry("one", 2_000).resumePositionMs)
        assertEquals(30_000L, entry("one").resumePositionMs)
    }
    @Test fun filtersHideVideosAndNormalizedChannels() {
        val filters = VideoFeedFilters(setOf("hidden"), setOf("my channel"))
        assertFalse(filters.allows("hidden", "Other"))
        assertFalse(filters.allows("another", " My Channel "))
        assertTrue(filters.allows("another", "Other"))
    }
    @Test fun qualityUsesNetworkDefaultsAndOnlyMatchingVideoOverrides() {
        assertEquals("1080p", preferredVideoQuality(true, "Auto", "Auto", null, "one"))
        assertEquals("360p", preferredVideoQuality(false, "Auto", "Auto", null, "one"))
        assertEquals("720p", preferredVideoQuality(false, "1080p", "720p", "other:1080p", "one"))
        assertEquals("1080p", preferredVideoQuality(false, "1080p", "360p", "one:1080p", "one"))
    }
    @Test fun videoWithSquareOrEmptyArtworkStillUsesVideoSource() {
        val video = track("one").copy(thumbnails = listOf(Thumbnail(640, "https://example.test/square", 640)))
        assertEquals(MERGING_DATA_TYPE.VIDEO, video.toGenericMediaItem().metadata.description)
        assertEquals(MERGING_DATA_TYPE.VIDEO, video.copy(thumbnails = emptyList()).toGenericMediaItem().metadata.description)
    }
    @Test fun savedHistoryRepairsVideoIdentityFromMusicMetadata() {
        val old = listOf(entry("one").copy(track = track("one").copy(videoType = null, resultType = null)))
        val json = Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(VideoWatchEntry.serializer()), old)
        assertEquals("VIDEO", decodeWatchHistory(json).single().track.videoType)
    }
}

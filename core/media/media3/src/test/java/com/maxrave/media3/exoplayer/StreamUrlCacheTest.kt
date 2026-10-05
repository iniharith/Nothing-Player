package com.maxrave.media3.exoplayer

import kotlinx.datetime.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamUrlCacheTest {
    private val start = LocalDateTime(2026, 10, 3, 12, 0)
    private val expiry = LocalDateTime(2026, 10, 3, 12, 30)

    @Test
    fun audioAndVideoForTheSameTrackStaySeparate() {
        val cache = StreamUrlCache(currentTime = { start })
        cache.put("song", "https://audio", expiry)
        cache.put("Video_song", "https://video", expiry)

        assertEquals("https://audio", cache["song"])
        assertEquals("https://video", cache["Video_song"])
        cache.invalidate("song")
        assertNull(cache["song"])
        assertEquals("https://video", cache["Video_song"])
    }

    @Test
    fun expiredUrlsAreNotReusedEvenAtTheExactExpiry() {
        var clock = start
        val cache = StreamUrlCache(currentTime = { clock })
        cache.put("song", "https://expired", expiry)
        clock = expiry

        assertNull(cache["song"])
        cache.put("song", "https://already-expired", expiry)
        assertNull(cache["song"])
    }

    @Test
    fun activeEntriesAreBoundedAndEvictTheLeastRecentlyUsedUrl() {
        val cache = StreamUrlCache(maxEntries = 2, currentTime = { start })
        cache.put("a", "https://a", expiry)
        cache.put("b", "https://b", expiry)
        assertEquals("https://a", cache["a"])
        cache.put("c", "https://c", expiry)

        assertNull(cache["b"])
        assertEquals("https://a", cache["a"])
        assertEquals("https://c", cache["c"])
    }
}

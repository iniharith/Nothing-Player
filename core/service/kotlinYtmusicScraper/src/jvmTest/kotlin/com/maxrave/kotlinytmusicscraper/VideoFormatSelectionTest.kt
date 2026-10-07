package com.maxrave.kotlinytmusicscraper

import com.maxrave.kotlinytmusicscraper.models.response.PlayerResponse
import kotlinx.serialization.json.Json
import kotlin.test.*

class VideoFormatSelectionTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private fun stream(itag: Int, height: Int, mime: String = "video/mp4", url: String = "https://example.test/video") =
        json.decodeFromString<PlayerResponse.StreamingData.Format>("""{"itag":$itag,"url":"$url","mimeType":"$mime","bitrate":1000000,"width":1920,"height":$height,"quality":"hd","fps":60}""")

    @Test fun chooses1080EvenWhenOnlyHighFrameRateItagIsAvailable() {
        val formats = listOf(stream(18, 360), stream(136, 720), stream(299, 1080))
        assertEquals(299, selectVideoFormat(formats, 1080)?.itag)
        assertEquals(136, selectVideoFormat(formats, 720)?.itag)
        assertEquals(18, selectVideoFormat(formats, 360)?.itag)
    }
    @Test fun webm1080BeatsLowerResolutionMp4() {
        assertEquals(248, selectVideoFormat(listOf(stream(18, 360), stream(248, 1080, "video/webm")), 1080)?.itag)
    }
    @Test fun unavailableQualityFallsBackWithoutChoosingAnEmptyUrl() {
        assertEquals(136, selectVideoFormat(listOf(stream(137, 1080, url = ""), stream(136, 720)), 1080)?.itag)
        assertNull(selectVideoFormat(emptyList(), 1080))
    }
}

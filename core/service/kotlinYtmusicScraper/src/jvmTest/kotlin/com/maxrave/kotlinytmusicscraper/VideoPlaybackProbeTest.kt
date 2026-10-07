package com.maxrave.kotlinytmusicscraper

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.URI

class VideoPlaybackProbeTest {
    @Test fun probeRegularYouTubeSearch() = runBlocking {
        if (System.getenv("NOTHING_VIDEO_PROBE") != "1") return@runBlocking
        withTimeout(60_000) {
            val results = YouTube().searchRegularVideos("Big Buck Bunny Blender").getOrThrow()
            println("VIDEO_SEARCH_PROBE results=${results.size} first=${results.firstOrNull()?.title}")
            assertTrue(results.isNotEmpty(), "Regular YouTube search must return videos")
            assertTrue(results.all { it.id.matches(Regex("[A-Za-z0-9_-]{11}")) }, "Results need usable YouTube video IDs")
        }
    }

    @Test fun probeYouTubeCaptions() = runBlocking {
        if (System.getenv("NOTHING_VIDEO_PROBE") != "1") return@runBlocking
        withTimeout(90_000) {
            val captions = YouTube().getYouTubeCaption("H14bBuluwB8", "en").getOrThrow()
            println("CAPTIONS_PROBE original=${captions.first.text.size} translated=${captions.second?.text?.size}")
            assertTrue(captions.first.text.isNotEmpty(), "Captioned video needs timed text")
        }
    }

    @Test fun probeRegularYouTubeVideo() = runBlocking {
        if (System.getenv("NOTHING_VIDEO_PROBE") != "1") return@runBlocking
        withTimeout(120_000) {
            val result = YouTube().player("aqz-KE-bpKQ").getOrThrow()
            val formats = result.second.streamingData?.let { it.formats.orEmpty() + it.adaptiveFormats }.orEmpty()
            val audio = formats.firstOrNull { it.isAudio && !it.url.isNullOrBlank() }
            val video = formats.firstOrNull { !it.isAudio && it.width != null && !it.url.isNullOrBlank() }
            println("VIDEO_PROBE title=${result.second.videoDetails?.title} type=${result.third} audio=${audio?.itag} video=${video?.itag}")
            assertTrue(audio != null && video != null, "Regular video needs both audio and video URLs")
            for (format in listOf(audio!!, video!!)) {
                val connection = URI(format.url!!).toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("Range", "bytes=0-1023")
                try {
                    val status = connection.responseCode
                    println("VIDEO_PROBE itag=${format.itag} status=$status")
                    assertTrue(status in 200..299, "Stream must be reachable")
                    connection.inputStream.use { assertTrue(it.read() >= 0, "Stream must return bytes") }
                } finally { connection.disconnect() }
            }
        }
    }
    @Test fun probe1080VideoSelection() = runBlocking {
        if (System.getenv("NOTHING_VIDEO_PROBE") != "1") return@runBlocking
        withTimeout(120_000) {
            val response = YouTube().videoPlayer("aqz-KE-bpKQ").getOrThrow().second
            val formats = response.streamingData?.let { it.formats.orEmpty() + it.adaptiveFormats }.orEmpty()
            val selected = selectVideoFormat(formats, 1080)
            println("QUALITY_PROBE heights=${formats.filter { !it.isAudio }.map { it.height }.distinct()} selected=${selected?.itag}/${selected?.height}")
            assertTrue(selected?.height == 1080, "1080p selection must find the sample's 1080p adaptive stream")
            val connection = URI(selected!!.url!!).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("Range", "bytes=0-1023")
            try {
                val status = connection.responseCode
                println("QUALITY_PROBE status=$status")
                assertTrue(status in 200..299, "Selected high-quality stream must be reachable")
                connection.inputStream.use { assertTrue(it.read() >= 0) }
            } finally { connection.disconnect() }
        }
    }
    @Test fun probeVideoSearchPagination() = runBlocking {
        if (System.getenv("NOTHING_VIDEO_PROBE") != "1") return@runBlocking
        withTimeout(90_000) {
            val youtube = YouTube()
            val first = youtube.regularVideosPage("Big Buck Bunny Blender").getOrThrow()
            assertTrue(first.videos.isNotEmpty())
            val token = first.continuation
            assertTrue(!token.isNullOrBlank(), "Search must preserve YouTube's next page")
            val second = youtube.regularVideosPage("Big Buck Bunny Blender", token).getOrThrow()
            val combined = (first.videos + second.videos).distinctBy { it.id }
            println("PAGINATION_PROBE first=${first.videos.size} second=${second.videos.size} combined=${combined.size}")
            assertTrue(combined.size > first.videos.size, "Next page must add new videos")
        }
    }
}

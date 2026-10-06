package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.response.DownloadProgress

actual class Extractor {
    actual fun regularVideoPlayer(videoId: String): com.maxrave.kotlinytmusicscraper.models.response.PlayerResponse = throw UnsupportedOperationException("YouTube video extraction is not available on iOS")

    actual fun searchVideos(query: String): List<RegularVideo> = throw UnsupportedOperationException("YouTube video search is not available on iOS")

    actual fun init() {
    }

    actual fun logIn(cookie: String?) {}

    actual fun newPipePlayer(videoId: String): List<Pair<Int, String>> = emptyList()

    actual fun mergeAudioVideoDownload(filePath: String): DownloadProgress = DownloadProgress.failed("Not supported on iOS")

    actual fun saveAudioWithThumbnail(
        filePath: String,
        track: SongItem,
    ): DownloadProgress = DownloadProgress.failed("Not supported on iOS")
}
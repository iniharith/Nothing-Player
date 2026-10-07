package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.response.DownloadProgress

data class RegularVideo(val id: String, val title: String, val channel: String, val durationSeconds: Int, val thumbnail: String)

data class RegularVideoPage(val videos: List<RegularVideo>, val continuation: String? = null)

@kotlinx.serialization.Serializable
internal data class VideoSearchContinuation(
    val url: String? = null,
    val id: String? = null,
    val ids: List<String>? = null,
    val cookies: Map<String, String>? = null,
    val body: ByteArray? = null,
)

expect class Extractor() {
    fun regularVideoPlayer(videoId: String): com.maxrave.kotlinytmusicscraper.models.response.PlayerResponse

    fun searchVideos(query: String): List<RegularVideo>

    fun searchVideosPage(query: String, continuation: String? = null): RegularVideoPage

    fun init()

    fun logIn(cookie: String?)

    fun mergeAudioVideoDownload(filePath: String): DownloadProgress

    fun saveAudioWithThumbnail(
        filePath: String,
        track: SongItem,
    ): DownloadProgress

    fun newPipePlayer(videoId: String): List<Pair<Int, String>>
}
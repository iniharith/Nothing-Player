package com.maxrave.domain.manager

import com.maxrave.domain.data.model.browse.album.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val VIDEO_HISTORY = "video_watch_history_v1"
const val VIDEO_FEED_FILTERS = "video_feed_filters_v1"
const val VIDEO_WIFI_QUALITY = "video_wifi_quality"
const val VIDEO_MOBILE_QUALITY = "video_mobile_quality"
const val VIDEO_QUALITY_OVERRIDE = "video_quality_override"
const val CAPTION_LANGUAGE = "video_caption_language"
const val CAPTION_SIZE = "video_caption_size"
private val videoPreferencesMutex = Mutex()

@Serializable
data class VideoWatchEntry(val track: Track, val positionMs: Long, val durationMs: Long, val watchedAt: Long) {
    val resumePositionMs: Long get() = if (positionMs < 5_000 || (durationMs > 0 && positionMs >= durationMs - 10_000)) 0 else positionMs
}

@Serializable
data class VideoFeedFilters(val videos: Set<String> = emptySet(), val channels: Set<String> = emptySet()) {
    fun allows(id: String, channel: String): Boolean = id !in videos && channel.trim().lowercase() !in channels
}

fun decodeWatchHistory(json: String?): List<VideoWatchEntry> = runCatching {
    Json.decodeFromString<List<VideoWatchEntry>>(json ?: "[]").map { it.copy(track = it.track.copy(videoType = "VIDEO", resultType = "video")) }
}.getOrDefault(emptyList())

fun decodeFeedFilters(json: String?): VideoFeedFilters = runCatching {
    Json.decodeFromString<VideoFeedFilters>(json ?: "{}")
}.getOrDefault(VideoFeedFilters())

fun updatedWatchHistory(previous: List<VideoWatchEntry>, entry: VideoWatchEntry): List<VideoWatchEntry> {
    if (previous.any { it.track.videoId == entry.track.videoId && it.watchedAt > entry.watchedAt }) return previous
    return (listOf(entry.copy(track = entry.track.copy(videoType = "VIDEO", resultType = "video"), positionMs = entry.positionMs.coerceAtLeast(0), durationMs = entry.durationMs.coerceAtLeast(0))) + previous.filter { it.track.videoId != entry.track.videoId }).sortedByDescending { it.watchedAt }.take(100)
}

fun DataStoreManager.videoWatchHistory(): Flow<List<VideoWatchEntry>> = getString(VIDEO_HISTORY).map(::decodeWatchHistory)
fun DataStoreManager.videoFeedFilters(): Flow<VideoFeedFilters> = getString(VIDEO_FEED_FILTERS).map(::decodeFeedFilters)

suspend fun DataStoreManager.recordVideoWatch(entry: VideoWatchEntry) = videoPreferencesMutex.withLock {
    val updated = updatedWatchHistory(decodeWatchHistory(getString(VIDEO_HISTORY).first()), entry)
    putString(VIDEO_HISTORY, Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(VideoWatchEntry.serializer()), updated))
}

suspend fun DataStoreManager.hideFeedVideo(id: String) = videoPreferencesMutex.withLock {
    val filters = decodeFeedFilters(getString(VIDEO_FEED_FILTERS).first())
    putString(VIDEO_FEED_FILTERS, Json.encodeToString(VideoFeedFilters.serializer(), filters.copy(videos = (filters.videos + id).takeLastSet(500))))
}

suspend fun DataStoreManager.hideFeedChannel(channel: String) = videoPreferencesMutex.withLock {
    val name = channel.trim().lowercase()
    if (name.isNotEmpty()) {
        val filters = decodeFeedFilters(getString(VIDEO_FEED_FILTERS).first())
        putString(VIDEO_FEED_FILTERS, Json.encodeToString(VideoFeedFilters.serializer(), filters.copy(channels = (filters.channels + name).takeLastSet(500))))
    }
}
private fun Set<String>.takeLastSet(count: Int): Set<String> = toList().takeLast(count).toSet()

fun DataStoreManager.songLyricsOffset(id: String?): Flow<Int> = combine(lyricsOffset, getString("lyrics_offset_song_${id.orEmpty().removePrefix("Video")}")) { global, saved -> saved?.toIntOrNull()?.coerceIn(-10_000, 10_000) ?: global }
suspend fun DataStoreManager.setSongLyricsOffset(id: String, offset: Int) = putString("lyrics_offset_song_${id.removePrefix("Video")}", offset.coerceIn(-10_000, 10_000).toString())

/** Auto uses a conservative quality on metered networks and a higher quality on Wi-Fi. */
fun preferredVideoQuality(wifi: Boolean, wifiPreference: String?, mobilePreference: String?, override: String?, id: String): String {
    val manual = override?.takeIf { it.substringBefore(':') == id }?.substringAfter(':')?.takeIf { it in listOf("1080p", "720p", "360p") }
    if (manual != null) return manual
    val value = if (wifi) wifiPreference else mobilePreference
    return value?.takeIf { it in listOf("1080p", "720p", "360p") } ?: if (wifi) "1080p" else "360p"
}

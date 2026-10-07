package com.maxrave.kotlinytmusicscraper

import com.maxrave.kotlinytmusicscraper.models.response.PlayerResponse

/** Choose by resolution rather than a single codec/frame-rate-specific YouTube itag. */
fun selectVideoFormat(formats: List<PlayerResponse.StreamingData.Format>, targetHeight: Int): PlayerResponse.StreamingData.Format? {
    val candidates = formats.filter { !it.isAudio && !it.url.isNullOrBlank() && (it.height ?: 0) > 0 }
    val height = candidates.mapNotNull { it.height }.filter { it <= targetHeight }.maxOrNull()
        ?: candidates.mapNotNull { it.height }.minOrNull() ?: return null
    return candidates.filter { it.height == height }.maxWithOrNull(
        compareBy<PlayerResponse.StreamingData.Format> { it.mimeType.startsWith("video/mp4") }
            .thenBy { it.fps ?: 0 }.thenBy { it.bitrate },
    )
}

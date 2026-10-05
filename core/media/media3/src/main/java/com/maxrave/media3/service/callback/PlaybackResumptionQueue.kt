package com.maxrave.media3.service.callback

import com.maxrave.domain.data.model.browse.album.Track

internal data class PlaybackResumptionQueue(
    val tracks: List<Track>,
    val startIndex: Int,
    val positionMs: Long,
    val playlistName: String,
)

/** Repair incomplete saves while retaining the order and the saved current track's position. */
internal fun playbackResumptionQueue(
    currentTrack: Track,
    savedTracks: List<Track>,
    savedPosition: String,
    playlistName: String,
): PlaybackResumptionQueue {
    val tracks = savedTracks.filter { it.videoId.isNotBlank() }.distinctBy { it.videoId }
    val currentIndex = tracks.indexOfFirst { it.videoId == currentTrack.videoId }
    val restoredTracks = if (currentIndex < 0) listOf(currentTrack) + tracks else tracks
    return PlaybackResumptionQueue(
        tracks = restoredTracks,
        startIndex = currentIndex.coerceAtLeast(0),
        positionMs = (savedPosition.toLongOrNull() ?: 0L).coerceAtLeast(0L),
        playlistName = playlistName,
    )
}

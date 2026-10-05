package com.maxrave.media3.service

internal object PlaybackServicePolicy {
    fun shouldContinuePlayback(
        playWhenReady: Boolean,
        mediaItemCount: Int,
        readyOrBuffering: Boolean,
        stopOnExit: Boolean,
    ): Boolean = !stopOnExit && playWhenReady && mediaItemCount > 0 && readyOrBuffering
}

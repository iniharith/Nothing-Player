package com.maxrave.media3.service

internal object PlaybackServicePolicy {
    fun shouldKeepForeground(reportedRequired: Boolean, playWhenReady: Boolean, mediaItemCount: Int, transientFocusLoss: Boolean): Boolean =
        reportedRequired || (playWhenReady && mediaItemCount > 0 && transientFocusLoss)

    fun shouldContinuePlayback(
        playWhenReady: Boolean,
        mediaItemCount: Int,
        readyOrBuffering: Boolean,
        stopOnExit: Boolean,
    ): Boolean = !stopOnExit && playWhenReady && mediaItemCount > 0 && readyOrBuffering
}

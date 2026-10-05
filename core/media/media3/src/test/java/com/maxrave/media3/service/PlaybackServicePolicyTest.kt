package com.maxrave.media3.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackServicePolicyTest {
    @Test fun dismissedPausedOrEmptySessionStops() {
        assertFalse(PlaybackServicePolicy.shouldContinuePlayback(false, 3, true, false))
        assertFalse(PlaybackServicePolicy.shouldContinuePlayback(true, 0, true, false))
        assertFalse(PlaybackServicePolicy.shouldContinuePlayback(true, 3, false, false))
    }

    @Test fun bufferingOrTemporarilySuppressedPlaybackContinues() {
        assertTrue(PlaybackServicePolicy.shouldContinuePlayback(true, 3, true, false))
    }

    @Test fun explicitStopOnExitWins() {
        assertFalse(PlaybackServicePolicy.shouldContinuePlayback(true, 3, true, true))
    }
}

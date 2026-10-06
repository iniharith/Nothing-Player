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

    @Test fun temporaryInterruptionKeepsPlaybackServiceWhileUserPauseDoesNot() {
        assertTrue(PlaybackServicePolicy.shouldKeepForeground(false, true, 1, true))
        assertFalse(PlaybackServicePolicy.shouldKeepForeground(false, false, 1, true))
        assertFalse(PlaybackServicePolicy.shouldKeepForeground(false, true, 0, true))
        assertFalse(PlaybackServicePolicy.shouldKeepForeground(false, false, 1, false))
        assertTrue(PlaybackServicePolicy.shouldKeepForeground(true, true, 1, false))
    }

    @Test fun explicitStopOnExitWins() {
        assertFalse(PlaybackServicePolicy.shouldContinuePlayback(true, 3, true, true))
    }
}

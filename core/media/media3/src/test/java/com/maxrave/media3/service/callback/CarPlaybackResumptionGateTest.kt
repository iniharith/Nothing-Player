package com.maxrave.media3.service.callback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlaybackResumptionGateTest {
    @Test
    fun oneResumeForMultipleCarControllers() {
        val gate = CarPlaybackResumptionGate<String>()
        assertTrue(gate.connect("browser"))
        assertFalse(gate.connect("browser"))
        assertFalse(gate.connect("playback"))
        assertTrue(gate.canAutomaticallyResume())
    }

    @Test
    fun pauseCancelsResumeAndAdditionalControllersCannotRestartIt() {
        val gate = CarPlaybackResumptionGate<String>()
        gate.connect("browser")
        gate.onUserPause()
        assertFalse(gate.canAutomaticallyResume())
        assertFalse(gate.connect("playback"))
        assertFalse(gate.canAutomaticallyResume())
        assertFalse(gate.disconnect("browser"))
        assertFalse(gate.canAutomaticallyResume())
    }

    @Test
    fun completeDisconnectAllowsResumeOnTheNextCarConnection() {
        val gate = CarPlaybackResumptionGate<String>()
        gate.connect("browser")
        gate.onUserPause()
        assertTrue(gate.disconnect("browser"))
        assertFalse(gate.canAutomaticallyResume())
        assertTrue(gate.connect("next trip"))
        assertTrue(gate.canAutomaticallyResume())
    }
}

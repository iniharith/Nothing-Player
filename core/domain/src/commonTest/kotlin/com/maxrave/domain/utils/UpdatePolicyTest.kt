package com.maxrave.domain.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdatePolicyTest {
    @Test
    fun comparesNumericVersionComponents() {
        assertTrue(UpdatePolicy.isNewerRelease("v2.10", "2.9"))
        assertTrue(UpdatePolicy.isNewerRelease("v2.0.1", "2.0"))
        assertFalse(UpdatePolicy.isNewerRelease("v1.99", "2.0"))
    }

    @Test
    fun equalVersionsAndBuildMetadataAreNotUpdates() {
        assertFalse(UpdatePolicy.isNewerRelease("v2.0", "2.0.0"))
        assertFalse(UpdatePolicy.isNewerRelease("v2.0.0+release", "2.0+local"))
    }

    @Test
    fun stableReleaseFollowsPrerelease() {
        assertTrue(UpdatePolicy.isNewerRelease("v2.0", "2.0-rc.2"))
        assertFalse(UpdatePolicy.isNewerRelease("v2.0-rc.2", "2.0"))
        assertTrue(UpdatePolicy.isNewerRelease("v2.0-rc.10", "2.0-rc.2"))
    }

    @Test
    fun invalidOrNamedTagsDoNotProduceFalseUpdatePrompts() {
        assertFalse(UpdatePolicy.isNewerRelease("nightly", "2.0"))
        assertFalse(UpdatePolicy.isNewerRelease("v3.0", ""))
        assertFalse(UpdatePolicy.isNewerRelease("v99999999999999999999.0", "2.0"))
    }

    @Test
    fun checksOncePerDayAndAfterClockMovesBack() {
        val lastCheck = UpdatePolicy.CHECK_INTERVAL_MILLIS
        assertTrue(UpdatePolicy.isCheckDue(lastCheck, 0L))
        assertFalse(UpdatePolicy.isCheckDue(lastCheck + 1, lastCheck))
        assertTrue(UpdatePolicy.isCheckDue(lastCheck + UpdatePolicy.CHECK_INTERVAL_MILLIS, lastCheck))
        assertTrue(UpdatePolicy.isCheckDue(lastCheck - 1, lastCheck))
    }
}

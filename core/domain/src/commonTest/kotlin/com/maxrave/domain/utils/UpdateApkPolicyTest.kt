package com.maxrave.domain.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateApkPolicyTest {
    private val base = "https://github.com/iniharith/Nothing-Player/releases/download/v2.0.2/"
    @Test fun prefersReleaseApkOverUniversalAlternative() {
        val expected = base + "Nothing-Player-2.0.2-release.apk"
        assertEquals(expected, UpdateApkPolicy.selectApk("v2.0.2", listOf("universal.apk" to base + "universal.apk", "Nothing-Player-2.0.2-release.apk" to expected)))
    }
    @Test fun rejectsDebugAndUntrustedDownloads() {
        assertNull(UpdateApkPolicy.selectApk("v2.0.2", listOf("universal-debug.apk" to base + "debug.apk", "universal.apk" to "https://example.com/app.apk")))
    }
    @Test fun missingUniversalApkKeepsReleasePageFallback() {
        assertNull(UpdateApkPolicy.selectApk("v2.0.2", listOf("arm64.apk" to base + "arm64.apk", null to null)))
        assertEquals(base + "universal.apk", UpdateApkPolicy.selectApk("v2.0.2", listOf("universal.apk" to base + "universal.apk")))
    }
}

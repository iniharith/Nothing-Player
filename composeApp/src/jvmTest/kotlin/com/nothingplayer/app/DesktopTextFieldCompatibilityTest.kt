package com.nothingplayer.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalComposeUiApi::class)
class DesktopTextFieldCompatibilityTest {
    @Test fun materialTextFieldRendersAgainstThePackagedFoundationRuntime() {
        val scene = ImageComposeScene(width = 480, height = 160, density = Density(1f)) {
            MaterialTheme { OutlinedTextField(value = "Search music", onValueChange = {}) }
        }
        try {
            scene.render().use { assertEquals(480, it.width) }
        } finally { scene.close() }
    }
}

package com.nothingplayer.app.expect

import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.maxrave.domain.data.model.update.UpdateData

@Composable
actual fun UpdateInstallButton(update: UpdateData) {
    TextButton(onClick = { openUrl(update.releaseUrl) }) { Text("View release") }
}

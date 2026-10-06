package com.nothingplayer.app.expect

import android.content.Intent
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.maxrave.media3.service.PlaybackDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun PlaybackDiagnosticsButton() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    TextButton(onClick = {
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { PlaybackDiagnostics.export(context.applicationContext) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Share playback diagnostics"))
            } catch (_: Exception) {
                Toast.makeText(context, "Could not export playback diagnostics", Toast.LENGTH_LONG).show()
            }
        }
    }) { Text("Share playback diagnostics") }
}

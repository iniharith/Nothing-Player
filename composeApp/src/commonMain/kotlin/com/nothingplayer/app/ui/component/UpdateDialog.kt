package com.nothingplayer.app.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maxrave.domain.data.model.update.UpdateData
import com.nothingplayer.app.expect.openUrl
import com.nothingplayer.app.ui.theme.typo
import org.jetbrains.compose.resources.stringResource
import nothingplayer.composeapp.generated.resources.Res
import nothingplayer.composeapp.generated.resources.later
import nothingplayer.composeapp.generated.resources.update_available
import nothingplayer.composeapp.generated.resources.update_message
import nothingplayer.composeapp.generated.resources.view_release

@Composable
fun UpdateDialog(update: UpdateData, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.update_available), style = typo().labelMedium) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(
                        Res.string.update_message,
                        update.tagName.removePrefix("v").removePrefix("V"),
                        update.releaseTime?.substringBefore('T').orEmpty(),
                    ),
                    style = typo().bodyMedium,
                )
                if (update.body.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(update.body.take(8_000), style = typo().bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                openUrl(update.releaseUrl)
                onDismiss()
            }) {
                Text(stringResource(Res.string.view_release), style = typo().bodySmall)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.later), style = typo().bodySmall)
            }
        },
    )
}

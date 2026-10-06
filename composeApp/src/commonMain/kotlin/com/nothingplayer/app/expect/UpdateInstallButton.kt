package com.nothingplayer.app.expect

import androidx.compose.runtime.Composable
import com.maxrave.domain.data.model.update.UpdateData

@Composable
expect fun UpdateInstallButton(update: UpdateData)

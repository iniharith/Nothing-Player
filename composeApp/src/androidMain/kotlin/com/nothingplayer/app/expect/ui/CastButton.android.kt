package com.nothingplayer.app.expect.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.nothingplayer.cast.CastIconButton

@Composable
actual fun PlatformCastButton(
    modifier: Modifier,
    tint: Color,
) {
    CastIconButton(modifier = modifier, tint = tint)
}

package com.nothingplayer.app.glyph

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun GlyphSongPreview(
    playing: Boolean,
    hardwareReady: Boolean,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "glyphSongPreview")
    val phase by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (Math.PI * 2).toFloat(),
            animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
            label = "glyphSongPhase",
        )

    fun pulse(offset: Float): Float {
        if (!playing) return 0.25f
        return 0.25f + 0.75f * abs(sin(phase + offset))
    }

    Canvas(
        modifier =
            modifier
                .background(Color.Black, CircleShape)
                .border(1.dp, Color(0xFF35363A), CircleShape)
                .padding(9.dp),
    ) {
        val stroke = 3.dp.toPx()
        val arcStyle = Stroke(width = stroke, cap = StrokeCap.Round)
        drawArc(
            color = Color.White.copy(alpha = pulse(0f)),
            startAngle = 205f,
            sweepAngle = 130f,
            useCenter = false,
            style = arcStyle,
        )
        drawLine(
            color = Color.White.copy(alpha = pulse(2.1f)),
            start = androidx.compose.ui.geometry.Offset(size.width * 0.50f, size.height * 0.18f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.50f, size.height * 0.78f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawArc(
            color = Color.White.copy(alpha = pulse(4.2f)),
            startAngle = 20f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.56f, size.height * 0.10f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.36f, size.height * 0.36f),
            style = arcStyle,
        )
        drawCircle(
            color = if (hardwareReady) Color(0xFF78E3C8) else Color(0xFF5A5A5D),
            radius = 2.5.dp.toPx(),
            center = androidx.compose.ui.geometry.Offset(size.width * 0.50f, size.height * 0.82f),
        )
    }
}

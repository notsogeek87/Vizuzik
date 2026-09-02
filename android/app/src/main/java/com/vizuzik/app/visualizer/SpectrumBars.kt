package com.vizuzik.app.visualizer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Barres de spectre simples, animées à partir de [magnitudes] (voir
 * [VisualizerEngine]). Chemin de secours quand les magnitudes sont vides
 * (permission refusée / rien ne joue) : les barres retombent à zéro.
 */
@Composable
fun SpectrumBars(
    magnitudes: FloatArray,
    modifier: Modifier = Modifier,
    barColor: Color = Color(0xFF00E5A0),
) {
    val animated = remember { Array(magnitudes.size) { Animatable(0f) } }
    LaunchedEffect(magnitudes.contentHashCode()) {
        magnitudes.forEachIndexed { index, value ->
            if (index < animated.size) {
                animated[index].animateTo(value, animationSpec = tween(120))
            }
        }
    }
    Canvas(modifier = modifier.fillMaxSize()) {
        drawBars(animated.map { it.value }, barColor)
    }
}

private fun DrawScope.drawBars(values: List<Float>, color: Color) {
    if (values.isEmpty()) return
    val barCount = values.size
    val gap = size.width * 0.015f
    val barWidth = (size.width - gap * (barCount - 1)) / barCount
    values.forEachIndexed { index, value ->
        val barHeight = size.height * value.coerceIn(0f, 1f)
        val x = index * (barWidth + gap)
        drawRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(x, size.height - barHeight),
            size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
        )
    }
}

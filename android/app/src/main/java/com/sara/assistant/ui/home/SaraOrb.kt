package com.sara.assistant.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.sara.assistant.live.LiveState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The state orb. One custom-drawn widget that answers "is Sara listening, thinking, talking
 * or driving my phone?" without a single label being read.
 *
 * Animation is cheap on purpose: one infinite rotation + two smoothed level inputs. Nothing
 * allocates per frame beyond the draw scope's own lists.
 */
@Composable
fun SaraOrb(
    state: LiveState,
    inputLevel: Float,
    outputLevel: Float,
    modifier: Modifier = Modifier,
) {
    val palette = paletteFor(state)

    val rotation by rememberInfiniteTransition(label = "orb").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "rotation",
    )

    val smoothedInput by animateFloatAsState(inputLevel.coerceIn(0f, 1f), label = "input")
    val smoothedOutput by animateFloatAsState(outputLevel.coerceIn(0f, 1f), label = "output")

    // Which level drives the core: the mic when listening, the model when speaking.
    val activity = when (state) {
        LiveState.LISTENING, LiveState.CONNECTING -> smoothedInput
        LiveState.SPEAKING -> smoothedOutput
        LiveState.THINKING, LiveState.ACTING -> 0.35f + smoothedInput * 0.3f
        LiveState.IDLE, LiveState.ERROR -> 0.12f
    }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = size.minDimension / 2f
        val coreRadius = maxRadius * (0.42f + activity * 0.16f)

        // 1. Outer halo.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(palette.accent.copy(alpha = 0.28f), Color.Transparent),
                center = center,
                radius = maxRadius,
            ),
            radius = maxRadius,
            center = center,
        )

        // 2. Rotating arcs — the "working" tell. Two arcs at different speeds.
        val arcRadius = maxRadius * 0.78f
        drawArc(
            color = palette.accent.copy(alpha = 0.85f),
            startAngle = rotation,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
            size = androidx.compose.ui.geometry.Size(arcRadius * 2, arcRadius * 2),
            style = Stroke(width = maxRadius * 0.045f),
        )
        drawArc(
            color = palette.secondary.copy(alpha = 0.7f),
            startAngle = -rotation * 1.6f,
            sweepAngle = 55f,
            useCenter = false,
            topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
            size = androidx.compose.ui.geometry.Size(arcRadius * 2, arcRadius * 2),
            style = Stroke(width = maxRadius * 0.03f),
        )

        // 3. Waveform ring: bar heights mix the live level with a travelling sine so it
        //    never looks frozen even in silence.
        val barCount = 48
        val baseRadius = maxRadius * 0.62f
        val phase = rotation / 180f * PI.toFloat()
        for (index in 0 until barCount) {
            val angle = (index.toFloat() / barCount) * 2f * PI.toFloat()
            val wobble = (sin(phase * 2f + index * 0.35f) + 1f) / 2f
            val height = maxRadius * (0.05f + activity * 0.22f * wobble)
            val from = Offset(
                center.x + cos(angle) * baseRadius,
                center.y + sin(angle) * baseRadius,
            )
            val to = Offset(
                center.x + cos(angle) * (baseRadius + height),
                center.y + sin(angle) * (baseRadius + height),
            )
            drawLine(
                color = palette.accent.copy(alpha = 0.55f),
                start = from,
                end = to,
                strokeWidth = 2.4f,
            )
        }

        // 4. Core.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.95f), palette.core, palette.accent),
                center = center,
                radius = coreRadius,
            ),
            radius = coreRadius,
            center = center,
        )
    }
}

private data class OrbPalette(val accent: Color, val secondary: Color, val core: Color)

private fun paletteFor(state: LiveState): OrbPalette = when (state) {
    LiveState.IDLE -> OrbPalette(Color(0xFF4A4F63), Color(0xFF3A3F52), Color(0xFF6E7488))
    LiveState.CONNECTING -> OrbPalette(Color(0xFF7C5CFF), Color(0xFF22D3EE), Color(0xFFB6A6FF))
    LiveState.LISTENING -> OrbPalette(Color(0xFF22D3EE), Color(0xFF7C5CFF), Color(0xFFA9F0FB))
    LiveState.THINKING -> OrbPalette(Color(0xFF7C5CFF), Color(0xFF9F7BFF), Color(0xFFD9CEFF))
    LiveState.SPEAKING -> OrbPalette(Color(0xFFFF6BD6), Color(0xFF7C5CFF), Color(0xFFFFC7EF))
    LiveState.ACTING -> OrbPalette(Color(0xFF2DD4BF), Color(0xFF22D3EE), Color(0xFFB8F5EC))
    LiveState.ERROR -> OrbPalette(Color(0xFFFF6B6B), Color(0xFFB23A48), Color(0xFFFFC2C2))
}

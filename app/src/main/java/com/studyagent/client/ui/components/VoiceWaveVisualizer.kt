package com.studyagent.client.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.studyagent.client.ui.theme.AccentTeal
import com.studyagent.client.ui.theme.PrimaryBlue
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.useReducedMotion

@Composable
fun VoiceWaveVisualizer(
    isActive: Boolean,
    color: Color = PrimaryBlue,
    modifier: Modifier = Modifier
    color: Color = AppColors.voiceListening,
    modifier: Modifier = Modifier,
    description: String = "Voice activity"
) {
    val heights = waveHeights(isActive)
    val reducedMotion = useReducedMotion()
    val heights = waveHeights(isActive && !reducedMotion)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp),
            .height(36.dp)
            .semantics { contentDescription = if (isActive) description else "Voice idle" },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (heightFraction in heights) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .width(4.dp)
                    .height((36 * heightFraction).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (isActive) color else Color.Gray.copy(alpha = 0.3f))
                    .background(if (isActive) color else AppColors.voiceIdle)
            )
        }
    }
}

/**
 * Bar height fractions for the waveform.
 *
 * The animation is created **only while the voice loop is active** (§58/§144). A visualizer that
 * kept animating on an idle Study screen would request a frame every 16 ms for as long as the
 * screen is open — a battery cost that shows nothing — and would leave the window permanently
 * non-idle for the instrumented tests (§177).
 */
@Composable
private fun waveHeights(isActive: Boolean): List<Float> {
    if (!isActive) return IDLE_WAVE_HEIGHTS

    val transition = rememberInfiniteTransition(label = "wave")

    val h1 by transition.animateFloat(
        initialValue = 0.2f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "h1"
    )
    val h2 by transition.animateFloat(
        initialValue = 0.8f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(550, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "h2"
    )
    val h3 by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(480, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "h3"
    )
    val h4 by transition.animateFloat(
        initialValue = 0.7f, targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(620, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "h4"
    )
    val h5 by transition.animateFloat(
        initialValue = 0.4f, targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(430, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "h5"
    )

    return listOf(h1, h2, h3, h4, h5, h2, h1)
}

/** Flat bars: the visual state of an inactive voice loop. */
private val IDLE_WAVE_HEIGHTS = List(7) { 0.15f }

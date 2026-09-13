package com.studyagent.client.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studyagent.client.ui.theme.DarkSurfaceElevated
import com.studyagent.client.ui.theme.PrimaryBlue
import com.studyagent.client.ui.theme.PrimaryBlueVariant
import com.studyagent.client.ui.theme.StatusRed
import com.studyagent.client.ui.theme.TextPrimary

/**
 * Semantics tag for the push-to-talk control.
 *
 * Exposed so the instrumented tests select the button by identity instead of by its visible copy
 * (§141). Nothing in the app reads it.
 */
const val PUSH_TO_TALK_TEST_TAG = "push_to_talk"

@Composable
fun PushToTalkButton(
    isListening: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onTapToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The pulse exists to show an active microphone, so it is only created while listening (§58).
    // An idle push-to-talk button that keeps animating would request a frame every 16 ms for as
    // long as the Study screen is open: a battery cost with no information on screen, and a
    // permanently non-idle window for the instrumented tests (§144/§177).
    val scale = if (isListening) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(700, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )
        pulseScale
    } else {
        1.0f
    }
    val bgColor = if (isListening) StatusRed else PrimaryBlue

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            // Stable hook for the instrumented tests (§141) — matching on the visible label would
            // break the moment the copy changes.
            .testTag(PUSH_TO_TALK_TEST_TAG)
            .scale(scale)
            .clip(RoundedCornerShape(24.dp))
            .pointerInput(isListening) {
                detectTapGestures(
                    onPress = {
                        onPressStart()
                        tryAwaitRelease()
                        onPressEnd()
                    },
                    onTap = {
                        onTapToggle()
                    }
                )
            },
        color = bgColor,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Default.Mic else Icons.Default.MicNone,
                    contentDescription = "Push to talk",
                    tint = TextPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = if (isListening) "Listening... (Release to submit)" else "Push to Talk (Hold or Tap)",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                color = TextPrimary
            )
        }
    }
}

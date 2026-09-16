package com.studyagent.client.ui.components

import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing
import com.studyagent.client.ui.theme.useReducedMotion

/**
 * Semantics tag for the push-to-talk control.
 *
 * Exposed so the instrumented tests select the button by identity instead of by its
 * visible copy. Nothing in the app reads it.
 */
const val PUSH_TO_TALK_TEST_TAG = "push_to_talk"

/**
 * Primary voice control on the Study screen (§27).
 *
 * Four visual states, each distinguished by colour **and** icon **and** text:
 *  - ready       (blue, mic, "Push to Talk / Hold or tap")
 *  - listening   (red, mic, "Listening… / Release to submit") + gentle pulse
 *  - processing  (tinted, spinner, "Processing…")
 *  - disabled    (muted, mic-off outline, "Unavailable right now")
 *
 * The pulse only runs while listening and is skipped entirely when the system
 * reduce-motion setting is on (§84). Press/release/tap give a short haptic tick.
 */
@Composable
fun PushToTalkButton(
    isListening: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onTapToggle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isProcessing: Boolean = false
) {
    val reducedMotion = useReducedMotion()
    val view = LocalView.current
    val haptics = LocalHapticFeedback.current

    // Only create the infinite transition while it carries information (active mic).
    val scale = if (isListening && !reducedMotion) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.03f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )
        pulseScale
    } else {
        1.0f
    }

    val (container, content) = when {
        !enabled -> AppColors.surfaceInteractive.copy(alpha = 0.6f) to AppColors.contentMuted
        isProcessing -> AppColors.statusInfoFill to AppColors.statusInfo
        isListening -> AppColors.statusDangerStrong to Color.White
        else -> AppColors.actionPrimaryStrong to Color.White
    }

    fun tick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        } else {
            @Suppress("DEPRECATION")
            view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    val title = when {
        isProcessing -> "Processing…"
        isListening -> "Listening…"
        else -> "Push to Talk"
    }
    val hint = when {
        !enabled -> "Unavailable right now"
        isProcessing -> "Finishing your answer"
        isListening -> "Release to submit"
        else -> "Hold or tap"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .testTag(PUSH_TO_TALK_TEST_TAG)
            .scale(scale)
            .clip(AppShape.heroCardShape)
            .pointerInput(isListening, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        tick()
                        onPressStart()
                        tryAwaitRelease()
                        onPressEnd()
                    },
                    onTap = {
                        tick()
                        onTapToggle()
                    }
                )
            }
            .semantics {
                contentDescription = when {
                    !enabled -> "Push to talk unavailable"
                    isProcessing -> "Processing answer"
                    isListening -> "Listening. Release to submit"
                    else -> "Push to talk. Hold or tap to start"
                }
            },
        color = container,
        shadowElevation = if (enabled && !isProcessing) 4.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.XL, vertical = AppSpacing.SM),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.5.dp,
                    color = content
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(content.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (enabled) Icons.Default.Mic else Icons.Default.MicNone,
                        contentDescription = null,
                        tint = content,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(AppSpacing.SM))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = content
                )
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.85f)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Previews (fake static data only — no repositories)
// ---------------------------------------------------------------------------

@Preview(name = "PTT — ready", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun PttReadyPreview() {
    PushToTalkButton(isListening = false, onPressStart = {}, onPressEnd = {}, onTapToggle = {})
}

@Preview(name = "PTT — listening", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun PttListeningPreview() {
    PushToTalkButton(isListening = true, onPressStart = {}, onPressEnd = {}, onTapToggle = {})
}

@Preview(name = "PTT — processing", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun PttProcessingPreview() {
    PushToTalkButton(
        isListening = false,
        onPressStart = {},
        onPressEnd = {},
        onTapToggle = {},
        isProcessing = true
    )
}

@Preview(name = "PTT — disabled", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun PttDisabledPreview() {
    PushToTalkButton(
        isListening = false,
        onPressStart = {},
        onPressEnd = {},
        onTapToggle = {},
        enabled = false
    )
}

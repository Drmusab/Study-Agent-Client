package com.studyagent.client.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Motion system: short, purposeful transitions (150–300 ms) that explain state
 * changes. No decorative continuous movement; infinite animation exists only
 * while a voice loop is actually active (see VoiceWaveVisualizer / PushToTalk).
 */
object AppMotion {

    /** Quick state flips (phase chips, banners in/out). */
    val quick: AnimationSpec<Float> = tween(150, easing = FastOutSlowInEasing)

    /** Card/banner entrance and content changes. */
    val standard: AnimationSpec<Float> = tween(220, easing = FastOutSlowInEasing)

    /** Larger transitions (hero card content swap). */
    val slow: AnimationSpec<Float> = tween(300, easing = FastOutSlowInEasing)

    /** Card advance: fade + small vertical shift (§71). */
    const val CARD_ADVANCE_OFFSET_DP = 12
}

/**
 * True when the user has requested reduced motion in system accessibility settings.
 *
 * [android.view.accessibility.AccessibilityManager.isReduceMotionEnabled] requires API 30;
 * below that there is no platform signal, so we conservatively return false (the app's
 * continuous animation is already minimal and gated on real voice activity).
 */
@Composable
@Stable
fun useReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { computeReduceMotion(context) }
}

internal fun computeReduceMotion(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    return try {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? android.view.accessibility.AccessibilityManager
        am?.isReduceMotionEnabled ?: false
    } catch (_: Throwable) {
        false
    }
}

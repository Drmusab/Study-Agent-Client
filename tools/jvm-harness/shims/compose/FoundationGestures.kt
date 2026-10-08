// Harness stub — androidx.compose.foundation.gestures (+ .detect). See Runtime.kt for the policy.
package androidx.compose.foundation.gestures

class DetectGesturesScope

suspend fun detectTapGestures(
    onPress: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    onDoubleTap: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    onLongPress: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    onTap: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null
): Unit = Unit

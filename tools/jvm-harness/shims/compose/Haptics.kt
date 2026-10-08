// Harness stub — androidx.compose.ui.hapticfeedback. See Runtime.kt for the policy.
package androidx.compose.ui.hapticfeedback

@JvmInline
value class HapticFeedbackType(val value: Int) {
    companion object {
        val LongPress = HapticFeedbackType(0)
        val VirtualKeyboard = HapticFeedbackType(1)
        val TextHandleChange = HapticFeedbackType(2)
        val Tap = HapticFeedbackType(3)
    }
}

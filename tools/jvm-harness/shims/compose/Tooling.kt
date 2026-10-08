// Harness stub — androidx.compose.ui.tooling.preview. See Runtime.kt for the policy.
package androidx.compose.ui.tooling.preview

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class Preview(
    val name: String = "",
    val apiLevel: Int = -1,
    val widthDp: Int = -1,
    val heightDp: Int = -1,
    val locale: String = "",
    val fontScale: Float = 1f,
    val showSystemUi: Boolean = false,
    val showBackground: Boolean = false,
    val backgroundColor: Long = 0
)

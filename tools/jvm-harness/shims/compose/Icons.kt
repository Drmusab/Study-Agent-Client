// Harness stub — androidx.compose.material.icons. See Runtime.kt for the policy.
//
// Only the handful of icons the audited screens import are declared, and each icon import is an
// extension property on `Icons.Default` / `Icons.AutoMirrored.Filled` in its own package, exactly as
// material-icons-extended generates them. A typo'd icon name therefore fails here as it does in the
// real build.
package androidx.compose.material.icons

import androidx.compose.ui.graphics.vector.ImageVector

object Icons {
    object Default
    object Filled
    object Outlined
    object Rounded
    object Sharp
    object AutoMirrored {
        object Filled
        object Outlined
        object Rounded
        object Sharp
    }
}

class ImageVectorStub(private val iconName: String?) : ImageVector {
    override val defaultWidth: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(24f)
    override val defaultHeight: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(24f)
    override val name: String? get() = iconName
}

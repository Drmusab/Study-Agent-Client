// Harness stub — androidx.compose.foundation.shape. See Runtime.kt for the policy.
package androidx.compose.foundation.shape

import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp

// `Dp` is an inline class, so a `CornerSize(Dp)` constructor would erase to the same JVM
// signature as `CornerSize(Float)`; the real library avoids this with distinct declarations.
class CornerSize(val value: Float) {
    constructor(percent: Int) : this(percent.toFloat())
}

class RoundedCornerShape(
    val topStart: CornerSize,
    val topEnd: CornerSize,
    val bottomEnd: CornerSize,
    val bottomStart: CornerSize
) : Shape {
    constructor(size: Dp) : this(CornerSize(size.value), CornerSize(size.value),
        CornerSize(size.value), CornerSize(size.value))
    constructor(percent: Int) : this(
        CornerSize(percent), CornerSize(percent), CornerSize(percent), CornerSize(percent)
    )
    constructor(topStart: Dp, topEnd: Dp, bottomEnd: Dp, bottomStart: Dp) : this(
        CornerSize(topStart.value), CornerSize(topEnd.value),
        CornerSize(bottomEnd.value), CornerSize(bottomStart.value)
    )
    constructor(topStartPercent: Int, topEndPercent: Int, bottomEndPercent: Int,
                bottomStartPercent: Int) : this(
        CornerSize(topStartPercent), CornerSize(topEndPercent),
        CornerSize(bottomEndPercent), CornerSize(bottomStartPercent)
    )
}

class CutCornerShape(val topStart: CornerSize, val topEnd: CornerSize, val bottomEnd: CornerSize,
                     val bottomStart: CornerSize) : Shape {
    constructor(size: Dp) : this(CornerSize(size.value), CornerSize(size.value),
        CornerSize(size.value), CornerSize(size.value))
    constructor(percent: Int) : this(
        CornerSize(percent), CornerSize(percent), CornerSize(percent), CornerSize(percent)
    )
}

val CircleShape: RoundedCornerShape = RoundedCornerShape(50)
val RectangleShape: Shape = RoundedCornerShape(0)

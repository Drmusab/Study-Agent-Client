// Harness stub — androidx.compose.ui.geometry. See Runtime.kt for the policy.
package androidx.compose.ui.geometry

class Offset(val x: Float = 0f, val y: Float = 0f) {
    constructor(dx: Double, dy: Double) : this(dx.toFloat(), dy.toFloat())
    override fun toString(): String = "Offset($x, $y)"
    operator fun plus(other: Offset): Offset = Offset(x + other.x, y + other.y)
    operator fun minus(other: Offset): Offset = Offset(x - other.x, y - other.y)

    companion object {
        val Zero = Offset(0f, 0f)
        val Unspecified = Offset(Float.NaN, Float.NaN)
        val Infinite = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    }
}

class Size(val width: Float = 0f, val height: Float = 0f) {
    constructor(size: Float) : this(size, size)
    constructor(width: Double, height: Double) : this(width.toFloat(), height.toFloat())
    val aspectRatio: Float get() = width / height
    override fun toString(): String = "Size($width, $height)"

    companion object {
        val Zero = Size(0f, 0f)
        val Unspecified = Size(Float.NaN, Float.NaN)
        val Infinite = Size(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    }
}

class Rect(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f)

class CornerRadius(val x: Float = 0f, val y: Float = x) {
    companion object {
        val Zero = CornerRadius(0f)
    }
}

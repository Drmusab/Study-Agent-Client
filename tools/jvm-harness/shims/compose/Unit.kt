// Harness stub — androidx.compose.ui.unit (compose 1.6.x). See Runtime.kt for the policy.
package androidx.compose.ui.unit

@JvmInline
value class Dp(val value: Float) : Comparable<Dp> {
    operator fun plus(other: Dp): Dp = Dp(value + other.value)
    operator fun minus(other: Dp): Dp = Dp(value - other.value)
    operator fun times(other: Float): Dp = Dp(value * other)
    operator fun times(other: Int): Dp = Dp(value * other)
    operator fun div(other: Float): Dp = Dp(value / other)
    operator fun unaryMinus(): Dp = Dp(-value)
    override fun compareTo(other: Dp): Int = value.compareTo(other.value)
    fun coerceAtLeast(minimumValue: Dp): Dp = if (value < minimumValue.value) minimumValue else this
    fun coerceAtMost(maximumValue: Dp): Dp = if (value > maximumValue.value) maximumValue else this
    fun coerceIn(minimumValue: Dp, maximumValue: Dp): Dp =
        if (value < minimumValue.value) minimumValue else if (value > maximumValue.value) maximumValue else this
    override fun toString(): String = "$value.dp"

    companion object {
        val Hairline = Dp(0.0f)
        val Zero = Dp(0.0f)
        val Unspecified = Dp(Float.NaN)
    }
}

val Int.dp: Dp get() = Dp(toFloat())
val Float.dp: Dp get() = Dp(this)
val Double.dp: Dp get() = Dp(toFloat())

enum class TextUnitType { Sp, Px }

class TextUnit(val value: Float, val type: TextUnitType) : Comparable<TextUnit> {
    override fun compareTo(other: TextUnit): Int = value.compareTo(other.value)
    override fun toString(): String = "$value.$type"

    companion object {
        val Unspecified = TextUnit(Float.NaN, TextUnitType.Sp)
    }
}

val Int.sp: TextUnit get() = TextUnit(toFloat(), TextUnitType.Sp)
val Float.sp: TextUnit get() = TextUnit(this, TextUnitType.Sp)
val Double.sp: TextUnit get() = TextUnit(toFloat(), TextUnitType.Sp)

enum class LayoutDirection { Ltr, Rtl }

class TextRange(val start: Int, val end: Int = start) {
    val collapsed: Boolean get() = start == end
}

interface Density {
    val density: Float
    val fontScale: Float
}

inline fun <R> Density.with(block: Density.() -> R): R = block(this)

fun Density.dp(value: Int): Dp = Dp(value.toFloat())
fun Density.dp(value: Float): Dp = Dp(value)
fun Density.sp(value: Int): TextUnit = TextUnit(value.toFloat(), TextUnitType.Sp)

class DensityImpl(override val density: Float = 1f, override val fontScale: Float = 1f) : Density

class IntSize(val width: Int = 0, val height: Int = 0) {
    companion object { val Zero: IntSize = IntSize(0, 0) }
}

class IntOffset(val x: Int = 0, val y: Int = 0) {
    companion object { val Zero: IntOffset = IntOffset(0, 0) }
    fun round(origin: (Float) -> Int = { it.toInt() }): IntOffset = this
}

// Harness stub — androidx.compose.ui.graphics (+ .vector, .shape aliases). See Runtime.kt.
package androidx.compose.ui.graphics

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

@JvmInline
value class Color(val value: ULong) {
    val red: Float get() = 0f
    val green: Float get() = 0f
    val blue: Float get() = 0f
    val alpha: Float get() = 1f
    val luminance: Float get() = 0f
    val isUnspecified: Boolean get() = value == UNSPECIFIED_SENTINEL
    fun copy(alpha: Float = this.alpha, red: Float = this.red, green: Float = this.green,
             blue: Float = this.blue): Color = this
    fun compositeOver(background: Color): Color = this
    fun lerp(stop: Color, fraction: Float): Color = this
    fun toArgb(): Int = 0
    fun toHex(): String = ""

    companion object {
        internal const val UNSPECIFIED_SENTINEL: ULong = 0uL
        val Black = Color(1uL)
        val DarkGray = Color(2uL)
        val Gray = Color(3uL)
        val LightGray = Color(4uL)
        val White = Color(5uL)
        val Red = Color(6uL)
        val Green = Color(7uL)
        val Blue = Color(8uL)
        val Yellow = Color(9uL)
        val Cyan = Color(10uL)
        val Magenta = Color(11uL)
        val Transparent = Color(12uL)
        val Unspecified = Color(UNSPECIFIED_SENTINEL)
        val Error = Color(13uL)

        operator fun invoke(rgb: Long): Color = Color(rgb.toULong())
        operator fun invoke(red: Int, green: Int, blue: Int, alpha: Int = 255): Color = Color(0uL)
        operator fun invoke(red: Float, green: Float, blue: Float, alpha: Float = 1f): Color =
            Color(0uL)
        fun lerp(start: Color, stop: Color, fraction: Float): Color = start
    }
}

typealias ColorProducer = () -> Color

fun Color.toArgb(): Int = 0
fun Color.toHex(): String = ""

interface Shape

interface Brush

class SolidColor(val value: Color) : Brush

class LinearGradientBrush(val colors: List<Color>, val startY: Float = 0f, val endY: Float = 0f) : Brush

class SweepGradientBrush(val colors: List<Color>) : Brush

interface Painter

class BorderStroke(val width: Dp, val brush: Brush)

class GenericShape : Shape

interface ColorFilter

fun Modifier.border(width: Dp, color: Color, shape: Shape = GenericShape()): Modifier = this
fun Modifier.border(border: BorderStroke, shape: Shape = GenericShape()): Modifier = this
fun Modifier.paint(painter: Painter, alpha: Float = 1.0f, colorFilter: ColorFilter? = null): Modifier = this
fun Modifier.shadow(elevation: Dp, shape: Shape = GenericShape(), clip: Boolean = true,
                    ambientColor: Color = Color.Black, spotColor: Color = Color.Black): Modifier = this
fun Modifier.graphicsLayer(block: Any.() -> Unit): Modifier = this

interface ImageBitmap

object AlphaEffect

// ---- DrawScope: what a `Canvas { }` / `drawBehind { }` body can reach -------------------------
interface DrawScope {
    val size: androidx.compose.ui.geometry.Size
    val density: androidx.compose.ui.unit.Density
    val layoutDirection: androidx.compose.ui.unit.LayoutDirection
    fun toPx(size: androidx.compose.ui.unit.Dp): Float = size.value
    fun toPx(value: Int): Float = value.toFloat()
    fun translate(left: Float = 0f, top: Float = 0f, block: (() -> Unit)? = null) { block?.invoke() }
    fun rotate(degrees: Float, pivot: androidx.compose.ui.geometry.Offset =
                   androidx.compose.ui.geometry.Offset.Zero, block: (() -> Unit)? = null) { block?.invoke() }
    fun scale(scale: Float, pivot: androidx.compose.ui.geometry.Offset =
                  androidx.compose.ui.geometry.Offset.Zero, block: (() -> Unit)? = null) { block?.invoke() }
    fun clipRect(left: Float = 0f, top: Float = 0f, right: Float = 0f, bottom: Float = 0f,
                 clipOp: Any? = null, block: (() -> Unit)? = null) { block?.invoke() }
    fun drawLine(color: androidx.compose.ui.graphics.Color, start: androidx.compose.ui.geometry.Offset,
                 end: androidx.compose.ui.geometry.Offset, strokeWidth: Float = 1f,
                 cap: Any? = null, pathEffect: Any? = null, alpha: Float = 1f,
                 blendMode: Any? = null) = Unit
    fun drawRect(color: androidx.compose.ui.graphics.Color, topLeft: androidx.compose.ui.geometry.Offset =
                 androidx.compose.ui.geometry.Offset.Zero, size: androidx.compose.ui.geometry.Size,
                 alpha: Float = 1f, style: Any? = null) = Unit
    fun drawRoundRect(color: androidx.compose.ui.graphics.Color, topLeft: androidx.compose.ui.geometry.Offset =
                      androidx.compose.ui.geometry.Offset.Zero, size: androidx.compose.ui.geometry.Size,
                      cornerRadius: androidx.compose.ui.geometry.CornerRadius =
                          androidx.compose.ui.geometry.CornerRadius.Zero, alpha: Float = 1f,
                      style: Any? = null) = Unit
    fun drawCircle(color: androidx.compose.ui.graphics.Color, radius: Float, center:
                   androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset.Zero,
                   alpha: Float = 1f, style: Any? = null) = Unit
    fun drawArc(color: androidx.compose.ui.graphics.Color, startAngle: Float, sweepAngle: Float,
                useCenter: Boolean, topLeft: androidx.compose.ui.geometry.Offset =
                androidx.compose.ui.geometry.Offset.Zero, size: androidx.compose.ui.geometry.Size,
                alpha: Float = 1f, style: Any? = null) = Unit
    fun drawPath(path: Any?, brush: Any? = null, color: androidx.compose.ui.graphics.Color,
                 alpha: Float = 1f, style: Any? = null, colorFilter: Any? = null) = Unit
}

// `Modifier.drawBehind`/`drawWithContent` live in `androidx.compose.ui.draw` (see Draw.kt); only the
// receiver types belong here.

/** `DrawScope.drawWithContent { drawContent() }` receives this; the real type is a fun interface. */
fun interface DrawBlock {
    fun invoke()
}

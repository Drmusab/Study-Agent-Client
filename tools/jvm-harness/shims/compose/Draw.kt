// Harness stub — androidx.compose.ui.draw. See Runtime.kt for the policy.
package androidx.compose.ui.draw

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp

fun Modifier.clip(shape: Shape): Modifier = this
fun Modifier.clipRect(): Modifier = this
fun Modifier.clipRect(ltrb: Rect): Modifier = this
fun Modifier.alpha(alpha: Float): Modifier = this
fun Modifier.scale(scaleX: Float, scaleY: Float): Modifier = this
fun Modifier.scale(scale: Float): Modifier = this
fun Modifier.rotate(degrees: Float): Modifier = this
fun Modifier.offset(x: Dp = Dp(0f), y: Dp = Dp(0f)): Modifier = this

fun Modifier.drawBehind(onDraw: androidx.compose.ui.graphics.DrawScope.() -> Unit): Modifier = this
fun Modifier.drawWithCache(onDraw: androidx.compose.ui.graphics.DrawScope.() -> Unit): Modifier = this
fun Modifier.drawWithContent(
    drawContent: androidx.compose.ui.graphics.DrawScope.(androidx.compose.ui.graphics.DrawBlock) -> Unit
): Modifier = this



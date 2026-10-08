// Harness stub — androidx.compose.animation.core. See Runtime.kt for the policy.
package androidx.compose.animation.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp

interface Easing

object LinearEasing : Easing
object FastOutSlowInEasing : Easing
object LinearOutSlowInEasing : Easing
object FastOutLinearInEasing : Easing
object Ease : Easing
object EaseIn : Easing
object EaseOut : Easing
object EaseInOut : Easing

fun easing(transitionFraction: (Float) -> Float): Easing = LinearEasing

interface AnimationSpec<T>
interface FiniteAnimationSpec<T> : AnimationSpec<T>
interface VectorizedAnimationSpec<T>
interface Repeatable<T> : AnimationSpec<T>
interface KeyframesSpec<T> : AnimationSpec<T>

class TweenSpec<T>(
    val durationMillis: Int = 300,
    val delayMillis: Int = 0,
    val easing: Easing = FastOutSlowInEasing
) : FiniteAnimationSpec<T>

fun <T> tween(durationMillis: Int = 300, delayMillis: Int = 0,
              easing: Easing = LinearOutSlowInEasing): FiniteAnimationSpec<T> = TweenSpec()

fun <T> spring(dampingRatio: Float = 1f, stiffness: Float = 1500f): FiniteAnimationSpec<T> = TweenSpec()

enum class RepeatMode { Restart, Reverse }

class StartOffset(val value: Int = 0, val type: Any? = null)

class RepeatableSpec<T>(
    val iterations: Int = -1,
    val animation: FiniteAnimationSpec<T>? = null,
    val repeatMode: RepeatMode = RepeatMode.Restart,
    val initialStartOffset: StartOffset? = null
) : Repeatable<T>

fun <T> infiniteRepeatable(
    animation: FiniteAnimationSpec<T>,
    repeatMode: RepeatMode = RepeatMode.Restart,
    initialStartOffset: StartOffset? = null
): Repeatable<T> = RepeatableSpec(animation = animation, repeatMode = repeatMode,
                                 initialStartOffset = initialStartOffset)

fun <T> repeatable(iterations: Int, animation: FiniteAnimationSpec<T>,
                   repeatMode: RepeatMode = RepeatMode.Restart): Repeatable<T> =
    RepeatableSpec(iterations, animation, repeatMode)

interface InfiniteTransition {
    @Composable
    fun animateFloat(
        initialValue: Float = 0f,
        targetValue: Float = 0f,
        animationSpec: Repeatable<Float> = infiniteRepeatable(tween<Float>()),
        internalHolders: List<Any> = emptyList(),
        finishedListener: ((Float) -> Unit)? = null
    ): State<Float>

    @Composable
    fun animateDp(
        initialValue: Dp = Dp(0f),
        targetValue: Dp = Dp(0f),
        animationSpec: Repeatable<Dp> = infiniteRepeatable(tween<Dp>())
    ): State<Dp>

    @Composable
    fun animateFloatAsState(
        targetValue: Float,
        animationSpec: FiniteAnimationSpec<Float> = spring(),
        label: String = "FloatAnimation"
    ): State<Float>
}

@Composable
fun rememberInfiniteTransition(label: String = "InfiniteTransition"): InfiniteTransition =
    object : InfiniteTransition {
        @Composable
        override fun animateFloat(initialValue: Float, targetValue: Float,
                                  animationSpec: Repeatable<Float>, internalHolders: List<Any>,
                                  finishedListener: ((Float) -> Unit)?): State<Float> =
            mutableFloatStateOf(initialValue)

        @Composable
        override fun animateDp(initialValue: Dp, targetValue: Dp,
                               animationSpec: Repeatable<Dp>): State<Dp> =
            remember { mutableStateOf(initialValue) }

        @Composable
        override fun animateFloatAsState(targetValue: Float,
                                         animationSpec: FiniteAnimationSpec<Float>,
                                         label: String): State<Float> =
            remember { mutableStateOf(targetValue) }
    }

@Composable
fun animateFloatAsState(
    targetValue: Float,
    animationSpec: FiniteAnimationSpec<Float> = tween(),
    label: String = "FloatAnimation"
): State<Float> = remember { mutableStateOf(targetValue) }

@Composable
fun animateDpAsState(
    targetValue: Dp,
    animationSpec: FiniteAnimationSpec<Dp> = tween(),
    label: String = "DpAnimation"
): State<Dp> = remember { mutableStateOf(targetValue) }

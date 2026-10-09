// Harness stub — androidx.compose.animation (transitions). See Runtime.kt for the policy.
package androidx.compose.animation

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

@Stable
interface EnterTransition {
    fun then(other: EnterTransition): EnterTransition = this

    // `plus` is a *member* of the real transition types, which is why call sites can write
    // `fadeIn() + fadeOut()` without importing an operator.
    operator fun plus(other: ExitTransition): ContentTransform = object : ContentTransform {}

    // Real Compose also combines two enter transitions into one enter transition, which is what
    // `enter = fadeIn() + expandVertically()` at a call site needs.
    operator fun plus(other: EnterTransition): EnterTransition = this
}

@Stable
interface ExitTransition {
    fun then(other: ExitTransition): ExitTransition = this
    operator fun plus(other: ExitTransition): ExitTransition = this
}

@Stable
interface ContentTransform

fun fadeIn(
    animationSpec: FiniteAnimationSpec<Float> = tween(300),
    initialAlpha: Float = 0.0f
): EnterTransition = object : EnterTransition {}

fun fadeOut(animationSpec: FiniteAnimationSpec<Float> = tween(300)): ExitTransition =
    object : ExitTransition {}

fun expandIn(
    expandFrom: Alignment = Alignment.TopStart,
    animationSpec: FiniteAnimationSpec<IntSize> = tween(300),
    clip: Boolean = true
): EnterTransition = object : EnterTransition {}

fun shrinkOut(
    shrinkTowards: Alignment = Alignment.TopStart,
    animationSpec: FiniteAnimationSpec<IntSize> = tween(300),
    clip: Boolean = true
): ExitTransition = object : ExitTransition {}

fun expandVertically(
    expandFrom: Alignment.Vertical = Alignment.Top,
    animationSpec: FiniteAnimationSpec<IntSize> = tween(300),
    clip: Boolean = true,
    initialHeight: (Int) -> Int = { 0 }
): EnterTransition = object : EnterTransition {}

fun shrinkVertically(
    shrinkTowards: Alignment.Vertical = Alignment.Top,
    animationSpec: FiniteAnimationSpec<IntSize> = tween(300),
    clip: Boolean = true,
    targetHeight: (Int) -> Int = { 0 }
): ExitTransition = object : ExitTransition {}

fun expandHorizontally(
    expandFrom: Alignment.Horizontal = Alignment.Start,
    animationSpec: FiniteAnimationSpec<IntSize> = tween(300)
): EnterTransition = object : EnterTransition {}

fun shrinkHorizontally(
    shrinkTowards: Alignment.Horizontal = Alignment.Start,
    animationSpec: FiniteAnimationSpec<IntSize> = tween(300)
): ExitTransition = object : ExitTransition {}

fun slideInVertically(
    animationSpec: FiniteAnimationSpec<IntOffset> = tween(300),
    initialOffsetY: (fullHeight: Int) -> Int = { -it / 2 }
): EnterTransition = object : EnterTransition {}

fun slideOutVertically(
    animationSpec: FiniteAnimationSpec<IntOffset> = tween(300),
    targetOffsetY: (fullHeight: Int) -> Int = { -it / 2 }
): ExitTransition = object : ExitTransition {}

fun slideInHorizontally(
    animationSpec: FiniteAnimationSpec<IntOffset> = tween(300),
    initialOffsetX: (fullWidth: Int) -> Int = { -it / 2 }
): EnterTransition = object : EnterTransition {}

fun slideOutHorizontally(
    animationSpec: FiniteAnimationSpec<IntOffset> = tween(300),
    targetOffsetX: (fullWidth: Int) -> Int = { -it / 2 }
): ExitTransition = object : ExitTransition {}

fun scaleIn(
    initialScale: Float = 0.8f,
    animationSpec: FiniteAnimationSpec<Float> = tween(300)
): EnterTransition = object : EnterTransition {}

fun scaleOut(
    targetScale: Float = 0.8f,
    animationSpec: FiniteAnimationSpec<Float> = tween(300)
): ExitTransition = object : ExitTransition {}

class AnimatedVisibilityScope

@Composable
fun AnimatedVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition = fadeIn(),
    exit: ExitTransition = fadeOut(),
    label: String = "AnimatedVisibility",
    content: @Composable AnimatedVisibilityScope.() -> Unit
) = content(AnimatedVisibilityScope())

@Composable
fun <T> AnimatedContent(
    targetState: T,
    transitionSpec: ContentTransform = fadeIn() + fadeOut(),
    modifier: Modifier = Modifier,
    contentKey: (targetState: T) -> Any? = { it },
    label: String = "AnimatedContent",
    content: @Composable AnimatedContentScope<T>.(T) -> Unit
) = Unit

class AnimatedContentScope<out T>(val targetState: T)

@Stable
interface EnterExitTransition

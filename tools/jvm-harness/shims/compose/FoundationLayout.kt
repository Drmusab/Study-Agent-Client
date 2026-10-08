// Harness stub — androidx.compose.foundation.layout. See Runtime.kt for the policy.
package androidx.compose.foundation.layout

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

object Arrangement {
    interface HorizontalOrVertical : Horizontal, Vertical
    interface Horizontal
    interface Vertical

    private object Impl : HorizontalOrVertical

    val Top: Vertical get() = Impl
    val Bottom: Vertical get() = Impl
    val Start: Horizontal get() = Impl
    val End: Horizontal get() = Impl
    val Center: HorizontalOrVertical get() = Impl
    val SpaceEvenly: HorizontalOrVertical get() = Impl
    val SpaceBetween: HorizontalOrVertical get() = Impl
    val SpaceAround: HorizontalOrVertical get() = Impl

    fun spacedBy(space: Dp): HorizontalOrVertical = Impl
    fun spacedBy(space: Dp, alignment: Alignment.Horizontal): Horizontal = Impl
    fun spacedBy(space: Dp, alignment: Alignment.Vertical): Vertical = Impl
    fun start(alignment: Alignment.Horizontal = Alignment.Start): Horizontal = Impl
    fun align(alignment: Alignment.Horizontal): Horizontal = Impl
    fun align(alignment: Alignment.Vertical): Vertical = Impl
    fun top(alignment: Alignment.Vertical = Alignment.Top): Vertical = Impl

    object Absolute {
        fun spacedBy(space: Dp): Arrangement.HorizontalOrVertical = Impl
        fun top(top: Dp): Arrangement.Vertical = Impl
        fun bottom(bottom: Dp): Arrangement.Vertical = Impl
        fun start(start: Dp): Arrangement.Horizontal = Impl
        fun end(end: Dp): Arrangement.Horizontal = Impl
    }
}

class PaddingValues(
    val start: Dp = Dp(0f),
    val top: Dp = Dp(0f),
    val end: Dp = Dp(0f),
    val bottom: Dp = Dp(0f)
) {
    constructor(all: Dp) : this(all, all, all, all)
    constructor(horizontal: Dp = Dp(0f), vertical: Dp = Dp(0f)) : this(horizontal, vertical, horizontal, vertical)

    fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
        if (layoutDirection == LayoutDirection.Ltr) start else end

    fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
        if (layoutDirection == LayoutDirection.Ltr) end else start

    fun calculateTopPadding(): Dp = top
    fun calculateBottomPadding(): Dp = bottom
}

interface BoxScope {
    fun Modifier.align(alignment: Alignment): Modifier
    fun Modifier.matchParentSize(): Modifier
}

interface ColumnScope {
    fun Modifier.weight(weight: Float, fill: Boolean = true): Modifier
    fun Modifier.align(alignment: Alignment.Horizontal): Modifier
}

interface RowScope {
    fun Modifier.weight(weight: Float, fill: Boolean = true): Modifier
    fun Modifier.align(alignment: Alignment.Vertical): Modifier
    fun Modifier.alignBy(baselineProvider: Any?, offset: Dp = Dp(0f)): Modifier
}

interface FlowRowScope


@Composable
fun Box(modifier: Modifier = Modifier) = Unit

@Composable
fun Box(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    propagateMinConstraints: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) = content(BoxScopeImpl)

@Composable
fun BoxWithConstraints(
    modifier: Modifier = Modifier,
    propagateMinConstraints: Boolean = false,
    content: @Composable BoxWithConstraintsScope.() -> Unit
) = Unit

internal object BoxScopeImpl : BoxScope {
    override fun Modifier.align(alignment: Alignment): Modifier = this
    override fun Modifier.matchParentSize(): Modifier = this
}

interface BoxWithConstraintsScope : BoxScope {
    val constraints: Any
    val hasBoundedWidth: Boolean
    val hasBoundedHeight: Boolean
    val minWidth: Dp
    val maxWidth: Dp
    val minHeight: Dp
    val maxHeight: Dp
}

@Composable
fun Column(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit
) = Unit

@Composable
fun Row(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable RowScope.() -> Unit
) = Unit

@Composable
fun FlowRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable FlowRowScope.() -> Unit
) = Unit

@Composable
fun Spacer(modifier: Modifier = Modifier) = Unit

@Composable
fun Spacer(modifier: Modifier, minSize: Dp) = Unit

fun Modifier.padding(all: Dp): Modifier = this
fun Modifier.padding(horizontal: Dp = Dp(0f), vertical: Dp = Dp(0f)): Modifier = this
fun Modifier.padding(start: Dp = Dp(0f), top: Dp = Dp(0f), end: Dp = Dp(0f), bottom: Dp = Dp(0f)): Modifier = this
fun Modifier.padding(values: PaddingValues): Modifier = this
fun Modifier.fillMaxWidth(fraction: Float = 1f, minWidth: Dp = Dp.Unspecified, minHeight: Dp = Dp.Unspecified): Modifier = this
fun Modifier.fillMaxHeight(fraction: Float = 1f, minWidth: Dp = Dp.Unspecified, minHeight: Dp = Dp.Unspecified): Modifier = this
fun Modifier.fillMaxSize(fraction: Float = 1f): Modifier = this
fun Modifier.width(width: Dp): Modifier = this
fun Modifier.height(height: Dp): Modifier = this
fun Modifier.size(size: Dp): Modifier = this
fun Modifier.size(width: Dp, height: Dp): Modifier = this
fun Modifier.widthIn(min: Dp = Dp.Unspecified, max: Dp = Dp.Unspecified): Modifier = this
fun Modifier.heightIn(min: Dp = Dp.Unspecified, max: Dp = Dp.Unspecified): Modifier = this
fun Modifier.sizeIn(minWidth: Dp = Dp.Unspecified, minHeight: Dp = Dp.Unspecified,
                    maxWidth: Dp = Dp.Unspecified, maxHeight: Dp = Dp.Unspecified): Modifier = this
fun Modifier.defaultMinSize(minWidth: Dp = Dp.Unspecified, minHeight: Dp = Dp.Unspecified): Modifier = this
fun Modifier.requiredHeight(height: Dp): Modifier = this
fun Modifier.requiredWidth(width: Dp): Modifier = this
fun Modifier.requiredSize(size: Dp): Modifier = this
fun Modifier.wrapContentHeight(align: Alignment.Vertical = Alignment.Top, unfilled: Boolean = false): Modifier = this
fun Modifier.wrapContentSize(align: Alignment = Alignment.Center, unfilled: Boolean = false): Modifier = this
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY,
        AnnotationTarget.TYPEALIAS)
annotation class ExperimentalLayoutApi

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY,
        AnnotationTarget.TYPEALIAS)
annotation class ExperimentalFoundationApi

@Composable
fun FlowRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    maxItemsInEachRow: Int = Int.MAX_VALUE,
    content: @Composable RowScope.() -> Unit
) = Unit

@Composable
fun FlowColumn(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    maxItemsInEachColumn: Int = Int.MAX_VALUE,
    content: @Composable ColumnScope.() -> Unit
) = Unit

// Harness stub — androidx.compose.foundation.lazy. See Runtime.kt for the policy.
package androidx.compose.foundation.lazy

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues

interface LazyItemScope {
    fun Modifier.fillParentMaxWidth(fraction: Float = 1f): Modifier
    fun Modifier.fillParentMaxHeight(fraction: Float = 1f): Modifier
    fun Modifier.fillParentMaxSize(fraction: Float = 1f): Modifier
    fun Modifier.animateItem()
}

interface LazyListScope {
    val itemCount: Int
    fun item(key: Any? = null, contentType: Any? = null, content: @Composable LazyItemScope.() -> Unit)
    fun items(
        count: Int,
        key: ((index: Int) -> Any)? = null,
        contentType: (index: Int) -> Any? = { null },
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit
    )

}

inline fun <T> LazyListScope.items(
    items: List<T>,
    noinline key: ((item: T) -> Any)? = null,
    noinline contentType: (item: T) -> Any? = { null },
    crossinline itemContent: @Composable LazyItemScope.(item: T) -> Unit
) = Unit

inline fun <T> LazyListScope.items(
    items: Collection<T>,
    noinline key: ((item: T) -> Any)? = null,
    noinline contentType: (item: T) -> Any? = { null },
    crossinline itemContent: @Composable LazyItemScope.(item: T) -> Unit
) = Unit

inline fun <T> LazyListScope.itemsIndexed(
    items: List<T>,
    noinline key: ((index: Int, item: T) -> Any)? = null,
    noinline contentType: (index: Int, item: T) -> Any? = { _, _ -> null },
    crossinline itemContent: @Composable LazyItemScope.(index: Int, item: T) -> Unit
) = Unit

class LazyListState(val firstVisibleItemIndex: Int = 0, val firstVisibleItemScrollOffset: Int = 0) {
    val isScrollInProgress: Boolean get() = false
    suspend fun animateScrollToItem(index: Int, scrollOffset: Int = 0) = Unit
    suspend fun scrollToItem(index: Int, scrollOffset: Int = 0) = Unit
}

@Composable
fun rememberLazyListState(initialFirstVisibleItemIndex: Int = 0, initialScrollOffset: Int = 0): LazyListState =
    LazyListState()

@Composable
fun LazyColumn(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit
) = Unit

@Composable
fun LazyColumn(
    state: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit
) = Unit

@Composable
fun LazyRow(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    reverseLayout: Boolean = false,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit
) = Unit

@Composable
fun LazyVerticalGrid(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) = Unit

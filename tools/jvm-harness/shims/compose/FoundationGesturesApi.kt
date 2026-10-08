// Harness stub — androidx.compose.foundation.gestures + pointer input. See Runtime.kt for policy.
package androidx.compose.foundation.gestures

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

class PointerInputChange {
    val position: Offset = Offset.Zero
    val pressed: Boolean = true
    val id: Any? = null
}

interface PointerInputScope {
    suspend fun awaitPointerEvent(): Any? = null
    suspend fun detectTapGestures(
        onDoubleTap: ((Offset) -> Unit)? = null,
        onPress: ((Offset) -> Unit)? = null,
        onLongPress: ((Offset) -> Unit)? = null,
        onTap: ((Offset) -> Unit)? = null
    ): Unit = Unit
    suspend fun detectVerticalDragGestures(
        onDragStart: ((Offset) -> Unit)? = null,
        onDragEnd: (() -> Unit)? = null,
        onDragCancel: (() -> Unit)? = null,
        onVerticalDrag: (PointerInputChange, Float) -> Unit
    ): Unit = Unit
    suspend fun detectDragGestures(
        onDragStart: ((Offset) -> Unit)? = null,
        onDrag: (PointerInputChange, Offset) -> Unit = { _, _ -> },
        onDragEnd: () -> Unit = {},
        onDragCancel: () -> Unit = {}
    ): Unit = Unit
    suspend fun tryAwaitRelease(): Boolean = true
}

/**
 * The real declaration is `Modifier.pointerInput(vararg keys: Any?, block: suspend
 * PointerInputScope.() -> Unit)`; the block is `suspend`, so a call that is not inside a coroutine is
 * a compile error in the app too.
 */
fun Modifier.pointerInput(vararg keys: Any?, block: suspend PointerInputScope.() -> Unit): Modifier = this

fun Modifier.pointerInput(key: Any?, block: suspend PointerInputScope.() -> Unit): Modifier = this

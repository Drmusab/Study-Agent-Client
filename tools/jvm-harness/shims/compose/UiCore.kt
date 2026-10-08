// Harness stub — androidx.compose.ui core (Modifier, Alignment). See Runtime.kt for the policy.
package androidx.compose.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

interface Modifier {
    fun then(other: Modifier): Modifier = other

    companion object : Modifier
}

/** Real Compose declares `Modifier.Element`/`ParentDataElement`; screens only chain modifiers. */
interface ModifierElement

@JvmInline
value class Alignment(val value: Int) {
    interface Horizontal {
        fun align(canvasSize: Int, contentSize: Int): Int
    }

    interface Vertical {
        fun align(canvasSize: Int, contentSize: Int): Int
    }

    companion object {
        val TopStart: Alignment = Alignment(0)
        val TopCenter: Alignment = Alignment(1)
        val TopEnd: Alignment = Alignment(2)
        val CenterStart: Alignment = Alignment(3)
        val Center: Alignment = Alignment(4)
        val CenterEnd: Alignment = Alignment(5)
        val BottomStart: Alignment = Alignment(6)
        val BottomCenter: Alignment = Alignment(7)
        val BottomEnd: Alignment = Alignment(8)

        val Start: Horizontal = object : Horizontal {
            override fun align(canvasSize: Int, contentSize: Int) = 0
        }
        val CenterHorizontally: Horizontal = object : Horizontal {
            override fun align(canvasSize: Int, contentSize: Int) = (canvasSize - contentSize) / 2
        }
        val End: Horizontal = object : Horizontal {
            override fun align(canvasSize: Int, contentSize: Int) = canvasSize - contentSize
            override fun toString() = "End"
        }
        val AlignByBaseline: Horizontal = object : Horizontal {
            override fun align(canvasSize: Int, contentSize: Int) = 0
        }
        val Top: Vertical = object : Vertical {
            override fun align(canvasSize: Int, contentSize: Int) = 0
        }
        val CenterVertically: Vertical = object : Vertical {
            override fun align(canvasSize: Int, contentSize: Int) = (canvasSize - contentSize) / 2
        }
        val Bottom: Vertical = object : Vertical {
            override fun align(canvasSize: Int, contentSize: Int) = canvasSize - contentSize
        }
        fun horizontal(offset: Dp): Horizontal = Start
        fun horizontal(alignmentLine: Any?): Horizontal = Start
        fun vertical(offset: Dp): Vertical = Top
        fun End(layoutDirection: LayoutDirection): Horizontal = Start
        fun Start(layoutDirection: LayoutDirection): Horizontal = Start
    }
}

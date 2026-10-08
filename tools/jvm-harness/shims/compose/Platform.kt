// Harness stub — androidx.compose.ui.platform (+ hapticfeedback, viewinterop, draw). See Runtime.kt.
package androidx.compose.ui.platform

import android.content.Context
import android.view.View
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density

// Deliberately NOT declaring `mergeDescendants` here either: it is a parameter of
// `Modifier.semantics`, not a symbol in `androidx.compose.ui.semantics`.
val LocalContext: ProvidableCompositionLocal<Context> = ProvidableCompositionLocal({ error("stub") })
val LocalView: ProvidableCompositionLocal<View> = ProvidableCompositionLocal({ error("stub") })
val LocalDensity: ProvidableCompositionLocal<Density> =
    ProvidableCompositionLocal({ object : Density {
        override val density: Float = 1f
        override val fontScale: Float = 1f
    } })
val LocalLayoutDirection: ProvidableCompositionLocal<LayoutDirection> =
    ProvidableCompositionLocal({ LayoutDirection.Ltr })
val LocalHapticFeedback: ProvidableCompositionLocal<HapticFeedback> =
    ProvidableCompositionLocal({ object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: Any) = Unit
    } })
val LocalConfiguration: ProvidableCompositionLocal<Any> = ProvidableCompositionLocal({ Any() })
val LocalResources: ProvidableCompositionLocal<Any> = ProvidableCompositionLocal({ Any() })
val LocalSoftwareKeyboardController: ProvidableCompositionLocal<Any> = ProvidableCompositionLocal({ Any() })
val LocalClipboardManager: ProvidableCompositionLocal<Any> = ProvidableCompositionLocal({ Any() })

interface HapticFeedback {
    fun performHapticFeedback(hapticFeedbackType: Any)
    fun performHapticFeedback(hapticFeedbackType: Any, performRepeat: Boolean) = Unit
}

fun Modifier.testTag(value: String): Modifier = this

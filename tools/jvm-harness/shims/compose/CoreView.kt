// Harness stub — androidx.core.view (only what the app's theme touches). See Runtime.kt.
package androidx.core.view

import android.view.View
import android.view.Window

class WindowInsetsControllerCompat(val window: Window?, val view: View?) {
    var isAppearanceLightStatusBars: Boolean = false
    var isAppearanceLightNavigationBars: Boolean = false
}

object WindowCompat {
    fun setDecorFitsSystemWindows(window: Window, decorFitsSystemWindows: Boolean) = Unit
    fun getInsetsController(window: Window, view: View): WindowInsetsControllerCompat =
        WindowInsetsControllerCompat(window, view)
}

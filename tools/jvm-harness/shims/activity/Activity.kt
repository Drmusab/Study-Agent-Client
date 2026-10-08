// Harness stub — androidx.activity. See ../compose/Runtime.kt for the policy.
package androidx.activity

import android.app.Activity
import android.os.Bundle
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultContract
import androidx.activity.result.ActivityResultLauncher

/**
 * `ComponentActivity` is what `MainActivity` extends, so the stub has to carry the interfaces the
 * Compose layer reads off it (`LifecycleOwner` for `lifecycleScope`, `ViewModelStoreOwner` and
 * `HasDefaultViewModelProviderFactory` for `viewModel { }`).
 */
open class ComponentActivity : Activity(), LifecycleOwner, ViewModelStoreOwner,
    HasDefaultViewModelProviderFactory, ActivityResultCaller {
    override val lifecycle: Lifecycle = LifecycleRegistry(this)
    override val viewModelStore: ViewModelStore = ViewModelStore()
    open val onBackPressedDispatcher: OnBackPressedDispatcher = OnBackPressedDispatcher()

    final override fun <I, O> registerForActivityResult(
        contract: ActivityResultContract<I, O>,
        callback: ActivityResultCallback<O>
    ): ActivityResultLauncher<I> = object : ActivityResultLauncher<I>() {
        override fun launch(input: I) = Unit
    }
}

open class OnBackPressedDispatcher {
    fun addCallback(enabled: Boolean = true, owner: Any? = null, onBackPressed: () -> Unit) = Unit
    fun addCallback(onBackPressedCallback: OnBackPressedCallback) = Unit
    fun onBackPressed() = Unit
    val hasBackCallbacks: Boolean get() = false
}

abstract class OnBackPressedCallback(var isEnabled: Boolean = true) {
    abstract fun handleOnBackPressed()
    fun remove() = Unit
}

fun ComponentActivity.enableEdgeToEdge(statusBarStyle: Any? = null, navigationBarStyle: Any? = null) = Unit

class SystemBarStyle private constructor() {
    companion object {
        val auto: SystemBarStyle = SystemBarStyle()
        val dark: SystemBarStyle = SystemBarStyle()
        val light: SystemBarStyle = SystemBarStyle()
    }
}

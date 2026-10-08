// Harness stub — androidx.lifecycle.viewmodel.compose. See Runtime.kt for the policy.
package androidx.lifecycle.viewmodel.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.CreationExtras
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

val LocalViewModelStoreOwner: ProvidableCompositionLocal<ViewModelStoreOwner?> =
    compositionLocalOf { null }

val LocalSavedStateRegistryOwner: ProvidableCompositionLocal<Any?> = compositionLocalOf { null }

/**
 * Same shape as the real builder-based `viewModel()`: `key` and a trailing `initializer` whose
 * receiver is `CreationExtras`, which is what the app's navigation host relies on
 * (`viewModel(key = "deck-details:$deckId") { DeckDetailsViewModel(...) }`).
 */
@Composable
inline fun <reified VM : ViewModel> viewModel(
    viewModelStoreOwner: ViewModelStoreOwner = HarnessViewModelStoreOwner,
    key: String? = null,
    factory: ViewModelProvider.Factory? = null,
    extras: CreationExtras = HasDefaultViewModelProviderFactoryDefaults.defaultViewModelCreationExtras,
    noinline initializer: CreationExtras.(arguments: MutableMap<String, Any?>) -> VM =
        { error("harness stub: no initializer") }
): VM = initializer(
    extras,
    HashMap<String, Any?>()
)

object HarnessViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}

object HasDefaultViewModelProviderFactoryDefaults : HasDefaultViewModelProviderFactory

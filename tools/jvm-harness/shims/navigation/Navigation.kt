// Harness stub — androidx.navigation (the non-compose surface the app's nav host touches).
// Signatures mirror `navigation__navigation-common.2.8.0-beta07.txt`; see ../compose/Runtime.kt.
package androidx.navigation

import android.content.Context
import android.os.Bundle
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

abstract class NavDestination {
    var route: String? = null
    open val name: String? get() = route
}

class NavGraph(val startDestinationRoute: String? = null) : NavDestination() {
    var startDestination: Int = 0
    val destinations: List<NavDestination> get() = emptyList()
    fun findNode(id: Int): NavDestination? = null
    fun addDestination(destination: NavDestination) = Unit

    companion object {
        /** The app writes `import androidx.navigation.NavGraph.Companion.findStartDestination`. */
        fun NavGraph.findStartDestination(): NavDestination? = this
    }
}

interface NavBackStackEntry : LifecycleOwner, ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    val destination: NavDestination
    val arguments: Bundle?
    val id: String
    val savedStateHandle: Any?
    override val lifecycle: Lifecycle get() = LifecycleRegistry(this)
    override val viewModelStore: ViewModelStore get() = ViewModelStore()
}

interface Navigator {
    interface Extras {
        val popUpToRoute: String?
    }
}

class NavDeepLink(val uriPattern: String)

class NavType<T>(val name: String = "String", val isNullableAllowed: Boolean = false) {
    companion object {
        val BoolType: NavType<Boolean> = NavType("Boolean")
        val ByteType: NavType<Byte> = NavType("Byte")
        val IntType: NavType<Int> = NavType("Int")
        val LongType: NavType<Long> = NavType("Long")
        val FloatType: NavType<Float> = NavType("Float")
        val DoubleType: NavType<Double> = NavType("Double")
        val CharType: NavType<Char> = NavType("Char")
        val StringType: NavType<String?> = NavType("String", true)
        val StringTypeList: NavType<List<String>> = NavType("StringList")
        val IntArrayList: NavType<IntArray> = NavType("IntegerArrayList")
        val LongArrayList: NavType<LongArray> = NavType("LongArrayList")
        val BundleType: NavType<Bundle?> = NavType("Bundle")
    }
}

class NavArgument {
    var defaultValue: Any? = null
    var nullable: Boolean = false
    var type: NavType<*> = NavType.StringType
}

class NavArgumentBuilder {
    var type: NavType<*>? = null
    var nullable: Boolean = false
    var defaultValue: Any? = null
    var validator: ((Any?) -> Unit)? = null
}

class NamedNavArgument(val name: String, val argument: NavArgument)

fun navArgument(name: String, builder: NavArgumentBuilder.() -> Unit = {}): NamedNavArgument {
    val b = NavArgumentBuilder().apply(builder)
    return NamedNavArgument(name, NavArgument().apply {
        type = b.type ?: NavType.StringType
        nullable = b.nullable
        defaultValue = b.defaultValue
    })
}

interface NavOptions {
    val popUpToRoute: String?
    val popUpToInclusive: Boolean
    val launchSingleTop: Boolean
    val restoreState: Boolean
    val isSingleTop: Boolean get() = launchSingleTop
}

class NavOptionsBuilder {
    var popUpToRoute: String? = null
    var launchSingleTop: Boolean = false
    var restoreState: Boolean = false
    fun popUpTo(route: String, builder: PopUpToBuilder.() -> Unit = {}) { popUpToRoute = route }
    fun popUpTo(id: Int, builder: PopUpToBuilder.() -> Unit = {}) = Unit
}

class PopUpToBuilder {
    var inclusive: Boolean = false
    var saveState: Boolean = false
    var route: String? = null
}

open class NavController(private val context: Context? = null) : Navigator {
    var graph: NavGraph? = null
    val currentBackStackEntry: NavBackStackEntry? get() = null
    val previousBackStackEntry: NavBackStackEntry? get() = null
    val currentDestination: NavDestination? get() = graph
    val visibleEntries: List<NavBackStackEntry> get() = emptyList()
    val backQueue: List<NavBackStackEntry> get() = emptyList()

    fun navigate(route: String, navOptions: NavOptions? = null, navigatorExtras: Navigator.Extras? = null) = Unit
    fun navigate(route: String, builder: NavOptionsBuilder.() -> Unit) = Unit
    fun navigate(route: Int, args: Bundle? = null, navigatorExtras: Navigator.Extras? = null) = Unit
    fun popBackStack(): Boolean = true
    fun popBackStack(route: String, inclusive: Boolean, saveState: Boolean = true): Boolean = true
    fun popBackStack(id: Int, inclusive: Boolean): Boolean = true
    fun navigateUp(): Boolean = true
    fun enableOnBackPressed(enabled: Boolean) = Unit
    fun clearBackstack() = Unit
}

class NavHostController(context: Context? = null) : NavController(context) {
    fun setGraph(graph: NavGraph, startArgs: Bundle? = null) = Unit
}

abstract class NavGraphBuilder {
    abstract fun addDestination(route: String, content: Any)
    abstract fun addGraph(graph: NavGraph)
}

class NavBuilder(context: Context? = null)

// Harness stub — androidx.navigation.compose. See ../compose/Runtime.kt for the policy.
package androidx.navigation.compose

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.navigation.NavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NamedNavArgument

@Composable
fun NavHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
    route: String? = null,
    builder: NavGraphBuilder.() -> Unit = {}
) = Unit

@Composable
fun rememberNavController(): NavHostController = NavHostController()

@Composable
fun NavHostController.currentBackStackEntryAsState(): State<NavBackStackEntry?> =
    object : State<NavBackStackEntry?> {
        override val value: NavBackStackEntry? get() = null
    }

@Composable
fun NavGraphBuilder.composable(
    route: String,
    arguments: List<NamedNavArgument>? = null,
    deepLinks: List<NavDeepLink>? = null,
    content: @Composable (NavBackStackEntry?) -> Unit
) = Unit

@Composable
fun NavGraphBuilder.composable(
    route: Intent,
    arguments: List<NamedNavArgument>? = null,
    deepLinks: List<NavDeepLink>? = null,
    content: @Composable (NavBackStackEntry?) -> Unit
) = Unit

@Composable
fun NavGraphBuilder.dialog(
    route: String,
    arguments: List<NamedNavArgument>? = null,
    deepLinks: List<NavDeepLink>? = null,
    content: @Composable (NavBackStackEntry?) -> Unit
) = Unit

@Composable
fun NavGraphBuilder.navigation(
    startDestination: String,
    route: String? = null,
    arguments: List<NamedNavArgument>? = null,
    deepLinks: List<NavDeepLink>? = null,
    builder: NavGraphBuilder.() -> Unit
) = Unit

package com.studyagent.client.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.di.AppContainer
import com.studyagent.client.ui.theme.AppColors

/** Test tag on the bottom navigation bar. */
const val BOTTOM_NAV_TEST_TAG = "bottom_nav"

private data class PrimaryDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
)

/**
 * Whether the bottom bar should be hidden because the user is in an active study turn
 * (§47). Pure so it is unit-testable.
 */
fun isImmersiveStudy(currentRoute: String?, studyState: StudyState): Boolean =
    currentRoute == Screen.Study.route && when (studyState) {
        is StudyState.Idle, is StudyState.SessionFinished, is StudyState.Error -> false
        else -> true
    }

/**
 * Root scaffold with the primary navigation: Dashboard / Study / Study Control.
 * Connection, Settings and Diagnostics remain secondary, reached from the Dashboard
 * header. The bar hides during immersive study turns so voice study is never crowded.
 */
@Composable
fun StudyAgentRoot(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val studyState by container.studySessionRepository.studyState.collectAsStateWithLifecycle()

    val showBottomBar = currentRoute in Screen.primaryRoutes && !isImmersiveStudy(currentRoute, studyState)

    val destinations = remember {
        listOf(
            PrimaryDestination(Screen.Home.route, "Dashboard", Icons.Default.Dashboard),
            PrimaryDestination(Screen.Study.route, "Study", Icons.Default.School),
            PrimaryDestination(Screen.Control.route, "Control", Icons.Default.Tune)
        )
    }

    Scaffold(
        containerColor = AppColors.appBackground,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = AppColors.surfacePrimary,
                    modifier = Modifier.testTag(BOTTOM_NAV_TEST_TAG)
                ) {
                    destinations.forEach { destination ->
                        val selected = currentRoute == destination.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (selected) return@NavigationBarItem
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = null
                                )
                            },
                            label = { Text(destination.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AppColors.actionPrimary,
                                selectedTextColor = AppColors.contentPrimary,
                                unselectedIconColor = AppColors.contentSecondary,
                                unselectedTextColor = AppColors.contentSecondary,
                                indicatorColor = AppColors.surfaceInteractive
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        AppNavHost(
            container = container,
            navController = navController,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

package com.studyagent.client.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.studyagent.client.di.AppContainer
import com.studyagent.client.ui.screens.connection.ConnectionScreen
import com.studyagent.client.ui.screens.connection.ConnectionViewModel
import com.studyagent.client.ui.screens.diagnostics.DiagnosticsScreen
import com.studyagent.client.ui.screens.diagnostics.DiagnosticsViewModel
import com.studyagent.client.ui.screens.home.HomeScreen
import com.studyagent.client.ui.screens.home.HomeViewModel
import com.studyagent.client.ui.screens.settings.SettingsScreen
import com.studyagent.client.ui.screens.settings.SettingsViewModel
import com.studyagent.client.ui.screens.study.StudyScreen
import com.studyagent.client.ui.screens.study.StudyViewModel

@Composable
fun AppNavHost(
    container: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = modifier
    ) {
        composable(Screen.Home.route) {
            val homeViewModel: HomeViewModel = viewModel {
                HomeViewModel(
                    connectionRepository = container.connectionRepository,
                    studySessionRepository = container.studySessionRepository,
                    audioRouteManager = container.audioRouteManager,
                    studyAudioRouteCoordinator = container.studyAudioRouteCoordinator
                )
            }
            HomeScreen(
                viewModel = homeViewModel,
                onNavigateToStudy = { navController.navigate(Screen.Study.route) },
                onNavigateToConnection = { navController.navigate(Screen.Connection.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToDiagnostics = { navController.navigate(Screen.Diagnostics.route) }
            )
        }

        composable(Screen.Study.route) {
            val studyViewModel: StudyViewModel = viewModel {
                StudyViewModel(
                    studySessionRepository = container.studySessionRepository,
                    connectionRepository = container.connectionRepository,
                    audioRouteManager = container.audioRouteManager,
                    recognitionOrchestrator = container.recognitionOrchestrator,
                    speechOrchestrator = container.speechOrchestrator,
                    preferencesDataStore = container.preferencesDataStore,
                    studyAudioRouteCoordinator = container.studyAudioRouteCoordinator
                )
            }
            StudyScreen(
                viewModel = studyViewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToConnection = { navController.navigate(Screen.Connection.route) }
            )
        }

        composable(Screen.Connection.route) {
            val connViewModel: ConnectionViewModel = viewModel {
                ConnectionViewModel(
                    connectionRepository = container.connectionRepository,
                    profileRepository = container.profileRepository,
                    preferencesDataStore = container.preferencesDataStore
                )
            }
            ConnectionScreen(
                viewModel = connViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            val settingsViewModel: SettingsViewModel = viewModel {
                SettingsViewModel(
                    preferencesDataStore = container.preferencesDataStore,
                    speechOrchestrator = container.speechOrchestrator,
                    recognitionOrchestrator = container.recognitionOrchestrator
                )
            }
            SettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Diagnostics.route) {
            val diagViewModel: DiagnosticsViewModel = viewModel {
                DiagnosticsViewModel(
                    diagnosticsRepository = container.diagnosticsRepository
                )
            }
            DiagnosticsScreen(
                viewModel = diagViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

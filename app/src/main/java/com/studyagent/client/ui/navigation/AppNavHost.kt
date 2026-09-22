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
import com.studyagent.client.ui.screens.control.ControlCenterScreen
import com.studyagent.client.ui.screens.control.ControlCenterViewModel
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
                    capabilityStore = container.capabilityStore,
                    dashboardRepository = container.dashboardRepository,
                    studyControlRepository = container.studyControlRepository,
                    studySessionRepository = container.studySessionRepository,
                    studyAudioRouteCoordinator = container.studyAudioRouteCoordinator
                )
            }
            HomeScreen(
                viewModel = homeViewModel,
                onNavigateToStudy = { navController.navigate(Screen.Study.route) },
                onNavigateToConnection = { navController.navigate(Screen.Connection.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToDiagnostics = { navController.navigate(Screen.Diagnostics.route) },
                onNavigateToControl = { navController.navigate(Screen.Control.route) }
            )
        }

        composable(Screen.Control.route) {
            val controlViewModel: ControlCenterViewModel = viewModel {
                ControlCenterViewModel(
                    studyControlRepository = container.studyControlRepository,
                    dashboardRepository = container.dashboardRepository,
                    capabilityStore = container.capabilityStore,
                    studySessionRepository = container.studySessionRepository,
                    preferencesDataStore = container.preferencesDataStore
                )
            }
            ControlCenterScreen(
                viewModel = controlViewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToStudy = { navController.navigate(Screen.Study.route) }
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
                    recognitionOrchestrator = container.recognitionOrchestrator,
                    ankiDroidHealthRepository = container.ankiDroidHealthRepository,
                    ankiDroidLauncher = container.ankiDroidLauncher
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

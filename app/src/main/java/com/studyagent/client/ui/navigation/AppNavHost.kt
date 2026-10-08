package com.studyagent.client.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.NavType
import androidx.navigation.navArgument
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
import com.studyagent.client.ui.screens.library.DeckDetailsScreen
import com.studyagent.client.ui.screens.library.DeckDetailsViewModel
import com.studyagent.client.ui.screens.library.LibraryScreen
import com.studyagent.client.ui.screens.library.LibraryViewModel
import com.studyagent.client.ui.screens.cardbrowser.CardBrowserScreen
import com.studyagent.client.ui.screens.cardbrowser.CardBrowserViewModel
import com.studyagent.client.ui.screens.cardbrowser.CardDetailsPlaceholderScreen
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
                    studyAudioRouteCoordinator = container.studyAudioRouteCoordinator,
                    // GATE 13 STEP 32.2 — the local Anki start site (capabilities frozen at start).
                    ankiLocalStudyStarter = container.ankiLocalStudyStarter
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

        composable(Screen.Library.route) {
            val libraryViewModel: LibraryViewModel = viewModel {
                LibraryViewModel(
                    backend = container.ankiDroidBackend,
                    libraryRepository = container.ankiLibraryRepository
                )
            }
            LibraryScreen(
                viewModel = libraryViewModel,
                onOpenDeck = { deckId ->
                    navController.navigate(Screen.DeckDetails.createRoute(deckId))
                }
            )
        }

        composable(
            route = Screen.DeckDetails.route,
            arguments = listOf(navArgument(Screen.DeckDetails.ARG_DECK_ID) { type = NavType.StringType })
        ) { backStackEntry ->
            val deckId = Screen.DeckDetails.decodeDeckId(
                backStackEntry.arguments?.getString(Screen.DeckDetails.ARG_DECK_ID)
            ) ?: "invalid-deck-id"
            val detailsViewModel: DeckDetailsViewModel = viewModel(key = "deck-details:$deckId") {
                DeckDetailsViewModel(
                    deckId = deckId,
                    backend = container.ankiDroidBackend,
                    libraryRepository = container.ankiLibraryRepository,
                    studyStarter = container.ankiLocalStudyStarter,
                    studyState = container.studySessionRepository.studyState
                )
            }
            DeckDetailsScreen(
                viewModel = detailsViewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToStudy = {
                    navController.navigate(Screen.Study.route) { launchSingleTop = true }
                },
                onNavigateToCardBrowser = { stableDeckId ->
                    navController.navigate(Screen.CardBrowser.createRoute(stableDeckId))
                }
            )
        }

        composable(
            route = Screen.CardBrowser.route,
            arguments = listOf(
                navArgument(Screen.CardBrowser.ARG_DECK_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val deckId = Screen.CardBrowser.decodeDeckId(
                backStackEntry.arguments?.getString(Screen.CardBrowser.ARG_DECK_ID)
            )
            val browserViewModel: CardBrowserViewModel = viewModel(
                key = "card-browser:${container.ankiDroidBackend.id.stableId}:${deckId ?: "all"}"
            ) {
                CardBrowserViewModel(initialBackend = container.ankiDroidBackend, deckId = deckId)
            }
            CardBrowserScreen(
                viewModel = browserViewModel,
                onNavigateBack = { navController.popBackStack() },
                onOpenCardDetails = { cardRef ->
                    navController.navigate(Screen.CardDetails.createRoute(cardRef))
                }
            )
        }

        composable(
            route = Screen.CardDetails.route,
            arguments = listOf(navArgument(Screen.CardDetails.ARG_CARD_REF) { type = NavType.StringType })
        ) { backStackEntry ->
            CardDetailsPlaceholderScreen(
                cardRef = Screen.CardDetails.decodeCardRef(
                    backStackEntry.arguments?.getString(Screen.CardDetails.ARG_CARD_REF)
                ),
                onNavigateBack = { navController.popBackStack() }
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
            val studyScope = rememberCoroutineScope()
            StudyScreen(
                viewModel = studyViewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToConnection = { navController.navigate(Screen.Connection.route) },
                onOpenAnkiDroid = { studyScope.launch { container.ankiDroidLauncher.open() } },
                onViewCommitDiagnostics = { navController.navigate(Screen.Diagnostics.route) }
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

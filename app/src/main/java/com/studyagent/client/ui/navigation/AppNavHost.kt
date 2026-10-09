package com.studyagent.client.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.studyagent.client.ui.screens.carddetails.CardDetailsScreen
import com.studyagent.client.ui.screens.carddetails.CardDetailsRouteErrorScreen
import com.studyagent.client.ui.screens.carddetails.CardDetailsViewModel
import com.studyagent.client.ui.screens.editnote.EditNoteScreen
import com.studyagent.client.ui.screens.editnote.EditNoteViewModel
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
            val cardRef = Screen.CardDetails.decodeCardRef(
                backStackEntry.arguments?.getString(Screen.CardDetails.ARG_CARD_REF)
            )
            when {
                cardRef == null -> CardDetailsRouteErrorScreen(
                    message = "The stable card reference is invalid. Return to the browser and open the card again.",
                    onNavigateBack = { navController.popBackStack() }
                )
                container.ankiBackendRegistry.find(cardRef.backendId) == null -> CardDetailsRouteErrorScreen(
                    message = "The backend that owns this card reference is not registered. The reference was not opened against another backend.",
                    onNavigateBack = { navController.popBackStack() }
                )
                else -> {
                    val detailsBackend = checkNotNull(container.ankiBackendRegistry.find(cardRef.backendId))
                    val detailsViewModel: CardDetailsViewModel = viewModel(
                        key = "card-details:${cardRef.stableKey}"
                    ) {
                        CardDetailsViewModel(initialBackend = detailsBackend, initialCardRef = cardRef)
                    }
                    // GATE 17 — a saved edit makes the details screen re-read the note: post-write
                    // truth comes from the backend, never from the editor's draft (INV-17-14).
                    val noteSaved by backStackEntry.savedStateHandle
                        .getStateFlow(NOTE_EDIT_SAVED_KEY, false)
                        .collectAsStateWithLifecycle()
                    LaunchedEffect(noteSaved) {
                        if (noteSaved) {
                            backStackEntry.savedStateHandle[NOTE_EDIT_SAVED_KEY] = false
                            detailsViewModel.refresh()
                        }
                    }
                    CardDetailsScreen(
                        viewModel = detailsViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onOpenNoteEditor = { ref ->
                            navController.navigate(Screen.EditNote.createRoute(ref))
                        }
                    )
                }
            }
        }

        composable(
            route = Screen.EditNote.route,
            arguments = listOf(navArgument(Screen.EditNote.ARG_CARD_REF) { type = NavType.StringType })
        ) { backStackEntry ->
            val editCardRef = Screen.EditNote.decodeCardRef(
                backStackEntry.arguments?.getString(Screen.EditNote.ARG_CARD_REF)
            )
            when {
                editCardRef == null -> CardDetailsRouteErrorScreen(
                    message = "The stable card reference for editing is invalid. Return to the card and open it again.",
                    onNavigateBack = { navController.popBackStack() }
                )
                container.ankiBackendRegistry.find(editCardRef.backendId) == null -> CardDetailsRouteErrorScreen(
                    message = "The backend that owns this note is not registered. The editor was not opened against another backend.",
                    onNavigateBack = { navController.popBackStack() }
                )
                else -> {
                    val editBackend = checkNotNull(container.ankiBackendRegistry.find(editCardRef.backendId))
                    val editViewModel: EditNoteViewModel = viewModel(
                        key = "edit-note:${editCardRef.stableKey}"
                    ) {
                        EditNoteViewModel(
                            initialBackend = editBackend,
                            initialCardRef = editCardRef,
                            // The only write path the editor has: the note-mutation coordinator.
                            coordinator = container.noteMutationCoordinator
                        )
                    }
                    EditNoteScreen(
                        viewModel = editViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onSaved = {
                            navController.previousBackStackEntry
                                ?.savedStateHandle
                                ?.set(NOTE_EDIT_SAVED_KEY, true)
                            navController.popBackStack()
                        }
                    )
                }
            }
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

/** Back-stack signal that a note edit reached durable APPLIED, so the details screen re-reads. */
private const val NOTE_EDIT_SAVED_KEY = "gate17_note_edit_saved"

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
import com.studyagent.client.core.anki.AnkiRequiredCapability
import com.studyagent.client.core.anki.CREATION_CONTEXT_REQUIREMENTS
import com.studyagent.client.data.anki.context.AnkiReadContextRequest
import com.studyagent.client.data.anki.context.AnkiReadContextUiState
import com.studyagent.client.data.anki.context.AnkiReadContextViewModel
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
import com.studyagent.client.ui.screens.addnote.AddNoteScreen
import com.studyagent.client.ui.screens.addnote.AddNoteViewModel
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
            val contextOwner: AnkiReadContextViewModel = viewModel(
                key = "library-read-context"
            ) {
                AnkiReadContextViewModel(
                    controller = container.ankiReadContextController,
                    request = AnkiReadContextRequest.New(
                        requiredCapabilities = setOf(AnkiRequiredCapability.DECKS),
                        purpose = "library"
                    )
                )
            }
            val contextState by contextOwner.uiState.collectAsStateWithLifecycle()
            when (val routeState = contextState) {
                AnkiReadContextUiState.Binding -> ReadContextBindingPlaceholder(
                    title = "Library", message = null, onNavigateBack = { navController.popBackStack() }
                )
                is AnkiReadContextUiState.Failed -> ReadContextBindingPlaceholder(
                    title = "Library", message = routeState.failure.safeMessage(),
                    onNavigateBack = { navController.popBackStack() }
                )
                is AnkiReadContextUiState.Bound -> {
                    val binding = routeState.binding
                    val libraryViewModel: LibraryViewModel = viewModel(
                        key = "library:${binding.context.generation.value}"
                    ) {
                        LibraryViewModel(
                            binding = binding,
                            libraryRepository = container.createAnkiLibraryRepository(binding)
                        )
                    }
                    LibraryScreen(
                        viewModel = libraryViewModel,
                        onOpenDeck = { deckRef, readContext ->
                            navController.navigate(Screen.DeckDetails.createRoute(deckRef, readContext))
                        },
                        // GATE 21K — a NEW creation context is seeded from GATE 21E resolution
                        // (DECISION-21B-21), performed at the composition root: one exact backend
                        // for the new context — never the Library's own backend by substitution,
                        // never a hard-coded instance. The route carries that exact id; the
                        // destination binds it exactly and never re-resolves. If no
                        // creation-capable backend resolves, there is nothing to seed and the
                        // tap does not navigate (the Library already surfaces backend state).
                        onOpenAddNote = {
                            container.resolveAddNoteSeedBackendId()?.let { seedBackendId ->
                                navController.navigate(Screen.AddNote.createRoute(seedBackendId))
                            }
                        }
                    )
                }
            }
        }

        composable(
            route = Screen.DeckDetails.route,
            arguments = listOf(
                navArgument(Screen.DeckDetails.ARG_DECK_REF) { type = NavType.StringType },
                navArgument(Screen.DeckDetails.ARG_READ_CONTEXT) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val deckRefToken = backStackEntry.arguments?.getString(Screen.DeckDetails.ARG_DECK_REF)
            val deckRef = Screen.DeckDetails.decodeDeckRef(deckRefToken)
            val readContextToken = backStackEntry.arguments?.getString(Screen.DeckDetails.ARG_READ_CONTEXT)
            val readContext = Screen.DeckDetails.decodeReadContext(readContextToken)
            if (deckRef == null || readContextToken != null && readContext == null) {
                ReadContextBindingPlaceholder(
                    title = "Deck details",
                    message = "The backend-qualified deck reference is invalid. Return to the Library and try again.",
                    onNavigateBack = { navController.popBackStack() }
                )
            } else {
                val bindingRequest = if (readContext != null) {
                    AnkiReadContextRequest.Continue(
                        sourceContext = readContext,
                        resourceBackendId = deckRef.backendId,
                        resourceCollectionKey = deckRef.collectionKey,
                        purpose = "deck_details",
                        requiredCapabilities = setOf(AnkiRequiredCapability.DECKS)
                    )
                } else {
                    AnkiReadContextRequest.Reference(
                        backendId = deckRef.backendId,
                        collectionKey = deckRef.collectionKey,
                        purpose = "deck_details",
                        requiredCapabilities = setOf(AnkiRequiredCapability.DECKS)
                    )
                }
                val contextOwner: AnkiReadContextViewModel = viewModel(
                    key = "deck-read-context:${deckRef.stableKey}:${readContext?.generation?.value ?: "restored"}"
                ) {
                    AnkiReadContextViewModel(container.ankiReadContextController, bindingRequest)
                }
                val contextState by contextOwner.uiState.collectAsStateWithLifecycle()
                when (val routeState = contextState) {
                    AnkiReadContextUiState.Binding -> ReadContextBindingPlaceholder(
                        title = "Deck details", message = null,
                        onNavigateBack = { navController.popBackStack() }
                    )
                    is AnkiReadContextUiState.Failed -> ReadContextBindingPlaceholder(
                        title = "Deck details", message = routeState.failure.safeMessage(),
                        onNavigateBack = { navController.popBackStack() }
                    )
                    is AnkiReadContextUiState.Bound -> {
                        val binding = routeState.binding
                        val detailsViewModel: DeckDetailsViewModel = viewModel(
                            key = "deck-details:${deckRef.stableKey}:${binding.context.generation.value}"
                        ) {
                            DeckDetailsViewModel(
                                deckRef = deckRef,
                                binding = binding,
                                libraryRepository = container.createAnkiLibraryRepository(binding),
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
                            onNavigateToCardBrowser = { ref, readContext ->
                                navController.navigate(Screen.CardBrowser.createRoute(ref, readContext))
                            }
                        )
                    }
                }
            }
        }

        composable(
            route = Screen.CardBrowser.route,
            arguments = listOf(
                navArgument(Screen.CardBrowser.ARG_DECK_REF) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(Screen.CardBrowser.ARG_READ_CONTEXT) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val deckRefToken = backStackEntry.arguments?.getString(Screen.CardBrowser.ARG_DECK_REF)
            val deckRef = Screen.CardBrowser.decodeDeckRef(deckRefToken)
            val readContextToken = backStackEntry.arguments?.getString(Screen.CardBrowser.ARG_READ_CONTEXT)
            val readContext = Screen.CardBrowser.decodeReadContext(readContextToken)
            if ((deckRefToken != null && deckRef == null) ||
                (readContextToken != null && readContext == null) ||
                (deckRef == null && readContext != null)
            ) {
                ReadContextBindingPlaceholder(
                    title = "Card browser",
                    message = "The backend-qualified deck reference is invalid. Return to Deck Details and try again.",
                    onNavigateBack = { navController.popBackStack() }
                )
            } else {
                val bindingRequest = when {
                    deckRef == null -> AnkiReadContextRequest.New(
                        requiredCapabilities = setOf(AnkiRequiredCapability.CARD_BROWSER),
                        purpose = "card_browser_all"
                    )
                    readContext != null -> AnkiReadContextRequest.Continue(
                        sourceContext = readContext,
                        resourceBackendId = deckRef.backendId,
                        resourceCollectionKey = deckRef.collectionKey,
                        purpose = "card_browser_deck",
                        requiredCapabilities = setOf(AnkiRequiredCapability.CARD_BROWSER)
                    )
                    else -> AnkiReadContextRequest.Reference(
                        backendId = deckRef.backendId,
                        collectionKey = deckRef.collectionKey,
                        purpose = "card_browser_deck",
                        requiredCapabilities = setOf(AnkiRequiredCapability.CARD_BROWSER)
                    )
                }
                val ownerKey = "card-browser-read-context:${deckRef?.stableKey ?: "all"}:${readContext?.generation?.value ?: "new"}"
                val contextOwner: AnkiReadContextViewModel = viewModel(key = ownerKey) {
                    AnkiReadContextViewModel(container.ankiReadContextController, bindingRequest)
                }
                val contextState by contextOwner.uiState.collectAsStateWithLifecycle()
                when (val routeState = contextState) {
                    AnkiReadContextUiState.Binding -> ReadContextBindingPlaceholder(
                        title = "Card browser", message = null,
                        onNavigateBack = { navController.popBackStack() }
                    )
                    is AnkiReadContextUiState.Failed -> ReadContextBindingPlaceholder(
                        title = "Card browser", message = routeState.failure.safeMessage(),
                        onNavigateBack = { navController.popBackStack() }
                    )
                    is AnkiReadContextUiState.Bound -> {
                        val binding = routeState.binding
                        val browserViewModel: CardBrowserViewModel = viewModel(
                            key = "card-browser:${binding.context.generation.value}:${deckRef?.stableKey ?: "all"}"
                        ) {
                            CardBrowserViewModel(initialBinding = binding, deckRef = deckRef)
                        }
                        CardBrowserScreen(
                            viewModel = browserViewModel,
                            onNavigateBack = { navController.popBackStack() },
                            onOpenCardDetails = { cardRef, readContext ->
                                navController.navigate(Screen.CardDetails.createRoute(cardRef, readContext))
                            }
                        )
                    }
                }
            }
        }

        composable(
            route = Screen.CardDetails.route,
            arguments = listOf(
                navArgument(Screen.CardDetails.ARG_CARD_REF) { type = NavType.StringType },
                navArgument(Screen.CardDetails.ARG_READ_CONTEXT) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val cardRef = Screen.CardDetails.decodeCardRef(
                backStackEntry.arguments?.getString(Screen.CardDetails.ARG_CARD_REF)
            )
            val readContextToken = backStackEntry.arguments?.getString(Screen.CardDetails.ARG_READ_CONTEXT)
            val readContext = Screen.CardDetails.decodeReadContext(readContextToken)
            if (cardRef == null || readContextToken != null && readContext == null) {
                CardDetailsRouteErrorScreen(
                    message = "The stable card reference is invalid. Return to the browser and open the card again.",
                    onNavigateBack = { navController.popBackStack() }
                )
            } else {
                val bindingRequest = if (readContext != null) {
                    AnkiReadContextRequest.Continue(
                        sourceContext = readContext,
                        resourceBackendId = cardRef.backendId,
                        resourceCollectionKey = cardRef.collectionKey,
                        purpose = "card_details",
                        requiredCapabilities = setOf(AnkiRequiredCapability.CARD_DETAILS)
                    )
                } else {
                    AnkiReadContextRequest.Reference(
                        backendId = cardRef.backendId,
                        collectionKey = cardRef.collectionKey,
                        purpose = "card_details",
                        requiredCapabilities = setOf(AnkiRequiredCapability.CARD_DETAILS)
                    )
                }
                val contextOwner: AnkiReadContextViewModel = viewModel(
                    key = "card-details-read-context:${cardRef.stableKey}:${readContext?.generation?.value ?: "restored"}"
                ) {
                    AnkiReadContextViewModel(container.ankiReadContextController, bindingRequest)
                }
                val contextState by contextOwner.uiState.collectAsStateWithLifecycle()
                when (val routeState = contextState) {
                    AnkiReadContextUiState.Binding -> ReadContextBindingPlaceholder(
                        title = "Card details", message = null,
                        onNavigateBack = { navController.popBackStack() }
                    )
                    is AnkiReadContextUiState.Failed -> ReadContextBindingPlaceholder(
                        title = "Card details", message = routeState.failure.safeMessage(),
                        onNavigateBack = { navController.popBackStack() }
                    )
                    is AnkiReadContextUiState.Bound -> {
                        val binding = routeState.binding
                        val detailsViewModel: CardDetailsViewModel = viewModel(
                            key = "card-details:${cardRef.stableKey}:${binding.context.generation.value}"
                        ) {
                            CardDetailsViewModel(binding = binding, initialCardRef = cardRef)
                        }
                        // A saved edit still requests an authoritative read through this same exact
                        // card/context owner; this changes no mutation, scheduler or session path.
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
        }

        composable(
            route = Screen.AddNote.route,
            arguments = listOf(navArgument(Screen.AddNote.ARG_BACKEND_ID) { type = NavType.StringType })
        ) { backStackEntry ->
            val addNoteBackendId = Screen.AddNote.decodeBackendId(
                backStackEntry.arguments?.getString(Screen.AddNote.ARG_BACKEND_ID)
            )
            val addNoteBackend = addNoteBackendId?.let(container.ankiBackendRegistry::find)
            if (addNoteBackend == null) {
                // GATE 21K — the route carries the exact backend a fresh resolution seeded for this
                // new creation context. It binds exactly (no AUTO, no sibling fallback): an
                // unregistered seed is a typed failure placeholder, never a guess.
                ReadContextBindingPlaceholder(
                    title = "New note",
                    message = "The note-creation backend for this screen is not registered.",
                    onNavigateBack = { navController.popBackStack() }
                )
            } else {
                val bindingRequest = AnkiReadContextRequest.Reference(
                    backendId = addNoteBackend.id,
                    collectionKey = addNoteBackend.currentCollectionIdentity
                        ?.takeIf { it.backendId == addNoteBackend.id }
                        ?.collectionKey,
                    purpose = "add_note_model_reads",
                    requiredCapabilities = CREATION_CONTEXT_REQUIREMENTS
                )
                val contextOwner: AnkiReadContextViewModel = viewModel(
                    key = "add-note-read-context:${addNoteBackend.id.stableId}"
                ) {
                    AnkiReadContextViewModel(container.ankiReadContextController, bindingRequest)
                }
                val contextState by contextOwner.uiState.collectAsStateWithLifecycle()
                when (val routeState = contextState) {
                    AnkiReadContextUiState.Binding -> ReadContextBindingPlaceholder(
                        title = "New note", message = null,
                        onNavigateBack = { navController.popBackStack() }
                    )
                    is AnkiReadContextUiState.Failed -> ReadContextBindingPlaceholder(
                        title = "New note", message = routeState.failure.safeMessage(),
                        onNavigateBack = { navController.popBackStack() }
                    )
                    is AnkiReadContextUiState.Bound -> {
                        val binding = routeState.binding
                        val addNoteViewModel: AddNoteViewModel = viewModel(
                            key = "add-note:${binding.context.generation.value}"
                        ) {
                            AddNoteViewModel(
                                binding = binding,
                                // GATE 21K — the coordinator routes every write to the draft's /
                                // record's own registered backend; the binding scopes the reads.
                                coordinator = container.noteCreationCoordinator,
                                mediaProbe = container.addNoteMediaProbe
                            )
                        }
                        AddNoteScreen(
                            viewModel = addNoteViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
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
                    studyAudioRouteCoordinator = container.studyAudioRouteCoordinator,
                    // GATE 21 — Study shows the session's LOCKED backend, never the preference.
                    ankiSessionLocks = container.ankiStudyBackendLocks
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
                    ankiDroidLauncher = container.ankiDroidLauncher,
                    // GATE 21M — instance-exact backend selection over the existing authoritative
                    // components: the registry inventory, the 21D observation point, the 21E
                    // preference repository (as the projection it already publishes) and the 21G
                    // session locks. No new registry, resolver or selection authority is created here.
                    ankiBackendRegistry = container.ankiBackendRegistry,
                    ankiBackendStatusCenter = container.ankiBackendStatusCenter,
                    ankiBackendPreference = container.backendPreferenceRepository.preference,
                    ankiBackendPreferenceWriteError =
                        container.backendPreferenceRepository.lastWriteError,
                    ankiBackendPreferenceWriter = { preference ->
                        container.backendPreferenceRepository.setPreference(preference)
                    },
                    ankiSessionLocks = container.ankiStudyBackendLocks,
                    ankiBackendAutoPreview = container.ankiBackendAutoPreview
                )
            }
            SettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = { navController.popBackStack() },
                // GATE 21M — safe troubleshooting destinations only (CHECKPOINT 21M-20).
                onOpenDiagnostics = { navController.navigate(Screen.Diagnostics.route) },
                onOpenConnection = { navController.navigate(Screen.Connection.route) }
            )
        }

        composable(Screen.Diagnostics.route) {
            val diagViewModel: DiagnosticsViewModel = viewModel {
                DiagnosticsViewModel(
                    diagnosticsRepository = container.diagnosticsRepository,
                    // GATE 21M — read-only backend diagnostics: observation snapshots plus the
                    // unresolved-transaction projections each durable family already exposes. Nothing
                    // here can write a ledger, reassign a transaction or probe a backend
                    // (AUDIT-21M-R2-13).
                    ankiBackendStates = container.ankiBackendStatusCenter.states,
                    ankiBackendPreference = container.backendPreferenceRepository.preference,
                    ankiBackendRouting = container.ankiBackendRouting,
                    ankiSessionLocks = container.ankiStudyBackendLocks,
                    ankiBackendRegistry = container.ankiBackendRegistry,
                    ankiUnresolvedTransactions = container.ankiUnresolvedTransactions
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

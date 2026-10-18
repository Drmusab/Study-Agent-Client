package com.studyagent.client.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studyagent.client.appContainer
import com.studyagent.client.awaitNode
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.BackendPreference
import com.studyagent.client.data.preferences.decodeStoredBackendPreference
import com.studyagent.client.ui.components.anki.AnkiBackendAutoRowUi
import com.studyagent.client.ui.screens.settings.ANKI_BACKEND_OPTION_TEST_TAG
import com.studyagent.client.ui.screens.settings.SETTINGS_LIST_TEST_TAG
import com.studyagent.client.ui.screens.settings.SettingsScreen
import com.studyagent.client.ui.screens.settings.SettingsViewModel
import com.studyagent.client.ui.theme.StudyAgentTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GATE 21M — Compose-level proof that the production Settings screen exposes the authoritative
 * preference and does not turn an absent PC Anki adapter into an operational choice.
 *
 * The JVM suite covers the pure selection model and controller. This test crosses the real screen,
 * semantics, composition-root wiring and DataStore preference flow. It uses the app's actual
 * registry, which contains only the configured AnkiDroid adapter; no Fake backend is inserted into
 * production inventory.
 *
 * Android execution is required for this test. A JVM harness PASS does not execute it.
 */
@RunWith(AndroidJUnit4::class)
class Gate21mBackendSelectionInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private val container by lazy { appContainer() }
    private var originalPreference: BackendPreference? = null
    private var changedPreference = false

    private val autoTag = "$ANKI_BACKEND_OPTION_TEST_TAG${AnkiBackendAutoRowUi.KEY}"
    private val ankiDroidTag = "$ANKI_BACKEND_OPTION_TEST_TAG${AnkiBackendId.AnkiDroidLocal.stableId}"
    private val unimplementedPcTag = "${ANKI_BACKEND_OPTION_TEST_TAG}kind:PC_AGENT"

    @Before
    fun setUp() {
        originalPreference = runBlocking {
            val stored = container.preferencesDataStore.backendPreferenceSnapshotFlow.first()
            decodeStoredBackendPreference(stored.modeStableId, stored.instancePinStableId)
        }
        // An invalid legacy value must not be overwritten just to make a test fixture convenient.
        assumeTrue(
            "the stored backend preference is not writable; preserve it and skip this integration test",
            originalPreference !is BackendPreference.InvalidStored
        )
        assertTrue(
            "test setup must persist AUTO through the authoritative repository",
            runBlocking {
                container.backendPreferenceRepository.setPreference(BackendPreference.Auto)
            }
        )
        changedPreference = true

        compose.setContent {
            StudyAgentTheme {
                val viewModel = remember(container) { createBackendAwareSettingsViewModel() }
                SettingsScreen(
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onOpenDiagnostics = {},
                    onOpenConnection = {}
                )
            }
        }
    }

    @After
    fun restorePreference() {
        val original = originalPreference
        if (changedPreference && original != null) {
            runBlocking { container.backendPreferenceRepository.setPreference(original) }
        }
    }

    @Test
    fun settings_shows_auto_selected_and_pc_anki_as_disabled_information() {
        compose.awaitNode(hasTestTag(autoTag))
        val list = compose.onNodeWithTag(SETTINGS_LIST_TEST_TAG)

        list.performScrollToNode(hasTestTag(autoTag))
        compose.onNodeWithTag(autoTag).assertExists().assertIsSelected()

        list.performScrollToNode(hasTestTag(unimplementedPcTag))
        compose.onNodeWithTag(unimplementedPcTag).assertExists().assertIsNotEnabled()
        compose.onNodeWithText("Not implemented in this build", substring = true).assertExists()
    }

    @Test
    fun configured_ankidroid_and_auto_choices_persist_and_reflect_selection() {
        compose.awaitNode(hasTestTag(autoTag))
        val list = compose.onNodeWithTag(SETTINGS_LIST_TEST_TAG)

        list.performScrollToNode(hasTestTag(ankiDroidTag))
        compose.onNodeWithTag(ankiDroidTag).performClick()
        awaitPreference(BackendPreference.Explicit(AnkiBackendId.AnkiDroidLocal))
        compose.onNodeWithTag(ankiDroidTag).assertIsSelected()

        list.performScrollToNode(hasTestTag(autoTag))
        compose.onNodeWithTag(autoTag).performClick()
        awaitPreference(BackendPreference.Auto)
        compose.onNodeWithTag(autoTag).assertIsSelected()
        assertEquals(BackendPreference.Auto, container.backendPreferenceRepository.preference.value)
    }

    private fun awaitPreference(expected: BackendPreference) {
        compose.waitUntil(timeoutMillis = 5_000) {
            container.backendPreferenceRepository.preference.value == expected
        }
    }

    /** Mirrors AppNavHost's production wiring; no test-only registry or resolver is supplied. */
    private fun createBackendAwareSettingsViewModel(): SettingsViewModel = SettingsViewModel(
        preferencesDataStore = container.preferencesDataStore,
        speechOrchestrator = container.speechOrchestrator,
        recognitionOrchestrator = container.recognitionOrchestrator,
        ankiDroidHealthRepository = container.ankiDroidHealthRepository,
        ankiDroidLauncher = container.ankiDroidLauncher,
        ankiBackendRegistry = container.ankiBackendRegistry,
        ankiBackendStatusCenter = container.ankiBackendStatusCenter,
        ankiBackendPreference = container.backendPreferenceRepository.preference,
        ankiBackendPreferenceWriteError = container.backendPreferenceRepository.lastWriteError,
        ankiBackendPreferenceWriter = { preference ->
            container.backendPreferenceRepository.setPreference(preference)
        },
        ankiSessionLocks = container.ankiStudyBackendLocks,
        ankiBackendAutoPreview = container.ankiBackendAutoPreview
    )
}

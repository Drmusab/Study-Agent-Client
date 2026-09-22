package com.studyagent.client.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studyagent.client.appContainer
import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.ui.screens.settings.SETTINGS_LIST_TEST_TAG
import com.studyagent.client.ui.screens.settings.SettingsScreen
import com.studyagent.client.ui.screens.settings.SettingsViewModel
import com.studyagent.client.ui.screens.settings.settingSwitchTestTag
import com.studyagent.client.ui.theme.StudyAgentTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings persistence against the **real** DataStore and the real settings screen.
 *
 * This is the one place where the Android layer is the subject: the JVM suite covers the codec
 * (round-trip, nullable clears, legacy migration, corrupt payload isolation), while only a device
 * proves that the value the user flipped actually reaches the store the app reads on the next
 * screen. A true process-restart check stays in the device matrix (`docs/REAL_DEVICE_TEST_MATRIX.md`,
 * case RD-F6) because an instrumented test shares the app's process.
 *
 * Nothing here depends on the initial value of the setting: each test reads it, flips it, and asserts
 * the opposite — a suite that assumed a default would fail on any device where a user (or another
 * test) already changed it.
 */
@RunWith(AndroidJUnit4::class)
class SettingsPersistenceInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    /** Bumped to rebuild the screen with a fresh ViewModel, i.e. a fresh read of the store. */
    private var generation by mutableIntStateOf(0)

    private val toggleTitle = "Auto-play Question"
    private val toggleTag = settingSwitchTestTag(toggleTitle)

    @Before
    fun setUp() {
        val container = appContainer()
        compose.setContent {
            StudyAgentTheme {
                val viewModel = remember(generation) {
                    SettingsViewModel(
                        preferencesDataStore = container.preferencesDataStore,
                        speechOrchestrator = container.speechOrchestrator,
                        recognitionOrchestrator = container.recognitionOrchestrator,
                        ankiDroidHealthRepository = container.ankiDroidHealthRepository,
                        ankiDroidLauncher = container.ankiDroidLauncher
                    )
                }
                SettingsScreen(viewModel = viewModel, onNavigateBack = {})
            }
        }
    }

    private fun settings(): AppSettings = runBlocking {
        appContainer().preferencesDataStore.settingsFlow.first()
    }

    private fun toggleState(): Boolean? =
        compose.onNodeWithTag(toggleTag)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.ToggleableState)
            ?.let { it == ToggleableState.On }

    /** Scrolls the row into view, then waits until the switch shows what the store actually holds. */
    private fun awaitToggleMatchesStore() {
        compose.onNodeWithTag(SETTINGS_LIST_TEST_TAG).performScrollToNode(hasTestTag(toggleTag))
        compose.waitUntil(5_000) { toggleState() == settings().autoPlayQuestion }
    }

    @Test
    fun a_toggled_setting_reaches_the_store_and_survives_a_fresh_read_of_the_screen() {
        compose.onNodeWithTag(SETTINGS_LIST_TEST_TAG).assertExists()

        val before = settings().autoPlayQuestion
        awaitToggleMatchesStore()

        compose.onNodeWithTag(toggleTag).performClick()

        // Committed to the store (DataStore only emits after the write succeeds).
        compose.waitUntil(5_000) { settings().autoPlayQuestion != before }

        // And a fresh ViewModel — a fresh subscription and a fresh read of the same store — shows
        // the value the user chose, not the default it was constructed with.
        compose.runOnUiThread { generation++ }
        awaitToggleMatchesStore()
        assertNotEquals(before, settings().autoPlayQuestion)
    }

    @Test
    fun toggling_one_setting_does_not_rewrite_its_neighbours() {
        val before = settings()
        awaitToggleMatchesStore()

        compose.onNodeWithTag(toggleTag).performClick()
        compose.waitUntil(5_000) { settings().autoPlayQuestion != before.autoPlayQuestion }

        // A whole-object write that dropped unrelated fields would show up here as a diff in any
        // other property — exactly the regression the round-trip codec tests cannot see end to end.
        val after = settings()
        assertEquals(
            "only the toggled setting may change",
            before.copy(autoPlayQuestion = after.autoPlayQuestion),
            after
        )
    }
}

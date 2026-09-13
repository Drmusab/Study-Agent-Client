package com.studyagent.client.ui

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studyagent.client.awaitNoNode
import com.studyagent.client.awaitNode
import com.studyagent.client.appContainer
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.ui.navigation.AppNavHost
import com.studyagent.client.ui.screens.diagnostics.DIAGNOSTICS_LIST_TEST_TAG
import com.studyagent.client.ui.screens.settings.SETTINGS_LIST_TEST_TAG
import com.studyagent.client.ui.theme.StudyAgentTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The navigation shell on a real device: Home composes, the screens are reachable, and a screen
 * that has to render while no PC agent is reachable still renders.
 *
 * `AppNavHost` is hosted directly instead of launching `MainActivity`, because the activity asks
 * for microphone/notification permissions on start and a system dialog would sit between the test
 * and the UI. The activity itself is covered by [com.studyagent.client.MainActivitySmokeInstrumentedTest].
 */
@RunWith(AndroidJUnit4::class)
class AppShellInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        val container = appContainer()
        compose.setContent {
            StudyAgentTheme {
                AppNavHost(container = container)
            }
        }
    }

    @Test
    fun home_screen_renders_with_its_navigation_actions() {
        compose.awaitNode(hasText("Study Agent"))
        compose.onNodeWithContentDescription("Refresh dashboard").assertExists()
        compose.onNodeWithContentDescription("Diagnostics").assertExists()
        compose.onNodeWithContentDescription("Settings").assertExists()
    }

    @Test
    fun settings_screen_opens_from_home_and_back_returns_home() {
        compose.awaitNode(hasContentDescription("Settings"))
        compose.onNodeWithContentDescription("Settings").performClick()

        compose.awaitNode(hasTestTag(SETTINGS_LIST_TEST_TAG))

        compose.onNodeWithContentDescription("Back").performClick()
        compose.awaitNode(hasText("Study Agent"))
    }

    @Test
    fun diagnostics_screen_opens_from_home_and_clearing_logs_removes_the_seeded_rows() {
        // Seed rows through the app's own logger: the instrumented test shares the process with the
        // app, so these are the same buffer, the same sanitizer and the same bounded ring the UI
        // reads. No fake logger is involved.
        repeat(3) { index ->
            AppLogger.i("InstrumentedTest", "clear-probe row $index")
        }

        compose.awaitNode(hasContentDescription("Diagnostics"))
        compose.onNodeWithContentDescription("Diagnostics").performClick()
        compose.awaitNode(hasTestTag(DIAGNOSTICS_LIST_TEST_TAG))

        val list = compose.onNodeWithTag(DIAGNOSTICS_LIST_TEST_TAG)
        list.performScrollToNode(hasText("clear-probe row 2", substring = true))
        compose.onNodeWithText("clear-probe row 2", substring = true).assertExists()

        compose.onNodeWithContentDescription("Clear logs").performClick()

        // The section still renders — and the seeded rows are gone from the buffer itself, which is
        // what the UI registers read.
        list.performScrollToNode(hasText("LOGS (", substring = true))
        compose.awaitNoNode(hasText("clear-probe row", substring = true))
        compose.waitUntil(5_000) {
            appContainer().diagnosticsRepository.logs.value.none { it.message.contains("clear-probe row") }
        }
    }
}

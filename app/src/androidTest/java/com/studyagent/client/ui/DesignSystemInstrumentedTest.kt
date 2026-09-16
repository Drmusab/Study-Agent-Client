package com.studyagent.client.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studyagent.client.appContainer
import com.studyagent.client.awaitNode
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.ui.components.ConnectionBadge
import com.studyagent.client.ui.components.STUDY_PHASE_CHIP_TEST_TAG
import com.studyagent.client.ui.components.StudyPhase
import com.studyagent.client.ui.components.StudyPhaseChip
import com.studyagent.client.ui.navigation.BOTTOM_NAV_TEST_TAG
import com.studyagent.client.ui.navigation.StudyAgentRoot
import com.studyagent.client.ui.theme.StudyAgentTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Design-system contracts (§85/§106): the shell exposes the three primary destinations, the
 * phase chip communicates by text (not colour alone), and status chips meet the touch-target
 * floor.
 */
@RunWith(AndroidJUnit4::class)
class DesignSystemInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun root_shell_shows_bottom_navigation_with_primary_destinations() {
        val container = appContainer()
        compose.setContent {
            StudyAgentTheme { StudyAgentRoot(container = container) }
        }
        compose.awaitNode(hasText("Study Agent"))
        compose.awaitNode(hasTestTag(BOTTOM_NAV_TEST_TAG))
        compose.onNodeWithText("Dashboard").assertIsDisplayed()
        compose.onNodeWithText("Study").assertIsDisplayed()
        compose.onNodeWithText("Control").assertIsDisplayed()

        // Navigating to Study Control keeps the bar (no active session).
        compose.onNodeWithText("Control").performClick()
        compose.awaitNode(hasText("Study Control"))
        compose.onNodeWithTag(BOTTOM_NAV_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun phase_chip_exposes_phase_as_text() {
        compose.setContent {
            StudyAgentTheme { StudyPhaseChip(phase = StudyPhase.LISTENING) }
        }
        compose.onNodeWithTag(STUDY_PHASE_CHIP_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithText("Listening").assertIsDisplayed()
        compose.onNodeWithContentDescription("Study phase: Listening").assertExists()
    }

    @Test
    fun connection_badge_is_tappable_and_meets_touch_floor() {
        compose.setContent {
            StudyAgentTheme {
                ConnectionBadge(
                    connectionState = ConnectionState.Connected("10.0.0.2", 8765, "Study PC", 24),
                    onClick = {}
                )
            }
        }
        val badge = compose.onNodeWithContentDescription("Connection: Connected", substring = true)
        badge.assertIsDisplayed()
        badge.assertHeightIsAtLeast(40.dp)
        compose.onNodeWithText("Study PC", substring = true).assertExists()
    }
}

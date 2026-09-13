package com.studyagent.client.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studyagent.client.awaitNode
import com.studyagent.client.appContainer
import com.studyagent.client.core.models.Rating
import com.studyagent.client.ui.components.PUSH_TO_TALK_TEST_TAG
import com.studyagent.client.ui.components.ratingTestTag
import com.studyagent.client.ui.screens.study.StudyScreen
import com.studyagent.client.ui.screens.study.StudyScreenTags
import com.studyagent.client.ui.screens.study.StudyViewModel
import com.studyagent.client.ui.theme.StudyAgentTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The study controls on a real device, in the state a device is actually in before a session exists.
 *
 * What this proves and what it deliberately does not: it proves the controls compose, are reachable,
 * meet the 48 dp touch-target minimum, and that nothing about the voice state is *claimed* while
 * there is no session. It does **not** re-test the voice loop — half-duplex behaviour, exactly-once
 * submission and route changes are proven by the JVM suites (`docs/TESTING_STRATEGY.md`) and by the
 * real-device matrix.
 */
@RunWith(AndroidJUnit4::class)
class StudyControlsInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private val ratings = listOf(Rating.AGAIN, Rating.HARD, Rating.GOOD, Rating.EASY)

    @Before
    fun setUp() {
        val container = appContainer()
        val viewModel = StudyViewModel(
            studySessionRepository = container.studySessionRepository,
            connectionRepository = container.connectionRepository,
            audioRouteManager = container.audioRouteManager,
            recognitionOrchestrator = container.recognitionOrchestrator,
            speechOrchestrator = container.speechOrchestrator,
            preferencesDataStore = container.preferencesDataStore,
            studyAudioRouteCoordinator = container.studyAudioRouteCoordinator
        )
        compose.setContent {
            StudyAgentTheme {
                StudyScreen(
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onNavigateToConnection = {}
                )
            }
        }
    }

    @Test
    fun idle_screen_shows_the_voice_controls_with_accessible_touch_targets() {
        compose.awaitNode(hasTestTag(PUSH_TO_TALK_TEST_TAG))
        compose.onNodeWithTag(PUSH_TO_TALK_TEST_TAG).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Push to Talk", substring = true).assertExists()

        // The half-duplex claim is never made without a voice loop: an idle screen says idle.
        compose.onNodeWithTag(StudyScreenTags.PHASE_CHIP).assertTextContains("Idle", substring = true)

        // No card is loaded, so the question area states that instead of showing an empty question.
        compose.onNodeWithText("Press 'Start' to begin loading cards", substring = true).assertExists()

        compose.onNodeWithTag(StudyScreenTags.PAUSE_TOGGLE).assertExists()
        compose.onNodeWithTag(StudyScreenTags.END_SESSION).assertExists()
    }

    @Test
    fun every_rating_button_exists_meets_the_touch_target_and_is_disabled_before_a_card() {
        compose.awaitNode(hasTestTag(ratingTestTag(Rating.GOOD)))
        ratings.forEach { rating ->
            val node = compose.onNodeWithTag(ratingTestTag(rating))
            node.assertExists()
            node.assertHeightIsAtLeast(48.dp)
            node.assertIsNotEnabled()
        }
    }

    @Test
    fun pause_and_end_controls_are_reachable() {
        // Reachability is the assertion here: these controls are the only way to pause or end a
        // hands-free session, so they must have a click action on a real device.
        compose.awaitNode(hasTestTag(StudyScreenTags.PAUSE_TOGGLE))
        compose.onNodeWithTag(StudyScreenTags.PAUSE_TOGGLE).assertIsEnabled()
        compose.onNodeWithTag(StudyScreenTags.END_SESSION).assertIsEnabled()
    }

    @Test
    fun tapping_push_to_talk_without_a_session_does_not_claim_the_microphone_is_open() {
        compose.awaitNode(hasTestTag(PUSH_TO_TALK_TEST_TAG))
        compose.onNodeWithTag(PUSH_TO_TALK_TEST_TAG).performClick()

        // The gate refuses push-to-talk outside an active study window, so the screen must still say
        // idle and must still show the "hold or tap" instruction rather than a listening claim.
        compose.onNodeWithTag(StudyScreenTags.PHASE_CHIP).assertTextContains("Idle", substring = true)
        compose.onNodeWithText("Push to Talk", substring = true).assertExists()
    }
}

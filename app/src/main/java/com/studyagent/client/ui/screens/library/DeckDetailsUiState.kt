package com.studyagent.client.ui.screens.library

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiDeckSummary
import com.studyagent.client.core.anki.AnkiError

sealed interface DeckDetailsUiState {
    data object Loading : DeckDetailsUiState

    data class Ready(
        val summary: AnkiDeckSummary,
        val backend: AnkiBackendId,
        val availability: AnkiAvailability,
        val capabilities: AnkiCapabilities,
        val canStudy: Boolean,
        val activeSessionPreventsStudy: Boolean,
        val isSummaryStale: Boolean,
        val isRefreshing: Boolean = false,
        val isStartingStudy: Boolean = false,
        val readError: AnkiError? = null,
        val studyStartError: AnkiError? = null
    ) : DeckDetailsUiState

    data class Unavailable(
        val backend: AnkiBackendId,
        val reason: AnkiAvailability,
        val isRetrying: Boolean = false
    ) : DeckDetailsUiState

    data class Error(
        val backend: AnkiBackendId,
        val error: AnkiError,
        val isRetrying: Boolean = false
    ) : DeckDetailsUiState
}

sealed interface DeckDetailsEvent {
    /** The normal StudySession start request was accepted by the app's session-start path. */
    data object StudyStarted : DeckDetailsEvent
}

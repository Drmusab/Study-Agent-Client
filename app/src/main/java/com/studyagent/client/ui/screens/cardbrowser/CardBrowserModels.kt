package com.studyagent.client.ui.screens.cardbrowser

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardFilters
import com.studyagent.client.core.anki.AnkiCardListItem
import com.studyagent.client.core.anki.AnkiCardSort
import com.studyagent.client.core.anki.AnkiCardType
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiFlag
import com.studyagent.client.core.anki.AnkiSchedulingInfo

/** Query controls as user-visible state; cursor and page size remain ViewModel/backend concerns. */
data class CardBrowserQueryUi(
    val deckId: String? = null,
    val searchText: String = "",
    val filters: AnkiCardFilters = AnkiCardFilters(),
    val sort: AnkiCardSort = AnkiCardSort.Default
) {
    init { require(deckId == null || deckId.isNotBlank()) }

    val hasSearchOrFilters: Boolean
        get() = searchText.isNotBlank() || !filters.isEmpty
}

/** Display-only projection. The original AnkiCardListItem / full card never reaches Compose. */
data class CardBrowserRow(
    val cardRef: com.studyagent.client.core.anki.AnkiCardRef,
    val questionPreview: String?,
    val answerPreview: String?,
    val deckName: String?,
    val tags: List<String>,
    val flag: AnkiFlag?,
    val type: AnkiCardType?,
    val scheduling: AnkiSchedulingInfo?,
    val suspended: Boolean?,
    val buried: Boolean?
) {
    val stableKey: String get() = cardRef.stableKey

    companion object {
        fun from(item: AnkiCardListItem, answerPreviewAllowed: Boolean): CardBrowserRow = CardBrowserRow(
            cardRef = item.cardRef,
            questionPreview = item.questionText?.let(::safePreview),
            answerPreview = if (answerPreviewAllowed) item.answerText?.let(::safePreview) else null,
            deckName = item.deckName?.let(::safePreview),
            tags = item.tags.take(3).map(::safePreview).filter(String::isNotBlank),
            flag = item.flag,
            type = item.type,
            scheduling = item.scheduling,
            suspended = item.suspended,
            buried = item.buried
        )

        /**
         * Enforce a text-only row boundary even if a buggy adapter sends markup in a preview field.
         * This is intentionally not an HTML renderer or a full HTML parser; Compose always receives
         * ordinary text and can never execute provider markup or load media.
         */
        private fun safePreview(value: String): String {
            val textOnly = value
                .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
                .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>"), " ")
                .replace(Regex("<[^>]*>"), " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
            val normalized = buildString(textOnly.length) {
                var pendingSpace = false
                textOnly.forEach { character ->
                    if (character.isWhitespace() || Character.isSpaceChar(character)) {
                        if (isNotEmpty()) pendingSpace = true
                    } else {
                        if (pendingSpace) append(' ')
                        append(character)
                        pendingSpace = false
                    }
                }
            }
            return if (normalized.length <= PREVIEW_MAX_CHARS) normalized
            else normalized.take(PREVIEW_MAX_CHARS - 1).trimEnd() + "…"
        }

        private const val PREVIEW_MAX_CHARS = 220
    }
}

/** Only supported options are passed to the reusable filter controls. */
data class FilterChipModel(
    val key: String,
    val label: String,
    val selected: Boolean,
    val contentDescription: String = label
)

data class SortOptionModel(
    val sort: AnkiCardSort,
    val label: String
)

/** One canonical state; paging activity only exists inside Ready. */
sealed interface CardBrowserUiState {
    val query: CardBrowserQueryUi
    val capabilities: com.studyagent.client.core.anki.AnkiCardBrowserCapabilities

    data class Loading(
        override val query: CardBrowserQueryUi,
        override val capabilities: com.studyagent.client.core.anki.AnkiCardBrowserCapabilities
    ) : CardBrowserUiState

    data class Ready(
        val rows: List<CardBrowserRow>,
        override val query: CardBrowserQueryUi,
        override val capabilities: com.studyagent.client.core.anki.AnkiCardBrowserCapabilities,
        val hasMore: Boolean,
        val isLoadingMore: Boolean,
        val totalCount: Int?,
        val appendError: AnkiError? = null
    ) : CardBrowserUiState

    data class Empty(
        override val query: CardBrowserQueryUi,
        val reason: Reason,
        override val capabilities: com.studyagent.client.core.anki.AnkiCardBrowserCapabilities,
        val totalCount: Int? = null
    ) : CardBrowserUiState {
        enum class Reason { NO_CARDS_IN_SCOPE, NO_MATCHES }
    }

    data class Unavailable(
        override val query: CardBrowserQueryUi,
        val backendId: AnkiBackendId,
        val availability: AnkiAvailability,
        override val capabilities: com.studyagent.client.core.anki.AnkiCardBrowserCapabilities,
        /** Non-null for a Ready backend that does not advertise the requested browse capability. */
        val unsupportedFeature: String? = null
    ) : CardBrowserUiState

    data class Error(
        override val query: CardBrowserQueryUi,
        val backendId: AnkiBackendId,
        val error: AnkiError,
        override val capabilities: com.studyagent.client.core.anki.AnkiCardBrowserCapabilities
    ) : CardBrowserUiState
}

fun AnkiCardSort.displayLabel(): String = when (this) {
    AnkiCardSort.Default -> "Default order"
    AnkiCardSort.Due -> "Due"
    AnkiCardSort.Created -> "Created"
    AnkiCardSort.Modified -> "Modified"
    AnkiCardSort.Reps -> "Repetitions"
    AnkiCardSort.Lapses -> "Lapses"
}

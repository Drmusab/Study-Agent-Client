package com.studyagent.client.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.mergeDescendants
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiDeckCounts
import com.studyagent.client.ui.components.BannerTone
import com.studyagent.client.ui.components.InfoBanner
import com.studyagent.client.ui.components.PrimaryButton
import com.studyagent.client.ui.components.SecondaryButton
import com.studyagent.client.ui.components.StudyAgentTopBar
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing

const val DECK_DETAILS_TEST_TAG = "deck_details"

@Composable
fun DeckDetailsScreen(
    viewModel: DeckDetailsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToStudy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing = when (val current = state) {
        DeckDetailsUiState.Loading -> true
        is DeckDetailsUiState.Unavailable -> current.isRetrying
        is DeckDetailsUiState.Error -> current.isRetrying
        is DeckDetailsUiState.Ready -> current.isRefreshing
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                DeckDetailsEvent.StudyStarted -> onNavigateToStudy()
            }
        }
    }

    Scaffold(
        containerColor = AppColors.appBackground,
        topBar = {
            StudyAgentTopBar(
                title = "Deck details",
                onBack = onNavigateBack,
                subtitle = (state as? DeckDetailsUiState.Ready)?.summary?.deck?.name,
                trailing = {
                    IconButton(
                        onClick = viewModel::refresh,
                        enabled = !isRefreshing,
                        modifier = Modifier.semantics { contentDescription = "Refresh deck details" }
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = AppColors.actionAccent
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = AppColors.contentPrimary)
                        }
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val contentWidth = maxWidth.coerceAtMost(AppSpacing.dashboardMaxWidth)
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .width(contentWidth)
                    .align(Alignment.TopCenter)
                    .testTag(DECK_DETAILS_TEST_TAG),
                contentPadding = PaddingValues(
                    start = AppSpacing.contentGutter,
                    end = AppSpacing.contentGutter,
                    top = AppSpacing.XS,
                    bottom = AppSpacing.XXL
                ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.screenSectionGap)
            ) {
                when (val current = state) {
                    DeckDetailsUiState.Loading -> item(key = "details-loading") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(AppSpacing.XXL),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)
                        ) {
                            CircularProgressIndicator(color = AppColors.actionAccent)
                            Text("Loading deck details…", color = AppColors.contentSecondary)
                        }
                    }
                    is DeckDetailsUiState.Unavailable -> item(key = "details-unavailable") {
                        DetailsUnavailablePanel(
                            reason = current.reason,
                            isRetrying = current.isRetrying,
                            onRetry = viewModel::refresh
                        )
                    }
                    is DeckDetailsUiState.Error -> item(key = "details-error") {
                        DetailsErrorPanel(
                            error = current.error,
                            isRetrying = current.isRetrying,
                            onRetry = viewModel::refresh
                        )
                    }
                    is DeckDetailsUiState.Ready -> {
                        item(key = "backend-status") {
                            DetailsBackendStatusCard(
                                availability = current.availability,
                                capabilities = current.capabilities,
                                onRetry = viewModel::refresh
                            )
                        }
                        if (current.readError != null) {
                            item(key = "details-read-error") {
                                InfoBanner(
                                    title = "Could not refresh deck data",
                                    message = current.readError.libraryMessage(),
                                    tone = BannerTone.WARNING,
                                    actionLabel = "Retry",
                                    onAction = viewModel::refresh
                                )
                            }
                        }
                        if (current.isSummaryStale && current.availability is AnkiAvailability.Ready && current.readError == null) {
                            item(key = "stale-summary") {
                                InfoBanner(
                                    title = "Showing last loaded deck data",
                                    message = "This summary may be out of date. Refresh to check the current collection.",
                                    tone = BannerTone.WARNING,
                                    actionLabel = "Refresh",
                                    onAction = viewModel::refresh
                                )
                            }
                        }
                        current.studyStartError?.let { error ->
                            item(key = "study-start-error") {
                                InfoBanner(
                                    title = "Study did not start",
                                    message = error.libraryMessage(),
                                    tone = BannerTone.WARNING,
                                    actionLabel = "Retry",
                                    onAction = viewModel::startStudy
                                )
                            }
                        }
                        item(key = "identity") { DeckIdentityCard(current) }
                        item(key = "summary") { DeckSummaryCard(current) }
                        if (current.activeSessionPreventsStudy) {
                            item(key = "active-study-notice") {
                                InfoBanner(
                                    title = "A study session is already active",
                                    message = "Resume or finish the active session before starting a different deck.",
                                    tone = BannerTone.INFO
                                )
                            }
                        }
                        if (!current.canStudy && !current.activeSessionPreventsStudy &&
                            current.availability is AnkiAvailability.Ready
                        ) {
                            val capabilityText = when {
                                !current.capabilities.review -> "This backend can browse decks but cannot commit review ratings."
                                !current.capabilities.scheduledReview -> "This backend cannot ask Anki's scheduler for the next review card."
                                else -> "The selected backend is not ready to start a review session."
                            }
                            item(key = "study-unavailable") {
                                InfoBanner(
                                    title = "Study is unavailable",
                                    message = capabilityText,
                                    tone = BannerTone.NEUTRAL
                                )
                            }
                        }
                        item(key = "study-action") {
                            PrimaryButton(
                                text = if (current.isStartingStudy) "Starting study…" else "Start study",
                                onClick = viewModel::startStudy,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = current.canStudy,
                                loading = current.isStartingStudy,
                                icon = if (current.isStartingStudy) null else Icons.Default.School,
                                tall = true
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeckIdentityCard(state: DeckDetailsUiState.Ready) {
    val deck = state.summary.deck
    Surface(color = AppColors.surfacePrimary, shape = AppShape.heroCardShape, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AppSpacing.heroCardPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)
        ) {
            Text(
                text = deck.leafName,
                style = MaterialTheme.typography.headlineMedium.copy(textDirection = TextDirection.ContentOrLtr),
                color = AppColors.contentPrimary
            )
            Text("Hierarchy", style = MaterialTheme.typography.labelLarge, color = AppColors.contentMuted)
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.XXS)) {
                deck.path.forEachIndexed { index, segment ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (index * AppSpacing.MD.value).dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (index > 0) {
                            Text("•", color = AppColors.actionAccent, modifier = Modifier.padding(end = AppSpacing.XS))
                        }
                        Text(
                            text = segment,
                            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrLtr),
                            color = if (index == deck.path.lastIndex) AppColors.contentPrimary else AppColors.contentSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (state.summary.isFiltered == true) {
                Surface(color = AppColors.statusInfoFill, shape = AppShape.chipShape) {
                    Text(
                        "Filtered deck",
                        modifier = Modifier.padding(horizontal = AppSpacing.SM, vertical = AppSpacing.XXS),
                        color = AppColors.statusInfo,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun DeckSummaryCard(state: DeckDetailsUiState.Ready) {
    val counts: AnkiDeckCounts? = state.summary.counts
    Surface(color = AppColors.surfacePrimary, shape = AppShape.cardShape, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AppSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)
        ) {
            Text("Deck summary", style = MaterialTheme.typography.titleMedium, color = AppColors.contentPrimary)
            if (state.capabilities.deckCounts) {
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)) {
                    SummaryCount("New", counts?.new, Modifier.weight(1f))
                    SummaryCount("Learning", counts?.learning, Modifier.weight(1f))
                    SummaryCount("Review", counts?.review, Modifier.weight(1f))
                }
                counts?.totalDue?.let { due ->
                    Text(
                        text = if (due == 0) "No cards currently due" else "$due total due",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (due == 0) AppColors.contentMuted else AppColors.actionAccent
                    )
                }
                // GATE 14 — only rendered when the backend actually reports a total card count.
                // `null` means "the backend did not say" and is never shown as zero.
                state.summary.totalCards?.let { total ->
                    Text(
                        text = if (total == 0) "No cards in this deck" else "$total total cards",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.contentSecondary,
                        modifier = Modifier.semantics {
                            contentDescription = "Total cards ${total}"
                        }
                    )
                }
            } else {
                Text("Due counts are not available from this backend.",
                    style = MaterialTheme.typography.bodyMedium, color = AppColors.contentSecondary)
            }
            Text(
                "Counts are read-only Anki data. Anki decides which card is due when a study session starts.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.contentMuted
            )
        }
    }
}

@Composable
private fun SummaryCount(label: String, count: Int?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label count ${count?.toString() ?: "unavailable"}"
        },
        color = AppColors.surfaceElevated,
        shape = AppShape.cardShape
    ) {
        Column(
            modifier = Modifier.padding(vertical = AppSpacing.MD, horizontal = AppSpacing.XS),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = count?.toString() ?: "—",
                style = MaterialTheme.typography.headlineSmall,
                color = AppColors.contentPrimary
            )
            Text(label, style = MaterialTheme.typography.bodySmall, color = AppColors.contentMuted)
        }
    }
}

@Composable
private fun DetailsUnavailablePanel(
    reason: AnkiAvailability,
    isRetrying: Boolean,
    onRetry: () -> Unit
) {
    Surface(color = AppColors.surfacePrimary, shape = AppShape.cardShape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.heroCardPadding), verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)) {
            Text(reason.displayLabel(), style = MaterialTheme.typography.titleLarge, color = AppColors.statusWarning)
            Text(reason.displayMessage(), style = MaterialTheme.typography.bodyMedium, color = AppColors.contentSecondary)
            SecondaryButton(
                text = if (isRetrying) "Checking…" else "Retry",
                onClick = onRetry,
                enabled = !isRetrying,
                icon = if (isRetrying) null else Icons.Default.Refresh
            )
        }
    }
}

@Composable
private fun DetailsErrorPanel(
    error: com.studyagent.client.core.anki.AnkiError,
    isRetrying: Boolean,
    onRetry: () -> Unit
) {
    Surface(color = AppColors.surfacePrimary, shape = AppShape.cardShape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.heroCardPadding), verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)) {
            Text("Deck details unavailable", style = MaterialTheme.typography.titleLarge, color = AppColors.statusDanger)
            Text(error.libraryMessage(), style = MaterialTheme.typography.bodyMedium, color = AppColors.contentSecondary)
            SecondaryButton(
                text = if (isRetrying) "Retrying…" else "Retry",
                onClick = onRetry,
                enabled = !isRetrying,
                icon = if (isRetrying) null else Icons.Default.Refresh
            )
        }
    }
}

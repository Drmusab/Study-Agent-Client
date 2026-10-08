package com.studyagent.client.ui.screens.cardbrowser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.ui.components.StudyAgentTopBar
import com.studyagent.client.ui.components.anki.CardBrowserFilters
import com.studyagent.client.ui.components.anki.CardBrowserRow as CardBrowserRowView
import com.studyagent.client.ui.components.anki.CardBrowserSortMenu
import com.studyagent.client.ui.screens.library.displayMessage
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing

const val CARD_BROWSER_TEST_TAG = "card_browser"
const val CARD_BROWSER_SEARCH_TEST_TAG = "card_browser_search"

@Composable
fun CardBrowserScreen(
    viewModel: CardBrowserViewModel,
    onNavigateBack: () -> Unit,
    onOpenCardDetails: (com.studyagent.client.core.anki.AnkiCardRef) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isLoading = state is CardBrowserUiState.Loading

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is CardBrowserEvent.OpenCardDetails -> onOpenCardDetails(event.cardRef)
            }
        }
    }

    Scaffold(
        containerColor = AppColors.appBackground,
        topBar = {
            StudyAgentTopBar(
                title = "Card browser",
                onBack = onNavigateBack,
                subtitle = if (state.query.deckId == null) "All cards" else "Deck-scoped",
                trailing = {
                    IconButton(
                        onClick = viewModel::refresh,
                        enabled = !isLoading,
                        modifier = Modifier.semantics { contentDescription = "Refresh card results" }
                    ) {
                        if (isLoading) {
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
    ) { insets ->
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(insets)
        ) {
            val contentWidth = maxWidth.coerceAtMost(AppSpacing.dashboardMaxWidth)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .width(contentWidth)
                    .align(Alignment.TopCenter)
                    .padding(horizontal = AppSpacing.contentGutter, vertical = AppSpacing.XS)
                    .testTag(CARD_BROWSER_TEST_TAG),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.XS)
            ) {
                SearchField(
                    value = state.query.searchText,
                    enabled = state.capabilities.textSearch,
                    onValueChange = viewModel::setSearchText,
                    onClear = { viewModel.setSearchText("") }
                )
                CardBrowserFilters(
                    capabilities = state.capabilities,
                    filters = state.query.filters,
                    onFiltersChanged = viewModel::setFilters
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ResultSummary(state)
                    CardBrowserSortMenu(
                        capabilities = state.capabilities,
                        selected = state.query.sort,
                        onSortSelected = viewModel::setSort
                    )
                }
                BrowserBody(
                    state = state,
                    onRetry = viewModel::refresh,
                    onLoadMore = viewModel::loadMore,
                    onOpenCard = viewModel::openCardDetails
                )
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        label = { Text("Search questions and answers") },
        placeholder = { Text("Search cards") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = onClear, modifier = Modifier.semantics {
                    contentDescription = "Clear card search"
                }) {
                    Icon(Icons.Default.Clear, contentDescription = null)
                }
            }
        },
        textStyle = TextStyle(textDirection = TextDirection.ContentOrLtr),
        modifier = Modifier.fillMaxWidth().testTag(CARD_BROWSER_SEARCH_TEST_TAG)
    )
}

@Composable
private fun ResultSummary(state: CardBrowserUiState) {
    val label = when (state) {
        is CardBrowserUiState.Ready -> state.totalCount?.let { "$it cards" }
            ?: "${state.rows.size}${if (state.hasMore) "+ loaded" else " cards"}"
        is CardBrowserUiState.Empty -> if (state.reason == CardBrowserUiState.Empty.Reason.NO_MATCHES) {
            "No matches"
        } else {
            state.totalCount?.let { "$it cards" } ?: "0 cards"
        }
        is CardBrowserUiState.Loading -> "Loading cards…"
        is CardBrowserUiState.Unavailable -> "Browsing unavailable"
        is CardBrowserUiState.Error -> "Could not load cards"
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = AppColors.contentSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.semantics { contentDescription = "Card result count: $label" }
    )
}

@Composable
private fun ColumnScope.BrowserBody(
    state: CardBrowserUiState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenCard: (com.studyagent.client.core.anki.AnkiCardRef) -> Unit
) {
    when (state) {
        is CardBrowserUiState.Loading -> CenterMessage(
            message = "Loading cards…",
            loading = true,
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
        is CardBrowserUiState.Ready -> {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = AppSpacing.XXL),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)
            ) {
                items(state.rows, key = { it.stableKey }) { row ->
                    CardBrowserRowView(row = row, onOpen = { onOpenCard(row.cardRef) })
                }
                state.appendError?.let { error ->
                    item(key = "card-browser-page-error") {
                        MessagePanel(
                            title = "Could not load more cards",
                            message = error.browserMessage(),
                            actionLabel = "Retry",
                            onAction = onLoadMore
                        )
                    }
                }
                if (state.isLoadingMore) {
                    item(key = "card-browser-page-loading") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(AppSpacing.MD),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("Loading more…", modifier = Modifier.padding(start = AppSpacing.SM))
                        }
                    }
                } else if (state.hasMore) {
                    item(key = "card-browser-load-more") {
                        TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                            Text("Load more cards")
                        }
                    }
                }
            }
        }
        is CardBrowserUiState.Empty -> CenterMessage(
            message = if (state.reason == CardBrowserUiState.Empty.Reason.NO_MATCHES) {
                "No matching cards. Try changing the search or filters."
            } else {
                "This deck has no cards to browse."
            },
            loading = false,
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
        is CardBrowserUiState.Unavailable -> {
            val (title, message) = if (state.unsupportedFeature != null && state.availability is AnkiAvailability.Ready) {
                "Card browsing is not supported" to
                    "This Anki backend does not expose a safe, bounded card-list query. Other supported Anki features remain available."
            } else {
                "Anki backend unavailable" to state.availability.displayMessage()
            }
            MessagePanel(
                title = title,
                message = message,
                actionLabel = if (state.availability is AnkiAvailability.Ready) null else "Retry",
                onAction = onRetry,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
        is CardBrowserUiState.Error -> MessagePanel(
            title = "Could not load cards",
            message = state.error.browserMessage(),
            actionLabel = "Retry",
            onAction = onRetry,
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
    }
}

@Composable
private fun CenterMessage(message: String, loading: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(AppSpacing.XXL),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)
    ) {
        if (loading) CircularProgressIndicator(color = AppColors.actionAccent)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = AppColors.contentSecondary)
    }
}

@Composable
private fun MessagePanel(
    title: String,
    message: String,
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = AppColors.surfacePrimary,
        shape = AppShape.cardShape
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = AppColors.contentPrimary)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = AppColors.contentSecondary)
            actionLabel?.let { label ->
                TextButton(onClick = onAction) { Text(label, color = AppColors.actionAccent) }
            }
        }
    }
}

private fun AnkiError.browserMessage(): String = when (this) {
    is AnkiError.PermissionRequired -> "Grant Anki access, then retry."
    is AnkiError.ProviderUnavailable -> "The Anki integration provider is not reachable. Open Anki and retry."
    is AnkiError.CollectionUnavailable -> "The Anki collection is not available right now."
    is AnkiError.BackendUnavailable -> "The selected Anki backend is not available right now."
    is AnkiError.UnsupportedAction -> "This backend does not support the requested browse feature."
    is AnkiError.QueryFailure -> "Anki could not answer this card query. Please retry."
    is AnkiError.MalformedResponse -> "The backend returned card data the app could not interpret."
    else -> "The card request could not be completed. Please retry."
}

package com.studyagent.client.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.mergeDescendants
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.isReadyForReview
import com.studyagent.client.ui.components.BannerTone
import com.studyagent.client.ui.components.InfoBanner
import com.studyagent.client.ui.components.SkeletonCard
import com.studyagent.client.ui.components.SecondaryButton
import com.studyagent.client.ui.components.anki.DeckRow
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing

const val LIBRARY_LIST_TEST_TAG = "library_deck_list"
const val LIBRARY_SEARCH_TEST_TAG = "library_search"

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenDeck: (deckId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var query by rememberSaveable { mutableStateOf("") }
    var expandedKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var expansionInitialized by rememberSaveable { mutableStateOf(false) }
    val isRefreshing = when (val current = state) {
        is LibraryUiState.Ready -> current.isRefreshing
        is LibraryUiState.Empty -> current.isRefreshing
        is LibraryUiState.Unavailable -> current.isRetrying
        is LibraryUiState.Error -> current.isRetrying
        LibraryUiState.Loading -> true
    }

    LaunchedEffect(Unit) { viewModel.onScreenActive() }
    LaunchedEffect(query) { viewModel.setSearchQuery(query) }
    val ready = state as? LibraryUiState.Ready
    val visibleRows = remember(ready?.tree, expandedKeys, query) {
        ready?.let {
            DeckTreeProjection.flattenVisible(
                roots = it.tree,
                expandedKeys = expandedKeys.toSet(),
                forceExpanded = query.isNotBlank()
            )
        }.orEmpty()
    }
    LaunchedEffect(ready?.backend) {
        if (!expansionInitialized && ready != null) {
            expandedKeys = DeckTreeProjection.defaultExpandedKeys(ready.tree)
            expansionInitialized = true
        }
    }

    Scaffold(
        containerColor = AppColors.appBackground,
        topBar = {
            LibraryTopBar(
                isRefreshing = isRefreshing,
                onRefresh = viewModel::refresh
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
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .width(contentWidth)
                    .align(Alignment.TopCenter)
                    .testTag(LIBRARY_LIST_TEST_TAG),
                contentPadding = PaddingValues(
                    start = AppSpacing.contentGutter,
                    end = AppSpacing.contentGutter,
                    top = AppSpacing.XS,
                    bottom = AppSpacing.XXL
                ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.screenSectionGap)
            ) {
                item(key = "library-intro") {
                    Column {
                        Text(
                            text = "Your Anki decks",
                            style = MaterialTheme.typography.headlineMedium,
                            color = AppColors.contentPrimary
                        )
                        Spacer(Modifier.height(AppSpacing.XXS))
                        Text(
                            text = "Browse your collection and choose a deck to study.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.contentSecondary
                        )
                    }
                }

                when (val current = state) {
                    LibraryUiState.Loading -> item(key = "loading") {
                        SkeletonCard(lines = 2)
                    }
                    is LibraryUiState.Unavailable -> item(key = "unavailable") {
                        BackendUnavailablePanel(
                            reason = current.reason,
                            isRetrying = current.isRetrying,
                            onRetry = viewModel::refresh
                        )
                    }
                    is LibraryUiState.Error -> item(key = "error") {
                        LibraryErrorPanel(
                            error = current.error,
                            isRetrying = current.isRetrying,
                            onRetry = viewModel::refresh
                        )
                    }
                    is LibraryUiState.Empty -> {
                        item(key = "backend-status") {
                            BackendStatusCard(
                                availability = current.availability,
                                capabilities = current.capabilities,
                                deckCount = 0
                            )
                        }
                        current.staleError?.let { error ->
                            item(key = "empty-library-stale-warning") {
                                InfoBanner(
                                    title = "Showing the last successful library result",
                                    message = error.libraryMessage(),
                                    tone = BannerTone.WARNING,
                                    actionLabel = "Retry",
                                    onAction = viewModel::refresh
                                )
                            }
                        }
                        item(key = "empty-library") { EmptyLibraryPanel(onRefresh = viewModel::refresh) }
                    }
                    is LibraryUiState.Ready -> {
                        item(key = "backend-status") {
                            BackendStatusCard(
                                availability = current.availability,
                                capabilities = current.capabilities,
                                deckCount = current.decks.size
                            )
                        }
                        item(key = "deck-controls") {
                            DeckSearchField(
                                value = query,
                                onValueChange = {
                                    query = it
                                    viewModel.setSearchQuery(it)
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (current.isRefreshing) {
                            item(key = "refresh-progress") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = AppColors.actionAccent
                                    )
                                    Spacer(Modifier.width(AppSpacing.XS))
                                    Text("Refreshing decks", color = AppColors.contentSecondary,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        current.staleError?.let { error ->
                            item(key = "stale-warning") {
                                InfoBanner(
                                    title = "Showing saved deck data",
                                    message = error.libraryMessage(),
                                    tone = BannerTone.WARNING,
                                    actionLabel = "Retry",
                                    onAction = viewModel::refresh
                                )
                            }
                        }
                        if (visibleRows.isEmpty()) {
                            item(key = "no-search-results") {
                                NoMatchingDecks(query = query, onClear = {
                                    query = ""
                                    viewModel.setSearchQuery("")
                                })
                            }
                        } else {
                            items(
                                items = visibleRows,
                                key = { it.key },
                                contentType = { if (it is DeckTreeItem.Deck) "deck" else "group" }
                            ) { item ->
                                when (item) {
                                    is DeckTreeItem.Deck -> DeckRow(
                                        item = item,
                                        showCounts = current.capabilities.deckCounts,
                                        expanded = item.key in expandedKeys,
                                        onToggleExpanded = {
                                            expandedKeys = if (item.key in expandedKeys) {
                                                expandedKeys - item.key
                                            } else {
                                                expandedKeys + item.key
                                            }
                                        },
                                        onOpen = { onOpenDeck(item.item.deck.ref.deckId) }
                                    )
                                    is DeckTreeItem.Group -> DeckGroupRow(
                                        item = item,
                                        expanded = item.key in expandedKeys,
                                        onToggle = {
                                            expandedKeys = if (item.key in expandedKeys) {
                                                expandedKeys - item.key
                                            } else {
                                                expandedKeys + item.key
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryTopBar(isRefreshing: Boolean, onRefresh: () -> Unit) {
    Surface(color = AppColors.appBackground) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.contentGutter, vertical = AppSpacing.XS),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.LibraryBooks,
                contentDescription = null,
                tint = AppColors.actionAccent,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.width(AppSpacing.SM))
            Column(modifier = Modifier.weight(1f)) {
                Text("Anki Library", style = MaterialTheme.typography.titleLarge, color = AppColors.contentPrimary)
                Text("Decks and study", style = MaterialTheme.typography.bodySmall, color = AppColors.contentMuted)
            }
            IconButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
                modifier = Modifier.semantics { contentDescription = "Refresh deck library" }
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
    }
}

@Composable
private fun BackendStatusCard(
    availability: AnkiAvailability.Ready,
    capabilities: com.studyagent.client.core.anki.AnkiCapabilities,
    deckCount: Int
) {
    Surface(
        color = AppColors.surfacePrimary,
        shape = AppShape.cardShape,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(AppSpacing.cardPadding), verticalArrangement = Arrangement.spacedBy(AppSpacing.XS)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = AppColors.statusSuccessFill, shape = AppShape.chipShape) {
                    Text(
                        text = "READY",
                        modifier = Modifier.padding(horizontal = AppSpacing.SM, vertical = AppSpacing.XXS),
                        style = MaterialTheme.typography.labelMedium,
                        color = AppColors.statusSuccess
                    )
                }
                Spacer(Modifier.width(AppSpacing.SM))
                Text("Selected Anki backend", style = MaterialTheme.typography.titleSmall, color = AppColors.contentPrimary)
            }
            Text(
                text = "$deckCount ${if (deckCount == 1) "deck" else "decks"} available",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.contentSecondary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)) {
                CapabilityLabel("Deck browsing", capabilities.deckListing)
                CapabilityLabel("Study", availability.isReadyForReview && capabilities.scheduledReview)
            }
        }
    }
}

@Composable
private fun CapabilityLabel(label: String, supported: Boolean) {
    val color = if (supported) AppColors.statusSuccess else AppColors.contentMuted
    Surface(color = AppColors.surfaceElevated, shape = AppShape.chipShape) {
        Text(
            text = "$label ${if (supported) "available" else "unavailable"}",
            modifier = Modifier
                .padding(horizontal = AppSpacing.SM, vertical = AppSpacing.XXS)
                .semantics { contentDescription = "$label ${if (supported) "available" else "unavailable"}" },
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun DeckSearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.testTag(LIBRARY_SEARCH_TEST_TAG),
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.semantics { contentDescription = "Clear deck search" }
                ) { Icon(Icons.Default.Close, contentDescription = null) }
            }
        } else null,
        placeholder = { Text("Search deck names") },
        shape = RoundedCornerShape(AppShape.field),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = AppColors.surfacePrimary,
            unfocusedContainerColor = AppColors.surfacePrimary,
            focusedTextColor = AppColors.contentPrimary,
            unfocusedTextColor = AppColors.contentPrimary,
            focusedIndicatorColor = AppColors.actionAccent,
            unfocusedIndicatorColor = AppColors.divider,
            cursorColor = AppColors.actionAccent
        )
    )
}

@Composable
private fun DeckGroupRow(item: DeckTreeItem.Group, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (AppSpacing.MD * item.depth.coerceAtMost(6).toFloat()), end = AppSpacing.XS)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) {
                contentDescription = "${if (expanded) "Collapse" else "Expand"} deck group ${item.fullName}"
                role = Role.Button
            }
            .padding(vertical = AppSpacing.XS, horizontal = AppSpacing.SM),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = AppColors.actionAccent
        )
        Spacer(Modifier.width(AppSpacing.XS))
        Text(
            text = item.name,
            style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.ContentOrLtr),
            color = AppColors.actionAccent,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${item.children.size}",
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.contentMuted
        )
    }
}

@Composable
private fun EmptyLibraryPanel(onRefresh: () -> Unit) {
    Surface(color = AppColors.surfacePrimary, shape = AppShape.cardShape, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AppSpacing.heroCardPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.SM),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.LibraryBooks, contentDescription = null, tint = AppColors.contentMuted, modifier = Modifier.size(36.dp))
            Text("Your library is empty", style = MaterialTheme.typography.titleLarge, color = AppColors.contentPrimary)
            Text(
                "The selected Anki collection has no decks yet. Add a deck in Anki, then refresh this library.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.contentSecondary
            )
            SecondaryButton("Refresh", onRefresh, icon = Icons.Default.Refresh)
        }
    }
}

@Composable
private fun BackendUnavailablePanel(reason: AnkiAvailability, isRetrying: Boolean, onRetry: () -> Unit) {
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
private fun LibraryErrorPanel(error: com.studyagent.client.core.anki.AnkiError, isRetrying: Boolean, onRetry: () -> Unit) {
    Surface(color = AppColors.surfacePrimary, shape = AppShape.cardShape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.heroCardPadding), verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)) {
            Text("Could not load decks", style = MaterialTheme.typography.titleLarge, color = AppColors.statusDanger)
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

@Composable
private fun NoMatchingDecks(query: String, onClear: () -> Unit) {
    Surface(color = AppColors.surfacePrimary, shape = AppShape.cardShape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.cardPadding), verticalArrangement = Arrangement.spacedBy(AppSpacing.XS)) {
            Text("No matching decks", style = MaterialTheme.typography.titleMedium, color = AppColors.contentPrimary)
            Text("Nothing matches “$query”. Search checks deck names and hierarchy paths.",
                style = MaterialTheme.typography.bodyMedium, color = AppColors.contentSecondary)
            SecondaryButton("Clear search", onClear)
        }
    }
}

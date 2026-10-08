package com.studyagent.client.ui.screens.carddetails

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyagent.client.core.anki.AnkiCardSide
import com.studyagent.client.core.anki.ReviewTurnId
import com.studyagent.client.core.render.AnkiCardRenderConfig
import com.studyagent.client.core.render.AnkiRenderSurfaceKind
import com.studyagent.client.ui.components.StudyAgentTopBar
import com.studyagent.client.ui.components.anki.AnkiCardRenderer
import com.studyagent.client.ui.components.anki.CardMetadataSection
import com.studyagent.client.ui.components.anki.CardSchedulingSection
import com.studyagent.client.ui.components.anki.NoteFieldsSection
import com.studyagent.client.ui.components.anki.SafeExpandableText
import com.studyagent.client.ui.screens.carddetails.CardDetailsUiState
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppSpacing

const val CARD_DETAILS_TEST_TAG = "card_details"

/** Defensive route-failure surface; it never substitutes a different backend/card. */
@Composable
fun CardDetailsRouteErrorScreen(
    message: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        containerColor = AppColors.appBackground,
        topBar = { StudyAgentTopBar(title = "Card details", onBack = onNavigateBack) },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(AppSpacing.contentGutter),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)
        ) {
            Text("This card reference cannot be opened", style = MaterialTheme.typography.titleMedium,
                color = AppColors.contentPrimary)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = AppColors.contentSecondary)
        }
    }
}

/** Strictly read-only Card Details screen; the only operation is a fresh read. */
@Composable
fun CardDetailsScreen(
    viewModel: CardDetailsViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ready = (state as? CardDetailsUiState.Ready)?.details
    Scaffold(
        containerColor = AppColors.appBackground,
        topBar = {
            StudyAgentTopBar(
                title = "Card details",
                onBack = onNavigateBack,
                subtitle = ready?.deckName,
                trailing = {
                    IconButton(
                        onClick = viewModel::refresh,
                        enabled = state !is CardDetailsUiState.Loading,
                        modifier = Modifier.semantics { contentDescription = "Refresh card details" }
                    ) {
                        if (state is CardDetailsUiState.Loading) {
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
    ) { padding ->
        when (state) {
            CardDetailsUiState.Loading -> LoadingContent(Modifier.padding(padding))
            is CardDetailsUiState.Unavailable -> UnavailableContent(
                state = state as CardDetailsUiState.Unavailable,
                onRetry = viewModel::refresh,
                modifier = Modifier.padding(padding)
            )
            is CardDetailsUiState.Error -> ErrorContent(
                state = state as CardDetailsUiState.Error,
                onRetry = viewModel::refresh,
                modifier = Modifier.padding(padding)
            )
            is CardDetailsUiState.Ready -> ReadyContent(
                presentation = (state as CardDetailsUiState.Ready).details,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AppColors.actionAccent)
    }
}

@Composable
private fun UnavailableContent(
    state: CardDetailsUiState.Unavailable,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(AppSpacing.contentGutter),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.SM),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Card details are unavailable", style = MaterialTheme.typography.titleMedium, color = AppColors.contentPrimary)
        Text(
            text = availabilityLabel(state.reason),
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.contentSecondary
        )
        androidx.compose.material3.TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun ErrorContent(
    state: CardDetailsUiState.Error,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(AppSpacing.contentGutter),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.SM),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Could not load card details", style = MaterialTheme.typography.titleMedium, color = AppColors.contentPrimary)
        Text(state.error.message, style = MaterialTheme.typography.bodyMedium, color = AppColors.contentSecondary)
        androidx.compose.material3.TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun ReadyContent(
    presentation: CardDetailsPresentation,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
        val contentWidth = maxWidth.coerceAtMost(AppSpacing.dashboardMaxWidth)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .width(contentWidth)
                .align(Alignment.TopCenter)
                .testTag(CARD_DETAILS_TEST_TAG)
                .semantics { contentDescription = "Read-only card details" },
            contentPadding = PaddingValues(
                start = AppSpacing.contentGutter,
                end = AppSpacing.contentGutter,
                top = AppSpacing.XS,
                bottom = AppSpacing.XXL
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.screenSectionGap)
        ) {
            item(key = "details-header") {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.XXS)) {
                    Text(
                        text = presentation.title,
                        style = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Content),
                        color = AppColors.contentPrimary
                    )
                    presentation.noteId?.let { noteId ->
                        Text(
                            text = "Note ID $noteId · Card ordinal ${presentation.cardOrd?.toString() ?: "not available"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.contentSecondary
                        )
                    }
                }
            }
            item(key = "overview") {
                CardMetadataSection(title = "Overview", rows = presentation.overviewRows)
            }
            item(key = "original-card") {
                OriginalCardSection(presentation)
            }
            item(key = "readable-text") {
                ReadableTextSection(presentation)
            }
            item(key = "note-fields") {
                NoteFieldsSection(fields = presentation.fields)
            }
            item(key = "tags") {
                TagsSection(tags = presentation.tags)
            }
            item(key = "scheduling") {
                CardSchedulingSection(rows = presentation.schedulingRows)
            }
            item(key = "media") {
                MediaSection(mediaFiles = presentation.mediaFiles)
            }
            item(key = "technical") {
                CardMetadataSection(title = "Technical metadata", rows = presentation.technicalRows)
            }
        }
    }
}

@Composable
private fun OriginalCardSection(presentation: CardDetailsPresentation) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = com.studyagent.client.ui.theme.AppShape.cardShape,
        color = AppColors.surfacePrimary
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)
        ) {
            Text("Original card", style = MaterialTheme.typography.titleMedium, color = AppColors.contentPrimary)
            val card = presentation.originalCard
            if (card == null) {
                Text("Original rendered content is not available.", color = AppColors.contentSecondary)
            } else {
                var selectedSide by remember(presentation.cardRef) { mutableIntStateOf(0) }
                TabRow(selectedTabIndex = selectedSide) {
                    Tab(
                        selected = selectedSide == 0,
                        onClick = { selectedSide = 0 },
                        text = { Text("Question") },
                        modifier = Modifier.semantics { contentDescription = "Show original question" }
                    )
                    Tab(
                        selected = selectedSide == 1,
                        onClick = { selectedSide = 1 },
                        text = { Text("Answer") },
                        modifier = Modifier.semantics { contentDescription = "Show original answer" }
                    )
                }
                // The existing renderer is turn-scoped. This namespaced token identifies only this
                // browsing render surface; it is not an active StudySession turn and is never sent
                // to a backend, reviewer action or scheduler (INV-16-18/19).
                val rendererToken = remember(presentation.cardRef) {
                    ReviewTurnId("card-details:${presentation.cardRef.stableKey}")
                }
                val side = if (selectedSide == 0) AnkiCardSide.QUESTION else AnkiCardSide.ANSWER
                val sideHasContent = if (side == AnkiCardSide.QUESTION) {
                    card.questionHtml != null || card.questionText != null
                } else {
                    card.answerHtml != null || card.answerText != null
                }
                val sideHasOriginalHtml = if (side == AnkiCardSide.QUESTION) {
                    card.questionHtml != null
                } else {
                    card.answerHtml != null
                }
                if (!sideHasContent) {
                    Text(
                        text = if (side == AnkiCardSide.QUESTION) "Question content is unavailable." else "Answer content is unavailable.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.contentSecondary
                    )
                } else {
                    if (!sideHasOriginalHtml) {
                        Text(
                            text = "Rendered HTML is unavailable; showing the backend's safe text fallback.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.contentSecondary
                        )
                    }
                    AnkiCardRenderer(
                        card = card,
                        turnId = rendererToken,
                        side = side,
                        config = AnkiCardRenderConfig.DARK_APP,
                        surfaceKind = AnkiRenderSurfaceKind.BROWSING,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 480.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadableTextSection(presentation: CardDetailsPresentation) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = com.studyagent.client.ui.theme.AppShape.cardShape,
        color = AppColors.surfacePrimary
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)
        ) {
            Text("Normalized text", style = MaterialTheme.typography.titleMedium, color = AppColors.contentPrimary)
            val hasText = presentation.questionText != null || presentation.answerText != null || presentation.pureAnswerText != null
            if (!hasText) {
                Text("Normalized question and answer text are not available.", color = AppColors.contentSecondary)
            } else {
                presentation.questionText?.let {
                    SafeExpandableText("Question text", it, "question:${presentation.cardRef.stableKey}")
                }
                presentation.answerText?.let {
                    SafeExpandableText("Answer text", it, "answer:${presentation.cardRef.stableKey}")
                }
                presentation.pureAnswerText?.let {
                    SafeExpandableText("Pure answer text", it, "pure-answer:${presentation.cardRef.stableKey}")
                }
            }
        }
    }
}

@Composable
private fun TagsSection(tags: List<String>?) {
    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = when {
                    tags == null -> "Tags unavailable"
                    tags.isEmpty() -> "No tags"
                    else -> "Tags: ${tags.joinToString(", ")}"
                }
            },
        shape = com.studyagent.client.ui.theme.AppShape.cardShape,
        color = AppColors.surfacePrimary
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)
        ) {
            Text("Tags", style = MaterialTheme.typography.titleMedium, color = AppColors.contentPrimary)
            when {
                tags == null -> Text("Tags are not available from this backend.", color = AppColors.contentSecondary)
                tags.isEmpty() -> Text("No tags.", color = AppColors.contentSecondary)
                else -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)
                ) {
                    tags.forEach { tag ->
                        Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                            color = AppColors.surfaceElevated,
                            modifier = Modifier.semantics { contentDescription = "Tag: $tag" }
                        ) {
                            Text(
                                text = tag,
                                modifier = Modifier.padding(horizontal = AppSpacing.SM, vertical = AppSpacing.XS),
                                style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Content),
                                color = AppColors.contentPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaSection(mediaFiles: List<String>) {
    CardMetadataSection(
        title = "Media references",
        rows = mediaFiles.mapIndexed { index, name -> MetadataRow("Media ${index + 1}", name) },
        emptyMessage = "No media references were found in this card's content."
    )
}

private fun availabilityLabel(availability: com.studyagent.client.core.anki.AnkiAvailability): String = when (availability) {
    com.studyagent.client.core.anki.AnkiAvailability.Checking -> "Checking the selected Anki backend."
    com.studyagent.client.core.anki.AnkiAvailability.NotInstalled -> "AnkiDroid is not installed."
    is com.studyagent.client.core.anki.AnkiAvailability.ProviderUnavailable -> "The AnkiDroid provider is unavailable."
    is com.studyagent.client.core.anki.AnkiAvailability.PermissionRequired -> "Anki access permission is required."
    com.studyagent.client.core.anki.AnkiAvailability.CollectionNotInitialized -> "The Anki collection is not available."
    is com.studyagent.client.core.anki.AnkiAvailability.Ready -> "This backend cannot currently provide card details."
    is com.studyagent.client.core.anki.AnkiAvailability.TemporarilyUnavailable -> "The Anki collection is temporarily unavailable."
    com.studyagent.client.core.anki.AnkiAvailability.AgentDisconnected -> "The PC study agent is disconnected."
    com.studyagent.client.core.anki.AnkiAvailability.AgentAnkiUnavailable -> "Anki is unavailable on the PC."
    is com.studyagent.client.core.anki.AnkiAvailability.Unsupported -> "This backend is not supported."
    is com.studyagent.client.core.anki.AnkiAvailability.Fault -> availability.error.message
}

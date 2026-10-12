package com.studyagent.client.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.studyagent.client.data.anki.context.AnkiReadContextFailure
import com.studyagent.client.data.anki.context.AnkiReadContextFailureKind
import com.studyagent.client.ui.components.StudyAgentTopBar
import com.studyagent.client.ui.theme.AppColors

@Composable
internal fun ReadContextBindingPlaceholder(
    title: String,
    message: String?,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        containerColor = AppColors.appBackground,
        topBar = { StudyAgentTopBar(title = title, onBack = onNavigateBack) }
    ) { insets ->
        Box(
            modifier = Modifier.fillMaxSize().padding(insets).padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            if (message == null) {
                CircularProgressIndicator(color = AppColors.actionAccent)
            } else {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppColors.contentSecondary
                )
            }
        }
    }
}

internal fun AnkiReadContextFailure.safeMessage(): String = when (kind) {
    AnkiReadContextFailureKind.RESOLUTION_FAILED,
    AnkiReadContextFailureKind.BACKEND_NOT_REGISTERED,
    AnkiReadContextFailureKind.AMBIGUOUS_BACKEND,
    AnkiReadContextFailureKind.INVALID_CONFIGURATION ->
        "No unambiguous configured Anki backend can serve this new read. Check backend settings and try again."
    AnkiReadContextFailureKind.BACKEND_UNAVAILABLE,
    AnkiReadContextFailureKind.BACKEND_CHECK_FAILED ->
        "The backend that owns this read is unavailable. The app did not fall back to another backend."
    AnkiReadContextFailureKind.CAPABILITY_UNSUPPORTED ->
        "The selected backend does not support this read."
    AnkiReadContextFailureKind.COLLECTION_IDENTITY_MISMATCH,
    AnkiReadContextFailureKind.COLLECTION_IDENTITY_UNVERIFIABLE,
    AnkiReadContextFailureKind.COLLECTION_IDENTITY_UNAVAILABLE ->
        "The collection identity could not be confirmed. This read has been stopped to protect the data."
    AnkiReadContextFailureKind.PREFERENCE_CHANGED_DURING_BINDING ->
        "The backend preference changed while this read was opening. Retry to bind a new context."
    AnkiReadContextFailureKind.INVALID_REFERENCE ->
        "This backend-qualified reference does not belong to the current read context."
}

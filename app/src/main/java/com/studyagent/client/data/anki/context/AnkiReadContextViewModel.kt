package com.studyagent.client.data.anki.context

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiReadContext
import com.studyagent.client.core.anki.AnkiRequiredCapability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Binding intent for one route. Existing resources never use [New]. */
sealed interface AnkiReadContextRequest {
    data class New(
        val requiredCapabilities: Set<AnkiRequiredCapability>,
        val purpose: String
    ) : AnkiReadContextRequest

    data class Reference(
        val backendId: AnkiBackendId,
        val collectionKey: String?,
        val purpose: String,
        val requiredCapabilities: Set<AnkiRequiredCapability> = emptySet()
    ) : AnkiReadContextRequest

    data class Continue(
        val sourceContext: AnkiReadContext,
        val resourceBackendId: AnkiBackendId,
        val resourceCollectionKey: String?,
        val purpose: String,
        val requiredCapabilities: Set<AnkiRequiredCapability> = emptySet()
    ) : AnkiReadContextRequest
}

sealed interface AnkiReadContextUiState {
    data object Binding : AnkiReadContextUiState
    data class Bound(val binding: AnkiReadContextBinding) : AnkiReadContextUiState
    data class Failed(val failure: AnkiReadContextFailure) : AnkiReadContextUiState
}

/** Route-scoped owner: it binds once, keeps that identity stable, and releases only its lease. */
class AnkiReadContextViewModel(
    private val controller: AnkiReadContextController,
    private val request: AnkiReadContextRequest
) : ViewModel() {
    private val disposed = AtomicBoolean(false)
    private var ownedBinding: AnkiReadContextBinding? = null

    private val _uiState = MutableStateFlow<AnkiReadContextUiState>(AnkiReadContextUiState.Binding)
    val uiState: StateFlow<AnkiReadContextUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val result = try {
                when (val target = request) {
                    is AnkiReadContextRequest.New -> controller.bindNewContext(
                        requiredCapabilities = target.requiredCapabilities,
                        purpose = target.purpose
                    )
                    is AnkiReadContextRequest.Reference -> controller.bindReferenceContext(
                        backendId = target.backendId,
                        collectionKey = target.collectionKey,
                        purpose = target.purpose,
                        requiredCapabilities = target.requiredCapabilities
                    )
                    is AnkiReadContextRequest.Continue -> controller.continueContext(
                        sourceContext = target.sourceContext,
                        resourceBackendId = target.resourceBackendId,
                        resourceCollectionKey = target.resourceCollectionKey,
                        purpose = target.purpose,
                        requiredCapabilities = target.requiredCapabilities
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            }
            when (result) {
                is AnkiReadContextBindingResult.Bound -> {
                    val binding = result.binding
                    if (disposed.get()) {
                        controller.release(binding)
                    } else {
                        ownedBinding = binding
                        _uiState.value = AnkiReadContextUiState.Bound(binding)
                    }
                }
                is AnkiReadContextBindingResult.Failed -> {
                    if (!disposed.get()) _uiState.value = AnkiReadContextUiState.Failed(result.failure)
                }
            }
        }
    }

    override fun onCleared() {
        disposed.set(true)
        ownedBinding?.let(controller::release)
        ownedBinding = null
        super.onCleared()
    }
}

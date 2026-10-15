package com.studyagent.client.data.preferences

import com.studyagent.client.core.anki.AnkiBackendMode
import com.studyagent.client.core.anki.AnkiBackendPreferenceStore
import kotlinx.coroutines.flow.StateFlow

/**
 * Backward-compatible mode-only facade over the authoritative [BackendPreferenceRepository].
 *
 * The old application settings UI still writes an [AnkiBackendMode], but there is only one
 * preference state and one write owner. This adapter deliberately does not keep a second optimistic
 * value that could diverge when an instance pin is written through the full repository.
 */
class DataStoreAnkiBackendPreferenceStore(
    private val repository: BackendPreferenceRepository
) : AnkiBackendPreferenceStore {
    override val mode: StateFlow<AnkiBackendMode> = repository.mode
    override val lastWriteError: StateFlow<String?> = repository.lastWriteError

    override suspend fun setMode(mode: AnkiBackendMode): Boolean = repository.setMode(mode)
}

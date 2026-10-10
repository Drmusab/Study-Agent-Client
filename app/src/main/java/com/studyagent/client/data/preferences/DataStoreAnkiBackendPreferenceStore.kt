package com.studyagent.client.data.preferences

import com.studyagent.client.core.anki.AnkiBackendMode
import com.studyagent.client.core.anki.AnkiBackendPreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * GATE 21 — DataStore-backed [AnkiBackendPreferenceStore] (IMPLEMENTATION-21-05).
 *
 * Persistence goes through the app's existing settings mechanism ([PreferencesDataStore]), so the
 * preferred backend survives a process restart with the same lifecycle as every other setting
 * (DISCOVERY-21-23). It is a *preference* only: nothing reads it to route an already-bound context
 * (DECISION-21-32, INV-21-08).
 *
 * The state flow is seeded from storage and then mirrors writes, so a screen can render the user's
 * choice immediately without waiting for a DataStore read.
 */
class DataStoreAnkiBackendPreferenceStore(
    private val preferencesDataStore: PreferencesDataStore,
    scope: CoroutineScope
) : AnkiBackendPreferenceStore {

    private val persisted: StateFlow<AnkiBackendMode> = preferencesDataStore.ankiBackendModeFlow
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = AnkiBackendMode.AUTO
        )

    /** The last value this instance wrote, so the UI reflects the choice before storage confirms. */
    private val pending = MutableStateFlow<AnkiBackendMode?>(null)

    private val _lastWriteError = MutableStateFlow<String?>(null)
    override val lastWriteError: StateFlow<String?> = _lastWriteError.asStateFlow()

    override val mode: StateFlow<AnkiBackendMode> =
        // Optimistic: the value this instance just wrote wins until storage echoes it back, so the
        // selector never flickers back to the previous choice. A failed write leaves `pending` null,
        // so storage stays authoritative and the UI cannot claim a switch that did not happen.
        combine(pending, persisted) { written, stored -> written ?: stored }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = persisted.value
            )

    override suspend fun setMode(mode: AnkiBackendMode): Boolean {
        val saved = preferencesDataStore.setAnkiBackendMode(mode)
        if (saved) {
            pending.value = mode
            _lastWriteError.value = null
        } else {
            // The stored value is still authoritative; do not pretend the switch happened.
            _lastWriteError.value = "backend_preference_not_saved"
        }
        return saved
    }
}

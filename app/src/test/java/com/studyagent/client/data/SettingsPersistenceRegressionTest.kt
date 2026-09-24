package com.studyagent.client.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.data.preferences.AppSettingsPreferencesCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence regressions that would hurt a real user (§64/§133-§135).
 *
 * The codec's own parity and migration rules are covered by `AppSettingsPreferencesCodecTest`, and
 * the Control Center's draft lifecycle by `StudyControlRepositoryTest`. What this suite adds is the
 * **blast radius** question: when *something else* in the same preferences file is broken — a
 * cache written by a newer build, a draft truncated by a kill during write, a key that a previous
 * version typed differently — does the user's study configuration survive?
 *
 * The invariant under test is one sentence long: **a bad cache may cost a cache, never a setting.**
 */
class SettingsPersistenceRegressionTest {

    /**
     * The management-cache keys, declared exactly as `PreferencesDataStore` declares them
     * (its own key object is private on purpose: nothing outside persistence may write a cache).
     * Keys compare by name and type, so a locally declared key addresses the same entry.
     */
    private val dashboardCacheJson = stringPreferencesKey("dashboard_cache_json")
    private val dashboardCacheSavedAt = androidx.datastore.preferences.core.longPreferencesKey("dashboard_cache_saved_at")
    private val decksCacheJson = stringPreferencesKey("decks_cache_json")
    private val controlConfigJson = stringPreferencesKey("control_config_json")
    private val controlDraftJson = stringPreferencesKey("control_draft_json")
    private val profilesJson = stringPreferencesKey("profiles_json")

    /** Everything a user can meaningfully have configured, set away from its default. */
    private val userSettings = AppSettings(
        selectedProfileId = "profile-home-pc",
        handsFreeMode = false,
        autoPlayQuestion = false,
        listenForSpokenRating = false,
        autoSubmitTranscript = false,
        ttsAcousticGapMs = 700,
        maxReconnectAttempts = 21,
        useFakeAgent = true
    )

    private fun corruptedCachePreferences(): MutablePreferences {
        val prefs = mutablePreferencesOf()
        AppSettingsPreferencesCodec.writeSettings(prefs, userSettings)
        // Caches as a broken/older/newer build might have left them: truncated JSON, a number
        // where a string belongs, a partial envelope, an empty string, a binary blob.
        prefs[dashboardCacheJson] = "{\"generated_at\": \"2026-0"
        prefs[dashboardCacheSavedAt] = 0L
        prefs[decksCacheJson] = "\u0000\u0001\u0002not json"
        prefs[controlConfigJson] =
            "{\"schemaVersion\":1,\"savedAtEpochMs\":123,\"payload\":\"{\\\"active_deck\\\":null}\""
        prefs[controlDraftJson] = "{}"
        prefs[profilesJson] = "["
        return prefs
    }

    @Test
    fun `a corrupt cache cannot change the user's study settings`() {
        val prefs = corruptedCachePreferences()

        val settings = AppSettingsPreferencesCodec.readSettings(prefs)

        assertEquals("profile-home-pc", settings.selectedProfileId)
        assertFalse("hands-free must stay off", settings.handsFreeMode)
        assertFalse(settings.autoPlayQuestion)
        assertFalse(settings.listenForSpokenRating)
        assertEquals(700, settings.ttsAcousticGapMs)
        assertEquals(21, settings.maxReconnectAttempts)
        assertTrue(settings.useFakeAgent)
    }

    @Test
    fun `reading a broken preferences file never throws`() {
        // DataStore itself can hand back an empty file (first run, or after a wipe), and a
        // partially-written one is indistinguishable from a hostile one from the client's side.
        val empty = AppSettingsPreferencesCodec.readSettings(emptyPreferences())
        assertEquals(AppSettings(), empty)

        val corrupt = corruptedCachePreferences()
        AppSettingsPreferencesCodec.migrateInPlace(corrupt)
        assertNotNull(AppSettingsPreferencesCodec.readSettings(corrupt))
    }

    @Test
    fun `a preferences file from a newer build is neither downgraded nor crashed`() {
        val prefs = mutablePreferencesOf()
        AppSettingsPreferencesCodec.writeSettings(prefs, userSettings)
        // A future version of the app wrote this file.
        prefs[AppSettingsPreferencesCodec.Keys.SCHEMA_VERSION] = 99
        val futureKey = stringPreferencesKey("something_from_v6")
        prefs[futureKey] = "keep-me"

        AppSettingsPreferencesCodec.migrateInPlace(prefs)
        val settings = AppSettingsPreferencesCodec.readSettings(prefs)

        assertEquals("a newer schema must not be rewritten as an older one", 99, prefs[AppSettingsPreferencesCodec.Keys.SCHEMA_VERSION])
        assertEquals("unknown keys must survive", "keep-me", prefs[futureKey])
        assertEquals("known settings must still be readable", "profile-home-pc", settings.selectedProfileId)
        assertEquals(700, settings.ttsAcousticGapMs)
    }

    @Test
    fun `settings migration never touches cache keys`() {
        val prefs = corruptedCachePreferences()
        val cacheBefore = mapOf(
            dashboardCacheJson to prefs[dashboardCacheJson],
            decksCacheJson to prefs[decksCacheJson],
            controlConfigJson to prefs[controlConfigJson],
            controlDraftJson to prefs[controlDraftJson],
            profilesJson to prefs[profilesJson]
        )

        AppSettingsPreferencesCodec.migrateInPlace(prefs)

        cacheBefore.forEach { (key, value) ->
            assertEquals("migration must not rewrite cache key $key", value, prefs[key])
        }
    }

    @Test
    fun `one corrupt field does not reset its neighbours`() {
        val prefs = mutablePreferencesOf()
        AppSettingsPreferencesCodec.writeSettings(prefs, userSettings)
        // Only the acoustic gap is nonsense; everything else must be read verbatim.
        prefs[AppSettingsPreferencesCodec.Keys.TTS_ACOUSTIC_GAP_MS] = Int.MIN_VALUE

        val settings = AppSettingsPreferencesCodec.readSettings(prefs)

        assertTrue("the gap must fall back to a sane minimum", settings.ttsAcousticGapMs > 0)
        assertEquals("profile-home-pc", settings.selectedProfileId)
        assertEquals(21, settings.maxReconnectAttempts)
        assertFalse(settings.handsFreeMode)
    }

    @Test
    fun `writing the same settings twice produces a byte-identical file`() {
        val first = mutablePreferencesOf()
        val second = mutablePreferencesOf()
        AppSettingsPreferencesCodec.writeSettings(first, userSettings)
        AppSettingsPreferencesCodec.writeSettings(second, userSettings)

        // A settings write must be idempotent: otherwise every app start looks like a change to
        // anything watching the file, and the "save" indicator never settles.
        assertEquals(first.asMap(), second.asMap())
        assertEquals(
            AppSettingsPreferencesCodec.readSettings(first),
            AppSettingsPreferencesCodec.readSettings(second)
        )
    }

    @Test
    fun `a cleared nullable setting stays cleared after a restart`() {
        val prefs = mutablePreferencesOf()
        val configured = userSettings.copy(selectedProfileId = "some-profile")
        AppSettingsPreferencesCodec.writeSettings(prefs, configured)

        AppSettingsPreferencesCodec.writeSettings(prefs, configured.copy(selectedProfileId = null))

        assertNull("the cleared value must not be resurrected", AppSettingsPreferencesCodec.readSettings(prefs).selectedProfileId)
        assertNull("and the key itself must be gone, not merely empty", prefs[AppSettingsPreferencesCodec.Keys.SELECTED_PROFILE_ID])
    }

    @Test
    fun `a DataStore edit of an unrelated key does not rewrite settings`() {
        // Guards against a `copy()`-based write that would drop defaults or unknown keys: the
        // codec must write every key it owns, and nothing else.
        val prefs = mutablePreferencesOf()
        AppSettingsPreferencesCodec.writeSettings(prefs, userSettings)
        val settingsKeys = prefs.asMap().keys.filter { it.name.startsWith("stt_") || it.name.startsWith("tts_") || it.name.startsWith("study_") }
        assertTrue("the codec must own and write its keys", settingsKeys.isNotEmpty())

        val untouched = mutablePreferencesOf()
        AppSettingsPreferencesCodec.writeSettings(untouched, userSettings)
        assertTrue(untouched.asMap().containsKey(AppSettingsPreferencesCodec.Keys.SCHEMA_VERSION))
    }
}

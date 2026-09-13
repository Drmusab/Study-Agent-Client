# Settings and configuration persistence contract

This document is the persistence contract for Study-Agent-Client. It is intentionally
more explicit than a list of DataStore keys: every value has an owner, a runtime/effective
value policy, a corruption policy, and an upgrade policy.

## Reliability guarantees

* `PreferencesDataStore.updateSettings` performs a read-transform-write inside one
  `DataStore.edit` transaction. DataStore serializes concurrent edits, so the final
  transaction contains the final transform result rather than a stale read.
* `AppSettingsPreferencesCodec` is the only mapper between `Preferences` and `AppSettings`.
  It writes every field, removes nullable values, validates untrusted input, and owns
  migrations. Adding a field without a codec/parity test is a code-review failure.
* Settings are never rebuilt from a UI-only mutable copy. Settings flows are the source of
  truth; runtime components collect those flows and derive typed TTS/STT/audio/network views.
* JSON management data is not part of `AppSettings`. Dashboard, deck, Study Control cache,
  and Study Control draft are optional, independently versioned persistence domains.
* An invalid optional JSON payload is discarded in its own domain. It cannot reset or prevent
  loading device settings, profiles, or another valid cache.

## Ownership and authority

| Data | Authority | Persistence | Runtime fallback |
| --- | --- | --- | --- |
| TTS/STT settings | Android | Preferences DataStore | Normalized user preference |
| Audio mode and disconnect behavior | Android | Preferences DataStore | `AUTO` / pause speech |
| Server profile identity and endpoint | Android | Profiles JSON in DataStore | Built-in local/emulator profiles |
| Profile auth token | Android secure storage | EncryptedSharedPreferences | No token |
| Network/reconnect preferences | Android | Preferences DataStore | Safe bounded defaults |
| Study Control v2 | PC Study Agent | `serverConfig` plus local cache | Server, then valid local config, then model default |
| Study Control v1 | Android local fallback | `localConfig` cache | Local config |
| Study Control draft | Android user work | Draft envelope | Kept through reject/timeout/process death |
| Dashboard/deck statistics | PC Study Agent | Android display cache | Fresh request, or unavailable |
| Dashboard/deck cache | Android display cache | Independent envelopes | Discard only the invalid cache |
| Server profile cache | Android | Profile envelope v1 (raw list is legacy-readable) | Built-in safe profiles without overwriting raw corruption |
| Connection state, current route, TTS inventory, STT capabilities | Runtime/device | Not persisted | Recomputed after startup |

### Study Control truths

`DefaultStudyControlRepository` deliberately retains three different values:

* `serverConfig`: the latest acknowledged PC Agent configuration. It is authoritative when
  a connected v2 Agent supports `study_config`.
* `localConfig`: the last known safe committed value. It is a fallback for v1/offline use,
  not proof that the PC Agent has accepted a change.
* `draft`: the user's unsaved edits. It is persisted as user work and is never deleted by a
  rejection, timeout, network failure, or an unrelated server push.

`effectiveConfig()` and Smart Start use `draft -> serverConfig -> localConfig`. This is an
explicit product policy: the visible Control Center edits are what Start uses, including an
unsaved draft. The Control Center UI reports dirty state and save state; a future product
change to “Start only saved config” must change this policy and its tests together.

A correlated `study_config_updated` ACK is required before a v2 save commits. The ACK's
server-normalized `config` wins when present. A server push while a draft is dirty updates
`serverConfig` and emits `serverPushedConfig`, but does not overwrite the draft. An error or
timeout leaves the previous authoritative/local value intact.

## AppSettings parity matrix

The key names below are stable storage contracts. `Codec` means both `readSettings` and
`writeSettings` in `AppSettingsPreferencesCodec`; `Normalize` means centralized validation
on read and before write. `Migration` is either a legacy migration or the safe current
field default.

| AppSettings field | Stable key | Codec | UI / source | Runtime consumer | Migration / null policy |
| --- | --- | --- | --- | --- | --- |
| `selectedProfileId` | `selected_profile_id` | read/write/remove | Profile screen/repository | `ProfileRepository` | null removes key |
| `sttLanguage` | `stt_language` | read/write | legacy only | STT fallback compatibility | old single locale |
| `sttLanguageMode` | `stt_language_mode` | read/write/normalize | Settings | `toSttSettings` | legacy locale -> mode |
| `sttEnglishLocale` | `stt_english_locale` | read/write/locale validate | mode defaults/advanced | STT | safe `en-US` |
| `sttArabicLocale` | `stt_arabic_locale` | read/write/locale validate | mode defaults/advanced | STT | safe `ar-IQ` |
| `sttAutoFallbackLocale` | `stt_auto_fallback_locale` | read/write/locale validate | STT | STT | legacy locale retained |
| `sttRecognitionMode` | `stt_recognition_mode` | read/write/enum fallback | Settings | STT backend policy | unknown -> `AUTO` |
| `sttPreferOnDevice` | `stt_prefer_on_device` | read/write | Settings | STT | default |
| `sttShowPartialTranscript` | `stt_show_partial_transcript` | read/write | Settings advanced | STT/UI | default |
| `sttMedicalBiasing` | `stt_medical_biasing` | read/write | Settings advanced | STT | default |
| `sttAnswerLength` | `stt_answer_length` | read/write/enum fallback | Settings | STT endpoint profile | unknown -> `NORMAL` |
| `sttDebugTranscriptLogging` | `stt_debug_transcript_logging` | read/write | Settings developer | explicit STT logging gate | default off |
| `ttsLanguage` | `tts_language` | read/write/locale validate | legacy | compatibility | default `en-US` |
| `speechRate` | `speech_rate_model_value` plus legacy mirror | read/write | legacy property | migration only | old key seeds per-purpose rates |
| `speechPitch` | `speech_pitch` | read/write/range | Settings | TTS | normalized |
| `ttsEngineId` | `tts_engine_id` | read/write/remove | Settings engine picker | TTS engine adapter | preferred value retained if unavailable |
| `englishVoiceId` | `english_voice_id` | read/write/remove | Settings voice picker | TTS selector | fallback without erasing preference |
| `arabicVoiceId` | `arabic_voice_id` | read/write/remove | Settings voice picker | TTS selector | fallback without erasing preference |
| `preferOfflineVoices` | `prefer_offline_voices` | read/write | Settings | TTS selector | default |
| `questionRate` | `question_rate` | read/write/range | Settings | TTS question/hint | independent |
| `feedbackRate` | `feedback_rate` | read/write/range | Settings | TTS feedback/rating/status | independent |
| `explanationRate` | `explanation_rate` | read/write/range | Settings | TTS answer/explanation | independent |
| `ttsAutoLanguageDetection` | `tts_auto_language_detection` | read/write | Settings | TTS segmentation | default |
| `ttsMedicalPronunciation` | `tts_medical_pronunciation` | read/write | Settings | TTS preprocessing | default |
| `ttsAcousticGapMs` | `tts_acoustic_gap_ms` | read/write/range | Settings | TTS -> STT handoff | 150..1200 ms |
| `headsetDisconnectBehavior` | `headset_disconnect_behavior` | read/write/stable enum | Settings | TTS route-loss policy | unknown -> pause |
| `studyAudioMode` | `study_audio_mode` | read/write/stable enum | Settings | audio route resolver | missing/unknown -> `AUTO` |
| `phoneAudioNoticeAcknowledged` | `phone_audio_notice_acknowledged` | read/write | Study screen | phone-mode notice | default false |
| `handsFreeMode` | `hands_free_mode` | read/write | Settings/preset adapter | study session/STT | default |
| `autoPlayQuestion` | `auto_play_question` | read/write | Settings | study session | default |
| `autoPlayFeedback` | `auto_play_feedback` | read/write | Settings | study session | default |
| `autoSubmitTranscript` | `auto_submit_transcript` | read/write | Settings | study session/STT | default |
| `confirmRating` | `confirm_rating` | read/write | Settings | rating flow | default false |
| `listenForSpokenRating` | `listen_for_spoken_rating` | read/write | Settings | study session/STT | old missing key -> true |
| `showTranscriptOnScreen` | `show_transcript` | read/write | Settings | study UI | default |
| `useFakeAgent` | `use_fake_agent` | read/write | developer/connection | connection repository | default false |
| `debugLogging` | `debug_logging` | read/write | Settings | logger/diagnostics | default |
| `autoReconnect` | `auto_reconnect` | read/write | Settings | WebSocket connection | default |
| `maxReconnectAttempts` | `max_reconnect_attempts` | read/write/range | network policy | WebSocket connection | 0..100 |
| `pingIntervalSeconds` | `ping_interval_seconds` | read/write/range | network policy | WebSocket ping loop | 5..300 seconds |

`SPEECH_RATE` remains a stable legacy mirror of `questionRate` for older app versions. The
new runtime source is always the three purpose-specific rates. The additional internal
`SPEECH_RATE_MODEL_VALUE` key prevents the deprecated `AppSettings.speechRate` property from
being silently lost during a complete codec round trip. It is not consulted by TTS runtime.

## Non-overlapping device and Agent settings

The persistence domains are intentionally not collapsed:

* `listenForSpokenRating` controls whether Android opens its microphone for spoken Again/Hard/Good/Easy commands. Control Center `ratingMode` controls how the PC Agent applies/suggests ratings; one does not overwrite the other.
* `showTranscriptOnScreen` controls device display. `transcriptRetention` controls what the Agent stores for privacy. Displaying a transcript is not permission to retain it.
* `autoSubmitTranscript` controls when Android sends a completed recognition result. It does not change transcript retention.
* `handsFreeMode` is the Android session workflow preference. Study Control presets may offer an explicit local behavior adapter, but server-side evaluation/rating configuration remains in `StudyControlConfig`.

Runtime states such as current connection, effective Bluetooth route, discovered TTS voice inventory, recognizer capabilities, `isSpeaking`, and `isListening` are never persisted as settings.

## Schema and migrations

`settings_schema_version` is currently **5**. A missing version is treated as legacy v1.
Migrations are sequential and idempotent:

1. **v1 -> v2:** if per-purpose rate keys are absent, seed them from legacy `speech_rate`.
2. **v2 -> v3:** convert legacy `stt_language` to `sttLanguageMode` and language locales.
   `ar-IQ` remains Arabic; `en-GB` remains the English locale and fallback.
3. **v3 -> v4:** missing/old audio mode becomes `AUTO`.
4. **v4 -> v5:** missing spoken-rating persistence becomes `true`, preserving the historical
   behavior while making it durable.

The AndroidX DataStore migration adapter invokes these steps before normal reads. Settings
writes also run the codec migration inside their transaction. A stored version newer than
this build is not downgraded and unknown keys are not cleared. Unknown enum values use safe
runtime fallbacks.

Cache schemas are independent from AppSettings:

* Dashboard cache: v1
* Deck cache: v1
* Control config cache: v1
* Control draft: v1

Each cache envelope contains `schemaVersion`, `savedAtEpochMs`, and its payload. Raw JSON
from older builds remains readable as schema 0 and is upgraded after a successful rewrite.
An unsupported/failing optional cache is discarded alone; a fresh server request can repair
it. Cache freshness is decided by repository policy, not by the storage layer.

## Validation and effective values

`AppSettingsPolicy` is the one range/normalization authority:

* TTS rates: 0.6..1.8
* speech pitch: 0.7..1.4
* acoustic gap: 150..1200 ms
* reconnect attempts: 0..100
* ping interval: 5..300 seconds
* locale tags are checked before `Locale.forLanguageTag`; malformed values use a safe locale
* unknown STT/audio/headset modes use explicit stable fallback identifiers
* NaN/infinite rates and pitch use safe defaults; out-of-range finite numbers are clamped

The UI and TTS runtime reference these same constants. A runtime fallback is never written
back as a preference:

* unavailable preferred TTS engine -> system engine for this run; preferred package retained
* unavailable preferred voice -> compatible selected voice; preferred voice ID retained
* unavailable on-device STT -> available system/network recognizer; preference retained
* `AUTO` audio -> current phone/headset route; `AUTO` remains persisted

## Profiles and secrets

Profile JSON contains endpoint identity only. `authToken` is removed before serialization and
tokens are stored in `AndroidSecureTokenStorage`/encrypted preferences. A corrupt profile JSON
blob yields built-in runtime fallback profiles but is not immediately overwritten with those
defaults. Profile mutation refuses to replace an unreadable blob. Deleting the selected profile
resolves selection atomically to a default/first valid profile, or removes the selection when
none remains. Profile IDs are used for updates, so editing name/host/port does not create a
new ID.

## Error and corruption handling

* DataStore I/O read errors emit `emptyPreferences()` and log a sanitized category. Other
  exceptions are rethrown so programming/codec errors are visible rather than disguised as
  defaults. File-level DataStore corruption uses AndroidX's corruption handler; a malformed
  JSON value inside a valid preferences file is isolated by its owning repository.
* A settings write catches `IOException`, keeps the last committed flow value, and exposes a
  transient error to Settings UI. It does not claim that a failed write succeeded.
* Dashboard/deck/control cache decoding is caught per cache. Control draft decode failure
  clears only the draft; local/server config remains available. A config save failure never
  deletes the draft.
* Persistence logs contain schema/category/error type only. Raw settings/cache JSON,
  profile tokens, auth headers, medical transcripts, and full transcripts are not logged.

## Adding a new setting checklist

1. Add the domain property with the intended fresh-install default.
2. Add a stable typed DataStore key in `AppSettingsPreferencesCodec.Keys`.
3. Add read and write/remove mapping in the codec.
4. Add centralized validation/default behavior in `AppSettingsPolicy` if needed.
5. Add a sequential migration when semantics differ from an old key.
6. Add/update the Settings UI, or document why it is migration-only.
7. Add the runtime consumer and distinguish preferred from effective fallback values.
8. Add a non-default round-trip assertion, null set/clear assertion if nullable, and a
   restart/process-death-style fixture assertion.
9. Update the parity matrix and migration notes.

## Reset scope

`resetAppSettings()` atomically clears only device/app settings and restores fresh defaults
on the next read. It does not remove server profiles, secure tokens, Study Control config or
draft, dashboard/deck caches, or credentials. Cache clearing and “discard unsaved Control
changes” remain separate operations.

## Known limits

A full file-level DataStore corruption event is handled by AndroidX with empty preferences;
there is no safe way to recover arbitrary bytes in that file. JSON-level optional cache
corruption is isolated as described above. Downgrades from a future build cannot preserve
semantics unknown to the older build, but unknown strings and bounded numbers do not crash
older runtime code.

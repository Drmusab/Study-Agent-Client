# STT Subsystem — Engineering Report

**Branch:** `arena/01a0909d-study-agent-client` (from `09215fe`)
**Scope:** Speech-to-text as a first-class subsystem of the Study Agent Android client.
**Companion document:** `docs/STT_ARCHITECTURE.md` (the implementation as built).

---

## 0. Verification status

> ### Partially verified. The framework-free core **is** compiled and **32 tests were executed and pass**.
> ### The Gradle build was **not** run and could not be — `assembleDebug` / `assembleRelease` /
> ### `testDebugUnitTest` remain **unverified**.

An earlier revision of this report claimed nothing could be compiled here. That was wrong:
the sandbox has partial network access, and a real toolchain was assembled from it.

### What was actually built and run

| Component | Source | Version |
|---|---|---|
| JDK | npm `javajre-linux-64@17.0.8` | Oracle OpenJDK **17.0.8+9-LTS-211**, full JDK (`javac`, `jar`, `jshell`) |
| Kotlin compiler | npm `kotlin-compiler@1.9.24` | **kotlinc-jvm 1.9.24** — exact match for the project |
| `kotlinx-coroutines-core` (JVM) | bundled in the Kotlin dist | JVM jar |
| `kotlin-stdlib`, `kotlin-test-junit` | bundled in the Kotlin dist | 1.9.24 |
| `hamcrest-core` | compiled from `hamcrest/JavaHamcrest` tag `hamcrest-java-1.3` | 1.3 — `org.hamcrest.CoreMatchers` produced by the **upstream `hamcrest-generator`**, not hand-written |
| `junit` | compiled from `junit-team/junit4` tag `r4.13.2` | 4.13.2 (350 classes) |

Two small stand-ins were necessary because their real artifacts live on Maven Central,
which is blocked: `android.util.Log` (the 4 call sites in `AppLogger`) and the
`kotlinx.serialization.Serializable` **annotation class only**. Neither contains project
logic. The real serialization compiler plugin is present in the Kotlin dist but was not run,
so `@Serializable` graphs are **not** validated.

### Results

```
$ kotlinc -no-stdlib -cp <stdlib:coroutines-core-jvm:stubs> -d out <16 files>
errors: 0     warnings: 0     class files: 124

$ kotlinc -no-stdlib -cp <android-34.jar:stdlib:coroutines-core-jvm:main> -d out \
        AndroidSpeechRecognitionBackend.kt
errors: 0     warnings: 0     class files: 15

$ kotlinc -no-stdlib -cp <android-34.jar:stdlib:coroutines-core-jvm:main> -d out \
        AudioRouteManager.kt AudioDeviceInfoModel.kt
errors: 0     warnings: 0     class files: 8

$ java -cp <tests:main:junit:hamcrest> org.junit.runner.JUnitCore \
      com.studyagent.client.stt.VoiceCommandInterpreterTest \
      com.studyagent.client.stt.RecognitionPolicyAndVocabularyTest
JUnit version 4.13.2-SNAPSHOT
................................
Time: 0.171

OK (32 tests)
```

**Compiled:** all 12 files in `core/voice/stt/`, plus `AppSettings.kt`, `VoiceCommand.kt`,
`TtsSettings.kt`, `AppLogger.kt`, `AudioRouteManager.kt` and `AudioDeviceInfoModel.kt`.

`android.jar` for API 34 (26,361,808 bytes) was obtained from the `Sable/android-platforms`
repository through the GitHub blob API, which is what made the platform-dependent files
compilable at all. Verifying against it paid for itself immediately — see §4.16 item 8.

**Executed:** 32 of the 53 new tests — `VoiceCommandInterpreterTest` (20) and
`RecognitionPolicyAndVocabularyTest` (12). These cover command safety, answer safety,
confidence gates, Arabic normalization, bias-list bounds, and the settings bridge.

### Still unverified

| Item | Why |
|---|---|
| `./gradlew testDebugUnitTest` | Gradle distribution is on `services.gradle.org` — blocked |
| `./gradlew assembleDebug` / `assembleRelease` | Android Gradle Plugin and AndroidX are on `dl.google.com` / Maven Central — blocked |
| `SpeechRecognitionOrchestratorTest.kt` (21 tests) | needs `kotlinx-coroutines-test`, only on Maven Central |
| All UI, repository, datastore, navigation code | needs AndroidX / Compose artifacts |
| Resource linking, manifest merging, R8/ProGuard, lint | Gradle-only steps |
| `@Serializable` model graphs | serialization compiler plugin not run |

Reachability was unstable and changed several times during the work; `codeload.github.com`
and `api.github.com` were each unavailable at one point and had to be retried. The reachable
hosts were `registry.npmjs.org`, `pypi.org` / `files.pythonhosted.org`, `github.com`,
`codeload.github.com` and `api.github.com`.
Everything Maven- or Google-hosted returned connection failures, and the GitHub
credential expired partway through, closing off the source-archive route before
`kotlinx-coroutines-test` and `android.jar` could be fetched.

### What the compiler and tests caught

Static analysis over the sources found only one of these. The rest required a real compiler
or a real test run — see §4.16.

## 1. Architecture changes

### 1.1 Before

`AndroidSpeechRecognitionManager` was a 200-line wrapper: one `Boolean` of state, one
`startListening(languageCode, isHandsFree)`, one `SharedFlow` of untyped events. Every
concern — endpointing, retry, language, candidate choice, command parsing, confidence —
either did not exist or lived in `StudySessionRepository`.

### 1.2 After

```
StudySessionRepository          what to listen for; what a result means for study state
SpeechRecognitionOrchestrator   one owner of the mic: lifecycle, watchdog, retry, rate limit,
                                stale-callback rejection, metrics
RecognitionPolicyFactory        purpose → RecognitionRequest
SpeechRecognitionBackend        contract: one recognizer, request-stamped events
AndroidSpeechRecognitionBackend SpeechRecognizer / RecognitionListener / RecognizerIntent
```

Plus framework-free services: `RecognitionCandidateSelector`, `VoiceCommandGrammar`,
`VoiceCommandInterpreter`, `CommandNormalizer`, `MedicalVocabularyProvider`.

**3,310 lines** in `core/voice/stt/` (12 files). This mirrors the existing `core/voice/tts/`
layering deliberately, so the two halves of the voice pipeline read the same way.

The repository shrank in *responsibility*: recognizer lifecycle, retry, configuration,
candidate selection, language policy and endpointing all moved out. It no longer holds a
`VoiceCommandManager` at all.

---

## 2. Confirmed problems in the original code

Every item below was verified against `git show HEAD:<file>` during this session. Line
numbers are from the base commit.

| # | Defect | Evidence (base commit) | Consequence |
|---|---|---|---|
| 1 | **State was a `Boolean`.** `_isListening.value = false` set in `onEndOfSpeech()` (L139) *and* `stopListening()` (L91). | `AndroidSpeechRecognitionManager.kt` L28–29, 91, 139 | A second `startListening()` could reach a recognizer still finalising → `ERROR_RECOGNIZER_BUSY`. |
| 2 | **Only `result[0]` was read.** `matches?.firstOrNull()`; `EXTRA_CONFIDENCE_SCORES` never read. | `AndroidSpeechRecognitionManager.kt` L156–157 | Alternative hypotheses and all confidence information discarded. No safety gate was possible. |
| 3 | **Prefix matching.** `t.startsWith("again")`. | `VoiceCommandManager.kt` L130 | The answer *"again, there is a midline shift"* rated the card **Again** and rescheduled it. |
| 4 | **`parseInStudyContext` context gating was a no-op** — the `when` fell through to `else -> cmd`. | `VoiceCommandManager.kt` | Commands fired in every state, including while answering. |
| 5 | **`autoSubmitTranscript` / `confirmRating` persisted but never consulted.** Present in `AppSettings` L41–42 and `PreferencesDataStore` L103–104, 134–135; **zero reads in `StudySessionRepository`**. | `git grep` over base | Toggles in Settings changed nothing. |
| 6 | **`listenForSpokenRating` had no DataStore key at all.** In `AppSettings` L44 and the presets in `StudyControlModels.kt`, absent from `PreferencesDataStore`. | `git grep` over base | Silently reset to `true` on every launch; the setting was unwritable. |
| 7 | **`NoSpeech` and `Error` handlers only logged.** `AppLogger.d(tag, "STT reported NoSpeech")` / `AppLogger.w(…)`. | `StudySessionRepository.kt` L580–586 | The study loop stalled permanently with no user feedback and no retry. |
| 8 | **No watchdog.** No timer anywhere in the STT path. | absence, verified | A recognizer that never calls back hangs the session forever. |
| 9 | **No request/card identity on results.** `Final` reads `_studyState.value` when the callback lands. | `StudySessionRepository.kt` L568–574 | A late callback from a cancelled turn was applied to whatever card was on screen. |
| 10 | **`stopManualPushToTalk(submitIfTranscriptPresent)` ignored its parameter** and just called `stopListening()`. | `StudySessionRepository.kt` L816–819 | PTT release semantics undefined; no `onResults` wait was modelled. |
| 11 | **No `<queries>` element** while `targetSdk = 34`. | `git show HEAD:AndroidManifest.xml \| grep -c queries` → **0** | AOSP requires it at API 30+; recognition can fail to find a service. |
| 12 | **`AudioRouteManager` was output-only.** | `AudioRouteManager.kt` | No way to tell a Bluetooth *microphone* from Bluetooth *headphones*. |
| 13 | **Full transcript logged unconditionally at INFO.** `AppLogger.i(tag, "Final recognition result: '$topMatch'")`. | `AndroidSpeechRecognitionManager.kt` L158 | Raw answers in Logcat and in diagnostics exports, with no opt-out. |
| 14 | **No `NoMatch` vs `NoSpeech` distinction**, no error taxonomy at all — errors arrived as a raw int. | `SpeechRecognitionResult.kt` | Indistinguishable user-facing states. |
| 15 | **No retry, no rate limit, no busy-race prevention.** | absence, verified | Concurrent TTS-completion + UI-tap + hands-free-loop starts collided. |

---

## 3. Files changed

**40 files, +6,755 / −518.**

### 3.1 One pre-existing bug fixed

`core/models/StudySession.kt` had a **duplicated `val isPaused: Boolean = false`** followed by
a stray `)` inside a constructor parameter list. Verified present in the base commit via
`git show HEAD:…` with an empty working diff. **This is a syntax error: the base commit could
not have compiled.** Repaired (duplicate and stray token removed), no semantic change.

### 3.2 New — `core/voice/stt/` (12 files, 3,310 lines)

| File | Lines | Role |
|---|---|---|
| `RecognitionModels.kt` | 531 | States, purposes, error codes, capabilities, metrics, health |
| `AndroidSpeechRecognitionBackend.kt` | 694 | **The only class touching `SpeechRecognizer`** |
| `DefaultSpeechRecognitionOrchestrator.kt` | 554 | Lifecycle, watchdog, retry, rate limit, stale-callback rejection |
| `VoiceCommandGrammar.kt` | 379 | EN+AR phrase table, bounded fuzzy matching |
| `VoiceCommandInterpreter.kt` | 240 | Context + confidence → decision |
| `RecognitionPolicyFactory.kt` | 207 | Purpose → request; retry-delay table |
| `MedicalVocabularyProvider.kt` | 193 | Bias list, ≤40 terms, purpose-scoped |
| `SpeechRecognitionBackend.kt` | 130 | Contract |
| `RecognitionCandidateSelector.kt` | 120 | Which hypothesis represents the turn |
| `CommandNormalizer.kt` | 115 | Command vs. answer normalization |
| `SttSettings.kt` | 92 | Thresholds, endpoint profiles |
| `SttSettingsMapping.kt` | 55 | `AppSettings` → `SttSettings` |

### 3.3 New — tests (4 files, 1,329 lines, **53 tests**)

| File | Tests |
|---|---|
| `SpeechRecognitionOrchestratorTest.kt` | 21 |
| `VoiceCommandInterpreterTest.kt` | 20 |
| `RecognitionPolicyAndVocabularyTest.kt` | 12 |
| `FakeSpeechRecognitionBackend.kt` | — (test double; scripts every Android callback) |

Pure JVM. No Robolectric (not in the project), no emulator, no microphone, no PC backend.

### 3.4 Deleted (3 files)

`core/voice/AndroidSpeechRecognitionManager.kt`, `SpeechRecognitionManager.kt`,
`SpeechRecognitionResult.kt`.

### 3.5 Modified (19 files)

`VoiceCommandManager.kt` (now a thin facade over grammar + interpreter — **its public contract
is unchanged**, so `VoiceCommandManagerTest` still applies), `AppSettings.kt` (+10 fields, 38
total), `StudyState.kt`, `StudySession.kt` (syntax repair), `AudioRouteManager.kt`,
`PreferencesDataStore.kt`, `StudySessionRepository.kt`, `DiagnosticsRepository.kt`,
`AppContainer.kt`, `StudyViewModel.kt`, `StudyScreen.kt`, `AppNavHost.kt`,
`SettingsViewModel.kt`, `SettingsScreen.kt`, `DiagnosticsViewModel.kt`,
`DiagnosticsScreen.kt`, `AndroidManifest.xml`.

### 3.6 Documentation

New `docs/STT_ARCHITECTURE.md`. Updated `docs/VOICE_FLOW.md` (command grammar is now
context-gated; input routes tracked) and `docs/SECURITY.md` (new §1.5 Speech Recognition
Privacy).

---

## 4. Improvements, and what each one fixes

### 4.1 Explicit state machine (fixes #1)
`RecognitionState`: `Unavailable → Idle → Preparing → ReadyForSpeech → Listening →
SpeechDetected → Processing → Completed`, plus `Failed` / `Released`. `onEndOfSpeech` moves
to **`Processing`**, not idle. `isReadyForNewRequest` is derived from state and is the only
readiness signal. A `isListening: StateFlow<Boolean>` is still exposed for UI compatibility,
but no internal decision reads it.

### 4.2 Request and card identity (fixes #9)
Every turn carries `requestId` (`answer_<seq>` / `rating_<seq>` / `command_<seq>`) and
`cardId`. Two independent guards: the orchestrator drops events whose id is not active
(counted in `metrics.staleCallbacksDropped`), and the repository discards outcomes whose
`cardId` does not match the card on screen. `cancelCurrentTurn` invalidates *before* calling
`backend.cancel()`.

### 4.3 Distinct policies per purpose (fixes #7, #14)
`ANSWER`, `RATING`, `COMMAND`, `PUSH_TO_TALK_ANSWER`, `PUSH_TO_TALK_COMMAND`,
`DECK_SELECTION`, `SHORT_CONFIRMATION`. Each resolves to its own endpoint profile, watchdog
budget, vocabulary and acceptance threshold. `RATING` gets a 15 s total budget; a long answer
gets 120 s.

### 4.4 Candidates and confidence (fixes #2)
All alternatives plus `EXTRA_CONFIDENCE_SCORES` are read into
`List<RecognitionHypothesis>`. Confidence is nullable **end to end** — many recognizers never
populate the array, and inventing a value would void every downstream safety gate. Where
absent, grammar strength alone decides.

### 4.5 Command safety (fixes #3, #4)
Whole-phrase matching only; fuzzy bounded to one edit on one word of length ≥ 4.
`VoiceCommandInterpreter` makes context authoritative — during an answer, only explicit
multi-word phrases fire; a lone "good"/"stop"/"next"/"easy" is submitted as the answer.
**Destructive commands (`Again`, `Skip`, `EndSession`) require a verbatim `EXACT` hit** —
"stoop" at 0.99 cannot end a session.

### 4.6 Rating thresholds (fixes #5)
≥0.75 apply · 0.45–0.75 confirm-or-re-listen · <0.45 reject · absent → apply only on an
`EXACT` phrase. Low confidence can never silently change Anki scheduling.

### 4.7 Push-to-talk (fixes #10)
Release calls `finishCurrentTurn()` (`stopListening`, not `cancel`) and **submits nothing**;
the transcript is submitted when `onResults` arrives. The dead parameter is gone.

### 4.8 Bilingual (EN / AR / Auto)
API 34+: `EXTRA_ENABLE_LANGUAGE_DETECTION`, `EXTRA_ENABLE_LANGUAGE_SWITCH`
(`LANGUAGE_SWITCH_BALANCED`), both `*_ALLOWED_LANGUAGES` restricted to a two-locale allowlist.
`onLanguageDetection` surfaces as a diagnostics event only — the user is never interrupted to
be told the language changed. Below 34 the request still works in `autoFallbackLocale`.
**Two recognizers are never run at once.** Arabic command matching unifies `أ إ آ ٱ → ا`,
`ى → ي`, `ة → ه`, strips tashkeel/tatweel. **Answer text is not normalized** — no medically
ambiguous abbreviation or number is ever rewritten.

### 4.9 Capability discovery and honest "Unknown"
From `isRecognitionAvailable`, `isOnDeviceRecognitionAvailable` (31+),
`checkRecognitionSupport` (33+). `null` means "could not determine" and renders as
**Unknown** — never coerced to `false`, which would falsely claim offline recognition is
unavailable. Likewise the app never shows "Offline" unless on-device recognition is actually
known to be in use.

### 4.10 Failure handling (fixes #7, #8, #14, #15)
18 `RecognitionErrorCode` values covering every `SpeechRecognizer.ERROR_*` in the target SDK
including the API 31/33 additions. `NO_SPEECH` ≠ `NO_MATCH` throughout. Bounded retry
(`maxRetriesPerTurn = 2`) with per-error backoff; `TOO_MANY_REQUESTS` is not retried;
permission/unavailability/unsupported-language/missing-model are never auto-retried. A second
repository-level bound (`MAX_CONSECUTIVE_RECOGNITION_FAILURES = 3`) stops a dead microphone
re-opening itself all session, falling back to on-screen controls. Four-phase watchdog
(`readyMs`, `firstSpeechMs`, `finalResultMs`, `totalMs`) guarantees a terminal outcome.
`minTurnIntervalMs = 250` rate-limits start races.

### 4.11 TTS/STT never overlap
Three independent gates: a `canOpenMicrophone` predicate ("nothing playing, nothing queued"),
the existing `VoiceHandoffController` acoustic gap (350 ms), and request identity.

### 4.12 Headset loss (fixes #12)
`AudioRouteManager` enumerates `GET_DEVICES_INPUTS` too. `TYPE_BLUETOOTH_A2DP` is output-only
and never appears in the input list; `TYPE_BLUETOOTH_SCO` appearing there means the
communication profile is actually available. `isCertain = false` for inferred routes renders
as *"… (system-selected)"* — the app never claims a Bluetooth microphone is active merely
because headphones are connected. Losing the route cancels the turn; partials are never
submitted.

### 4.13 Privacy (fixes #13)
Completion logs `purpose=ANSWER chars=146 candidates=3 conf=0.87 source=ON_DEVICE
finalizeMs=410`. Full text requires the explicit `sttDebugTranscriptLogging` opt-in.
`onBufferReceived` is empty. Metrics store no text. Diagnostics export is redacted *by
construction*, since the logger never receives transcript text unless enabled. The bias list
is built from the **question only** — the expected answer never reaches the client, so it
cannot leak into a bias list or a log.

### 4.14 Bounded event volume
`onRmsChanged` / `onPartialResults` sampled in the backend (60 ms / 100 ms). Levels and
partials travel on **conflated `StateFlow`s, not the terminal-result `SharedFlow`** — a chatty
recognizer cannot evict a transcript from a shared buffer.

### 4.15 Diagnostics show real status
`DiagnosticsRepository.recognitionDiagnosticsRows()` renders provider package, backend in
use, per-language model state, capability flags, live turn identity, request age, retry
attempt, last confidence, and counters (turns ok/failed/cancelled, no-speech/no-match,
busy/rate-limited, watchdog timeouts, stale callbacks dropped, on-device/network split).
Wired into `DiagnosticsScreen` as a SPEECH RECOGNITION card plus a live state badge, and into
the export text. **Every undetermined value renders as `Unknown`.**

### 4.16 Defects found only by compiling and running

Static brace/symbol analysis over the sources found **one** of these seven. The rest needed a
real compiler or a real test run. This is the strongest argument in this report for running
the build before trusting any of it.

| # | Defect | How it was caught | Fix |
|---|---|---|---|
| 1 | `RecognitionPurpose` had **no `label` property**, but `RecognitionPolicyFactory:118` and `DefaultSpeechRecognitionOrchestrator:408` both called `purpose.label`. Would not compile. | kotlinc: `unresolved reference: label` | added `RecognitionPurpose.label`; `RecognitionRequest.label` now delegates to it |
| 2 | **`entries` was shadowed inside `buildMap { }`.** `Map.entries` (a `Set<Map.Entry>`) hid `VoiceCommandGrammar.entries` (a `List<CommandEntry>`), so `entry.phrases` resolved against `Map.Entry` and `existing + entry` became `List<Any>`. | kotlinc: `unresolved reference: phrases` + `type mismatch: List<Any>` | renamed the field to `commandEntries` |
| 3 | **Missing `import kotlinx.coroutines.flow.collect`.** Without it only the `@InternalCoroutinesApi` member `collect(FlowCollector)` is visible, so `backend.events.collect { }` produced a misleading *"internal kotlinx.coroutines API"* error on the event pump — the loop that drives the entire subsystem. | kotlinc (3 cascading errors) | added the import |
| 4 | `updateSettings(newSettings:)` disagreed with the supertype's parameter name `settings`, so any caller using a named argument would fail. | kotlinc warning | renamed to `settings` |
| 5 | **`selectForAnswer` overrode rank 0 on a ≥0.20 confidence gap.** With alternatives `["could" 0.53, "good" 0.92]` during an answer, the transcript became `"good"` — a medical answer silently rewritten into a rating word. This also contradicted the class's own KDoc ("deliberately boring: rank 0"). | **test failure**: `expected:<[coul]d> but was:<[goo]d>` | `selectForAnswer` now returns the lowest-ranked non-blank hypothesis, always; `CONFIDENCE_OVERRIDE_GAP` deleted |
| 6 | **`medicalVocabularyBiasing = false` did not turn medical biasing off.** It dropped only the question-derived terms, leaving the curated core list active — so `"epidural hematoma"` was still biased in with the toggle off, and the setting appeared to do nothing. | **test failure** | for answer-like purposes the toggle now clears the hint list entirely; rating/command vocabularies (not medical) are untouched |
| 7 | A test asserted ordering against `"septic shock"`, which the 40-term `MAX_BIAS_TERMS` truncation removes — so the assertion compared `0 < -1` and would fail for a reason unrelated to ordering. | **test failure**, then a probe printing the real list | compared against `subdural hematoma` (present at index 2) and added a guard asserting it is present |

Items 5 and 6 are the ones that matter: both are user-visible behavioural bugs in
safety-relevant paths, and neither was reachable by reading the code.

Once `android.jar` for API 34 became available, compiling `AndroidSpeechRecognitionBackend.kt`
added two more:

| # | Defect | How it was caught | Fix |
|---|---|---|---|
| 8 | **`checkRecognitionSupport` was called with two arguments.** The platform exposes only `checkRecognitionSupport(Intent, Executor, RecognitionSupportCallback)` — there is no two-argument overload, contrary to what the documentation read at design time suggested. | kotlinc against `android-34.jar`: `inferred type is <no name provided> but Executor was expected` + `no value passed for parameter 'p2'` | pass a direct `Executor { it.run() }`; the call is already inside `postToMain`, so the callback still lands on the main thread |
| 9 | `requestModelDownload` assigned `requested = true` unconditionally after posting, so it returned `true` even when no recognizer existed or `triggerModelDownload` threw. The local variable was dead — kotlinc flagged it as a redundant initializer. | kotlinc warning, then reading the body | returns the real outcome; documented as exact on the main thread and best-effort when queued |

Every other API-33/34 symbol used in that file was verified present in `android-34.jar` with
`javap`: `triggerModelDownload(Intent)`, `isOnDeviceRecognitionAvailable`,
`createOnDeviceSpeechRecognizer`, `DETECTED_LANGUAGE`, `LANGUAGE_DETECTION_CONFIDENCE_LEVEL`,
`LANGUAGE_SWITCH_RESULT`, `TOP_LOCALE_ALTERNATIVES`, `EXTRA_BIASING_STRINGS`,
`EXTRA_ENABLE_LANGUAGE_DETECTION`, `EXTRA_ENABLE_LANGUAGE_SWITCH`,
`EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES`, `EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES`,
`LANGUAGE_SWITCH_BALANCED`, and the four `RecognitionSupport` getters.

---

## 5. API 26–33 vs. 34+ compatibility

| Capability | API | Behaviour below it |
|---|---|---|
| Core recognition | 8+ | — |
| `EXTRA_PREFER_OFFLINE` | 23 | (documented as a hint implementations may ignore) |
| `EXTRA_BIASING_STRINGS` | **33** | no biasing; recognition unaffected |
| `checkRecognitionSupport` / `RecognitionSupport` | **33** | capability fields stay `null` → **Unknown** |
| `triggerModelDownload` | **33** | Settings download button hidden |
| On-device recognizer (`createOnDeviceSpeechRecognizer`) | **31** | system recognizer only |
| `ERROR_SERVER_DISCONNECTED`, `ERROR_TOO_MANY_REQUESTS`, `ERROR_LANGUAGE_NOT_SUPPORTED`, `ERROR_LANGUAGE_UNAVAILABLE` | **31** | fall through to a legacy mapping |
| `ERROR_CANNOT_CHECK_SUPPORT` | **33** | fall through |
| `EXTRA_ENABLE_LANGUAGE_DETECTION`, `EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES` | **34** | single-locale recognition |
| `EXTRA_ENABLE_LANGUAGE_SWITCH`, `onLanguageDetection` | **34** | no mid-turn switching |

**Nothing above is required for core function.** The state machine, purposes, candidate
selection, command safety, watchdogs, retry and metrics are identical on API 26 and API 34.
Every API 33/34 feature is `Build.VERSION.SDK_INT`-guarded and degrades to the plain system
recognizer. API 26 support is preserved — no `minSdk` change, no new required permission.

Note that `EXTRA_ENABLE_LANGUAGE_SWITCH` takes a **String** sensitivity level, not a boolean
(`"quick_response"` / `"balanced"` / `"high_precision"`). There is no
`EXTRA_LANGUAGE_DETECTION` constant. These were verified against the platform documentation
because the SDK is not available here to compile against.

---

## 6. Test commands and results

### Executed

```
$ export JAVA_HOME=<jdk-17.0.8>
$ kotlinc -no-stdlib -cp kotlin-stdlib.jar:kotlinx-coroutines-core-jvm.jar:stubs \
          -d out <11 stt files + AppSettings + VoiceCommand + TtsSettings + AppLogger>
# errors: 0   warnings: 0   class files: 124

$ kotlinc -no-stdlib -cp <main>:junit-4.13.2.jar:hamcrest-core-1.3.jar \
          -d tout VoiceCommandInterpreterTest.kt RecognitionPolicyAndVocabularyTest.kt
# errors: 0

$ java -cp <tout>:<main>:junit:hamcrest org.junit.runner.JUnitCore \
       com.studyagent.client.stt.VoiceCommandInterpreterTest \
       com.studyagent.client.stt.RecognitionPolicyAndVocabularyTest
JUnit version 4.13.2-SNAPSHOT
................................
Time: 0.182

OK (32 tests)
```

**Result: 32 tests run, 32 passed, 0 failures.**

### Not executed

```
./gradlew testDebugUnitTest     # NOT RUN — no Gradle distribution reachable
./gradlew assembleDebug         # NOT RUN — no AGP / AndroidX / Android SDK reachable
./gradlew assembleRelease       # NOT RUN — same
```

`SpeechRecognitionOrchestratorTest` (21 tests) is written and was **not** run: it needs
`kotlinx-coroutines-test`, which is only published to Maven Central. Its subject,
`DefaultSpeechRecognitionOrchestrator`, does compile.

### What the 32 executed tests cover

- Command vocabulary: English and Arabic, exact-phrase matching, no prefix matching
- **Answer safety** — a lone "good" / "stop" / "next" / "easy" is submitted as the answer,
  never executed; the same alternatives resolve differently in RATING vs ANSWER context
- **Destructive commands require a verbatim `EXACT` hit** ("stoop" at 0.99 cannot end a session)
- Rating confidence gates: ≥0.75 apply, 0.45–0.75 confirm-or-retry, <0.45 reject,
  absent score → apply only on an EXACT phrase
- `PAUSED` / `IDLE` context restrictions
- Arabic alef / ya / ta-marbuta / tashkeel normalization
- Command-vs-answer normalizer split (answer text is never rewritten)
- Bias list: bounded at 40, de-duplicated, context-terms-first, whole-word abbreviations,
  per-purpose scoping, and the disable toggle actually disabling
- `AppSettings.toSttSettings()` round-trip and safe fallbacks for corrupt values
- Confidence-gate ordering and `detectionAllowlist()`
- No expected-answer leakage into the bias list

## 7. Real-device limitations

These cannot be tested in a sandbox at all, and the code is written to be honest about them
rather than to hide them:

1. **Which microphone was actually used is unknowable.** Android does not publish the input
   route a `SpeechRecognizer` selected. The UI therefore always reports Bluetooth input as
   inferred, never as certain.
2. **`EXTRA_PREFER_OFFLINE`, `EXTRA_BIASING_STRINGS` and the silence-length extras may be
   ignored** by any given recognizer. Nothing depends on them; endpointing is bounded by the
   watchdog instead.
3. **Recognizer behaviour varies enormously by OEM.** Timeout, endpoint and partial-result
   cadence are not specified by the platform.
4. **On-device model availability is per-device and per-locale.** `AUTO` falls back to a
   single locale where models are absent.
5. **`EXTRA_ENABLE_LANGUAGE_SWITCH` requires the target model to be downloaded**, or the
   recognizer reports a switch error.
6. **Bluetooth SCO quality** materially affects recognition accuracy; this is a hardware
   constraint, not a software one.
7. **Compiling is not the same as running.** `AndroidSpeechRecognitionBackend.kt` now compiles
   cleanly against `android-34.jar`, but `android.jar` is a stub — every method body throws.
   The recogniser has never executed on a device or emulator, so callback ordering, timing and
   OEM variation are all untested.

---

## 8. Remaining issues

Items 1–2 were found by review and are **not fixed**. Items 7–8 are verification gaps rather
than code defects.

1. ~~`requestModelDownload` returned `true` optimistically.~~ **Fixed** — see §4.16 item 9.
2. **`StudySessionRepository.handleRecognitionFailure` re-listen path** calls
   `beginStt(purposeForState(state), card)` where `state` may not be a `Listening` or
   `WaitingForRating` variant. `purposeForState` handles this, but the re-listen should be
   gated on the state being one where re-listening is sensible at all.
3. **`AnswerEndpointProfile.SHORT` maps to `EndpointProfile.NORMAL_ANSWER`.** Intentional
   (SHORT shortens the watchdog, not the endpoint profile) but the name is misleading.
4. **No integration test covers the full repository → orchestrator → backend path.** The
   orchestrator and interpreter are tested against fakes; the repository's state transitions
   under real recognition outcomes are not.
5. **Segmented recognition and word-level timing are not implemented.** Neither is needed for
   the current turn-based flow; both would add capability gating without a demonstrated
   benefit at flashcard answer length.
6. **Recognition is turn-based by design.** There is no continuous open-microphone mode, and
   none should be added without an explicit power/privacy decision.
7. **Nothing has been run on a device or emulator.** `android.jar` is a stub, so compiling
   against it proves types and signatures, not behaviour.
8. **21 of the 53 new tests are unexecuted** (`SpeechRecognitionOrchestratorTest`), because
   `kotlinx-coroutines-test` could not be obtained. The orchestrator itself compiles.

---

## 9. Next recommended enhancement

**Close the remaining verification gap first.** §0 shows a real compiler and a real test
runner were assembled here and immediately found seven defects (§4.16), including two
user-visible safety bugs. That is direct evidence about what a full Gradle build would find
in the parts still uncompiled — above all `AndroidSpeechRecognitionBackend.kt`, which has
never been through a compiler at all.

Run, on a machine with the Android SDK:

```
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleRelease
```

Expect further errors in `AndroidSpeechRecognitionBackend.kt` and in the UI/repository layer
(Compose, DataStore, Navigation), none of which has been compiled here.

After that, the highest-value change is:

> ### A real-device recognition conformance harness.
>
> The remaining risk is not in the logic — it is in the assumption that recognizers behave as
> documented. Build a diagnostics-only screen that runs a fixed script (say a rating, a
> long answer, an Arabic command, a mid-turn language switch), records what the recognizer
> *actually* returned — callback order, timings, whether `EXTRA_CONFIDENCE_SCORES` was
> populated, whether biasing had any measurable effect, whether `onLanguageDetection` fired —
> and exports it as a report.
>
> This converts every guess in §7 into a measurement, on the specific devices the user
> actually carries. It reuses `RecognitionMetrics` and the existing diagnostics export, so it
> is a small increment, and it is the only way to know whether the medical-vocabulary biasing
> and API 34 language switching deliver anything in practice.

# GATE 00 — Repository Baseline & Green-Pre-Anki Lock

Date: 2026-09-22
Scope: baseline capture, failure classification, minimal baseline repairs,
repository hygiene, security audit, CI audit, documentation consistency.
**No AnkiDroid integration was started in this gate.**

---

## 1. Baseline identity

| Item | Value |
|---|---|
| Repository | `https://github.com/Drmusab/Study-Agent-Client` |
| Base branch / commit | `master` @ `50b54990ecc14ca9c8e0b474dd54e2b36afd72f9` |
| Base commit timestamp | 2026-09-22 10:40:18 +0300 |
| Base working tree | clean (verified with `git status` before any change) |
| Gate branch | `arena/01a0c90e-study-agent-client` (session-pinned branch, branched from the base commit; a project gate branch such as `gate/00-baseline-green-build` was not created because this working session is bound to the pinned branch) |
| Final gate SHA (pre-lock) | `250b45f` (see `git log 50b5499..HEAD` in the branch) |
| Git version | 2.39.5 |
| OS | Debian 12 (bookworm), x86_64, 2 CPU, 3.8 GiB RAM |
| Python | 3.11.2 (server/protocol tests) |
| JDK (sandbox) | **not installed** — no Java runtime exists in the execution environment |
| JDK required by repo | 17 (CI uses Temurin 17; `jvmTarget = "17"`; AGP 8.4.2 requires JDK 17+) |
| Gradle | 8.7 (wrapper; `gradle/wrapper/gradle-wrapper.properties`) |
| Android Gradle Plugin | 8.4.2 |
| Kotlin | 1.9.24 (Compose compiler extension 1.5.14) |
| compileSdk / targetSdk / minSdk | 34 / 34 / 26 |
| applicationId | `com.studyagent.client` (debug suffix `.debug`) |
| versionCode / versionName | 1 / `1.0.0` |

## 2. Validation matrix (honest status per Gate 00 rule: PASS / FAIL / BLOCKED_BY_ENVIRONMENT / NOT_RUN)

| Check | Result | Evidence / notes |
|---|---|---|
| `./gradlew clean` | **BLOCKED_BY_ENVIRONMENT** | No JDK/Gradle/Android SDK in sandbox; egress to services.gradle.org, dl.google.com, Maven Central is firewalled (only GitHub + PyPI reachable). See §10. |
| `./gradlew testDebugUnitTest` | **BLOCKED_BY_ENVIRONMENT** | Same as above. The *source-level* blockers that would have failed compilation first were found and fixed statically (§5, issues A1–A3). |
| `./gradlew lint` | **BLOCKED_BY_ENVIRONMENT** | Same as above. `lint { abortOnError = true }` (no wholesale silencing) — configuration is sound. |
| `./gradlew assembleDebug` | **BLOCKED_BY_ENVIRONMENT** | Same as above. |
| `./gradlew assembleRelease` | **BLOCKED_BY_ENVIRONMENT** | Same as above. Release uses **debug signing** (`signingConfigs.getByName("debug")`), minify off — development-only configuration, acceptable for compile validation, NOT for production distribution. |
| Server/protocol tests (`python3 server/test_contract.py`) | **PASS** | "All unit contract tests passed!" — run in sandbox (Python 3.11.2), both without and with `websockets` installed. |
| TTS mock-provider tests (`pytest server/test_tts_mock_provider.py`) | **PASS** | 28/28 passed (deterministic provider, audio normalization, bounded cache). |
| TTS protocol contract tests (`pytest server/test_tts_contract.py`) | **PASS** (after Gate 00 fix) | 22/22 passed. **Before the fix: FAIL at collection** — `ImportError: cannot import name 'TTS_STATE' from 'mock_pc_agent'` (issue B1). |
| TTS live-provider tests (`server/test_tts_providers_live.py`) | **NOT_RUN (by design)** | Requires real `OPENAI_API_KEY` / `ELEVENLABS_API_KEY`; all 4 tests skip gracefully without keys. Not a CI gate; never run in CI. |
| Mock agent end-to-end loop (`test_client.py` vs `mock_pc_agent.py`) | **PASS** | hello → welcome (with TTS capabilities) → start_session → question → submit_answer → evaluation → rate_card → rating_saved → next question. The client script needed a 2-frame handshake fix (issue B2). |
| Server `py_compile` (all `server/*.py`, `server/tts/*.py`) | **PASS** | Mirrors the CI compile check. |
| Instrumented tests (`connectedDebugAndroidTest`) | **NOT_RUN** | No emulator/device in sandbox. Deliberately not a PR gate (emulator cannot validate Bluetooth routing, real TTS engines or microphones — see `docs/REAL_DEVICE_TEST_MATRIX.md`). |
| GitHub Actions CI | **INFRASTRUCTURE BLOCKED** | Every run on `master` (≥ 28 runs since 2026-09-20) and on the gate branch fail with **zero steps executed in ~2 s**. Job annotation (verbatim): *"The job was not started because your account is locked due to a billing issue."* — run `35700778737` (master @ base SHA) and run `35728104833` (gate branch @ `250b45f`). The workflow YAML parses correctly (all jobs, including the new TTS job, are created and scheduled). **This is a GitHub account-level billing lock, not a source, test or workflow defect.** |

**No `NOT_RUN` / `BLOCKED_BY_ENVIRONMENT` result was converted to PASS.**

## 3. What the sandbox could and could not verify

- **Could:** run the entire deterministic Python server/protocol suite; statically analyze every Kotlin file for unresolved references across the whole TTS subsystem; audit `.gitignore`, tracked files, secrets, manifests, docs, CI workflow; push and observe GitHub Actions.
- **Could not:** execute any Gradle task (no JDK 17, no Android SDK, no network to the toolchain/dependency hosts). Therefore Android compilation, unit tests, lint and packaging are verified **only by static analysis** in this gate and remain to be confirmed by a green CI run once the account lock is lifted (§10).

## 4. Confirmed problems found (classified)

| ID | Class | Severity | Component | Evidence | Root cause | Fix (Gate 00) | Regression evidence |
|---|---|---|---|---|---|---|---|
| A1 | BASELINE-BUILD | **P0** | `core/voice/tts/RemoteTtsTransport.kt` | `ProviderBackendRouter.kt:161` and `SpeechBackendRouter.kt:100` construct `RemoteProviderInfo(..., supportsPitch = true)`; the data class had no `supportsPitch` parameter → `compileDebugKotlin` failure (all Android targets). | Half-landed cloud-TTS feature: `SpeechBackendCapabilities.supportsPitch` was added but `RemoteProviderInfo` was not updated to match. | Added `val supportsPitch: Boolean = true` to `RemoteProviderInfo`. | Static cross-check of every `RemoteProviderInfo` construction site in the module. Android compile confirmation pending CI unlock. |
| A2 | BASELINE-BUILD | **P0** | `core/voice/tts/SpeechModels.kt` | `RemoteSpeechBackend.mapError` references `SpeechErrorCode.{PROVIDER_UNAVAILABLE, PROVIDER_NOT_CONFIGURED, PROVIDER_AUTH_FAILED, PROVIDER_RATE_LIMITED, PROVIDER_QUOTA_EXCEEDED, MODEL_UNAVAILABLE, CLOUD_STREAM_FAILED}` — none existed → compile failure. | Same half-landed feature: cloud error codes were defined on the server (`server/tts/models.py`) and in `RemoteTtsException`, but never mirrored into the Android enum. | Added the 7 cloud members (stable contract mirror). Verified no exhaustive `when` without `else` over the enum exists anywhere in `src/main`/`src/test`, so adding members breaks nothing. | Same as A1. |
| A3 | BASELINE-BUILD | **P0** | `core/voice/tts/TtsSettings.kt` | `ProviderBackendRouter` reads `cfg.ttsProvider`, `cfg.cloudTtsCache`; `RemoteSpeechBackend` reads `cfg.cloudTtsCache`, `cfg.cloudTtsFallback`, `openaiModelId`, `elevenlabsModelId` — none existed on `TtsSettings` → compile failure. | Same half-landed feature: cloud settings fields were consumed but never declared (and never persisted in `AppSettings`). | Added the 5 fields with defaults that reproduce the current local-only behavior exactly (`ttsProvider = ANDROID` etc.); `toTtsSettings()` deliberately unchanged — persisting/surfacing cloud settings is later-Gate work. Cloud classes remain **dormant** (not wired into `AppContainer`, UI or persistence), so runtime behavior is unchanged. | Static cross-check of all `TtsSettings` consumers. |
| B1 | BASELINE-TEST | **P1** | `server/mock_pc_agent.py` | `pytest server/test_tts_contract.py` → `ImportError: cannot import name 'TTS_STATE' from 'mock_pc_agent'` — the entire 22-test TTS contract suite (the documented CI gate for the TTS protocol) could not even be collected. The `server/tts/` package (providers, stream manager, media server) was fully implemented but never wired into the agent. | The TTS subsystem commits landed the `tts` package + contract tests without the agent-side wiring (`TTS_STATE`, `initialize_tts`, `tts_*` WS handlers, media-plane boot). | Wired the existing `tts` package into the mock agent: `TTS_STATE` + `initialize_tts()` + handlers for `tts_capabilities_request` / `tts_voices_request` / `tts_synthesize` / `tts_cancel` / `tts_test_provider` (in_reply_to correlation, typed errors, `AUTH_REQUIRED` gate), welcome advertises `tts*` capabilities, CLI flags `--no-tts/--mock-tts/--tts-http-port/--tts-fail`. | `pytest server/test_tts_contract.py`: collection error → **22/22 passed**; `server/test_contract.py` still passes; end-to-end mock loop passes with TTS advertised in `welcome.capabilities`. |
| B2 | BASELINE-TEST | P2 | `server/test_client.py` | End-to-end run crashed with `KeyError: 'session_id'`: the agent sends a legacy `capabilities` frame immediately after `welcome`; the client treated it as the `session_started` reply. | Manual test client written against a single-frame hello response. | Client now skips non-`session_started` frames. | Full end-to-end loop prints `SUCCESS: End-to-end study protocol cycle verified!` |
| C1 | BASELINE-REPOSITORY | P2 | `.gradle/` (17 files) | `git ls-files` listed `.gradle/8.7/executionHistory/executionHistory.bin` (2 MB) etc. — local Gradle build-cache binaries were tracked although `.gitignore` already contains `.gradle/`. | The import commit (`50b5499`) committed the local `.gradle/` directory. | `git rm -r --cached .gradle` (index only; working files untouched; wrapper `gradlew`/`gradle/wrapper/*` remains tracked). Verified `git ls-files .gradle/` is now empty. | `git ls-files` audit. |
| C2 | BASELINE-REPOSITORY | P3 | `.gitignore` | Missing `.env` / `.env.*` (the TTS subsystem reads `OPENAI_API_KEY`/`ELEVENLABS_API_KEY` from the environment — a committed `.env` would be a credential leak), missing `*.jks` (`*.keystore` was already ignored), missing `.pytest_cache/`. | Ignore list predated the Python TTS tooling and standard env-file conventions. | Appended the four patterns (CRLF style preserved; file previously ended without trailing newline — now fixed). | `git status` clean after running the Python suites. |
| D1 | BASELINE-CI | **P1** | GitHub Actions (all jobs) | ≥ 28 consecutive failed runs (push + nightly, 2026-09-20 → 2026-09-22), every job failing with **0 steps** within ~2 s. Annotation: *"The job was not started because your account is locked due to a billing issue."* | **GitHub account-level billing lock** (owner: `Drmusab`). Not a source, test, or workflow defect: the run parses, all jobs (incl. the new TTS job) are created and scheduled, then never start. | **No code change** (Gate 00 rule 21: zero-step failures must not be "fixed" by touching source). Requires owner action: resolve the account billing/lock. Once lifted, re-run CI on the gate branch — the pipeline is designed correctly (§7). | Reproduced on the gate branch: run `35728104833` shows the identical zero-step failure + annotation, proving the new workflow parses and the failure is environmental. |
| D2 | BASELINE-CI | P2 | `.github/workflows/android-ci.yml` | The TTS contract suite (`test_tts_contract.py`, `test_tts_mock_provider.py`) — explicitly called "the CI gate for the whole TTS protocol" by its own docstring — was **not executed by any job** (the protocol job runs only stdlib `test_contract.py`; `py_compile` cannot catch import-time breakage, which is exactly how B1 survived). | CI coverage lagged the new subsystem. | Added a `TTS contract tests` job (Python 3.11, `pytest` + `pytest-asyncio` + `websockets<14`; hermetic in-process agent with mock providers; no `continue-on-error`). | Job is created/scheduled on push (visible in run `35728104833`); the identical command passes locally 50/50. |
| E1 | BASELINE-DOCS | P2 | `docs/PROTOCOL.md` | The remote TTS wire contract (5 control-plane messages, media plane, error codes) existed only in code/tests; `PROTOCOL.md` had zero TTS mentions. | Subsystem landed without protocol documentation. | New section 21 "Remote TTS Subprotocol" (capabilities, messages, media plane, stable codes, credential hygiene); version history updated. | Cross-checked field-by-field against `server/tts/`, the contract tests, and `RemoteTtsTransport.kt`. |
| E2 | BASELINE-DOCS | P2 | `docs/TTS_ARCHITECTURE.md` | Stated "no cloud TTS, no bundled engine" — directly contradicted by the current tree; its `SpeechErrorCode` table listed only the 13 local members. | Doc predates the cloud-TTS work. | Replaced the claim with an accurate baseline note (subsystem exists but is **dormant**; local engine remains the active path) and extended the error-code table. | Matches code + Gate 00 audit. |
| E3 | BASELINE-DOCS | P3 | `server/tts/__init__.py`, `server/tts/models.py` | Docstrings referenced `docs/CLOUD_TTS.md`, which does not exist (dangling reference). | Doc renamed/never created. | References now point to `docs/PROTOCOL.md` §21. | Grep: no remaining `CLOUD_TTS` references. |

### Cloud TTS internal-consistency audit (Gate 00 rule 15)

- All referenced models exist after A1–A3: `TtsProvider` (ANDROID/OPENAI/ELEVENLABS, `label`, `storageId`), `CloudTtsFallbackPolicy` / `CloudTtsCachePolicy` / `CloudTtsQualityProfile` / `CloudLanguageStrategy`, `CloudTtsCache` + `CloudTtsCacheKeys.key(...)`, `CloudTtsStyle.languageCode(...)`, `SpeechVoice`, `PlaybackStats`, `StreamingSpeechPlayer`, `EngineStatus`, `BackendUtterance`, `AppSettingsPolicy` constants, `SpeechError(requiresUserAction)` / `notInitialized()`.
- **Protocol messages required by cloud TTS exist on both sides** after B1: Android expects `tts_capabilities` / `tts_voices` / `tts_stream` / `tts_cancelled` / `tts_provider_test` with the exact field names served by the agent (verified against the 22 contract tests).
- **AppContainer wiring is coherent**: `DefaultSpeechOrchestrator(engine = ...)` (local only) — no dangling references to the router classes. The cloud layer (`ProviderBackendRouter`, `RemoteSpeechBackend`, `SpeechBackendRouter`, `AndroidOnlyBackendRouter`) is **dormant by construction**: unreferenced by DI, UI and tests. There is **no** production `WebSocketRemoteTtsTransport` implementation yet (the interface + data types exist; a docstring claims one — see deferred work F5).
- Tests corresponding to production implementation: local-TTS pipeline is well tested (see §9); the cloud layer has **no** JVM tests yet (blind spot, deferred — it is dormant).

## 5. Repository hygiene

- **Tracked generated files:** `.gradle/` (17 binaries) — removed from index (C1). `app/build/`, `build/` were never tracked. `server/__pycache__` / `.pytest_cache` were never tracked; `.pytest_cache/` added to `.gitignore` (C2).
- **`.gitignore` changes:** appended `.env`, `.env.*`, `*.jks`, `.pytest_cache/`; existing rules (Gradle, IDE, keystores with `!debug.keystore`, Python caches, logs) preserved. CRLF line endings retained to keep the diff minimal (noted as cosmetic debt F7).
- **Temporary artifacts:** none user-owned found. The gate's own `.pytest_cache` (created while running the suites) was removed from the work tree; no destructive git commands were used.
- **Tracked file count:** 323 → 306 (17 `.gradle` removals + 2 new doc files added later).

## 6. Security

- **Secret scan (working tree + full history, `git log --all`):** no real credentials found. Patterns checked: `sk-…`, `xi-api-…`, `AKIA…`, `ghp_…`, long base64 blobs, `Bearer <token>` literals, `password=`/`secret=` assignments, `*.pem/.p12/.keystore/.jks/.env/local.properties` in any added file. Only test fakes (`"unit-test-secret-999"`, `fake-key-abcdef`, `wrong-token`) and detection regexes in `AppLogger.kt`/`DiagnosticsExportPrivacyTest.kt` appear — all deliberately fake.
- **No credential file has ever been committed** in this repository's history.
- **Backup/security posture:** `secure_prefs.xml` (encrypted token store) is excluded from cloud backup *and* device transfer (`backup_rules.xml`, `data_extraction_rules.xml`) — correct. Cleartext traffic is allowed by design (LAN `ws://` to a user-configured agent; documented in `network_security_config.xml`); `usesCleartextTraffic="true"` is redundant when an NSC is present but not contradictory (F8).
- **TTS credential hygiene:** provider keys stay on the PC Agent (environment variables); the protocol sends only capabilities/codes; the single shared credential travels in a Bearer header only; the TTS contract suite asserts no secret material appears in any protocol frame (`_assert_no_secrets`) — 22/22 passing.

## 7. CI analysis (source vs tests vs workflow vs infrastructure)

- **Workflow:** `.github/workflows/android-ci.yml` is well-formed (GitHub parsed it and scheduled all 5 jobs on the gate branch) and well-designed: JDK 17 Temurin, `gradle/actions/setup-gradle@v3` caching, `testDebugUnitTest` → `lint` → `assembleDebug` → `assembleRelease` in one blocking job, no `continue-on-error` on any gate, nightly chaos sweep (seeds 100–1000), emulator instrumented job on schedule/dispatch only (deliberately not a PR gate, with the reasoning documented), artifacts uploaded on failure.
- **Triggers:** push to `master` and `arena/**`, PRs to `master`, nightly cron, workflow_dispatch.
- **Permissions:** `contents: read` (minimal).
- **Failure classification (explicit, per rules 20/21/45):** every observed CI failure — 28+ on `master` since 2026-09-20 and 1 on the gate branch — is an **infrastructure failure** (GitHub account billing lock; jobs die before step 1). **No evidence exists that any CI failure came from source, tests, or the workflow.** Zero-step rule applied: no Kotlin code was modified in response to CI results.
- **CI target (rule 23):** satisfied — the existing pipeline already implements the desired minimum (checkout → JDK → Gradle setup/cache → unit tests → lint → debug → release); Gate 00 only *added* the missing TTS contract job.
- **Required owner action:** resolve the billing/lock on the GitHub account, then re-run on the gate branch. Until then, CI cannot prove the Android build (that is exactly why this gate is CONDITIONALLY BLOCKED, not PASS).

## 8. Android static audit (no toolchain available — findings by inspection)

- **Manifest:** permissions consistent with features (`RECORD_AUDIO`, `BLUETOOTH` ≤30 + `BLUETOOTH_CONNECT`, `FOREGROUND_SERVICE` + typed `microphone|mediaPlayback` matching the declared service type, `POST_NOTIFICATIONS`, `WAKE_LOCK`). `<queries>` for `RecognitionService` present and documented (targetSdk 34 package visibility). No contradictions found.
- **Release configuration:** debug signing, `isMinifyEnabled = false`, no resource shrinking — **development-only**; documented as not production-distribution-ready. No production key was created or committed (none exists).
- **SDK levels:** compileSdk 34 / targetSdk 34 / minSdk 26 — consistent across build files; modernization deferred (F2).
- **Dependency inventory (observation only, no upgrades performed):**

  | Area | Version | Note |
  |---|---|---|
  | Compose BOM | 2024.06.00 | OK for Kotlin 1.9.24 |
  | Activity Compose | 1.9.0 | |
  | Lifecycle | 2.8.3 | |
  | Navigation Compose | 2.7.7 | |
  | Coroutines | 1.8.1 | |
  | Serialization JSON | 1.6.3 | |
  | OkHttp | 4.12.0 (+ logging, mockwebserver) | |
  | DataStore Preferences | 1.1.1 | |
  | Security Crypto | 1.1.0-alpha06 | **alpha** — candidate for stabilization follow-up (F3) |
  | Testing | JUnit 4.13.2, coroutines-test 1.8.1, Turbine 1.1.0, MockWebServer 4.12.0, org.json 20240303, androidx.test 1.6.x | |

  No evidence of vulnerable coordinates found in this audit; a dependency-update pass is explicitly deferred.

## 9. Test inventory (existing coverage, grouped)

- **Study:** `StudyStateMachineTest`, `StudyReducerTest`, `StudySessionHappyPathTest`, `StudySessionSimulationTest`, `StudySessionEnduranceTest`, `StudyAgentChaosTest`, `NetworkChaosTest`, `ReconnectAtEveryPhaseTest`, `SessionIdempotencyTest`, `SessionInvariantTest`, `SessionReconstructionTest`, `ConnectionRestoreTest`, `PhoneModeMachineIntegrationTest`, `DefaultStudySessionRepositoryTest`, `SpokenCommandRouterTest`
- **Voice:** `VoiceCommandManagerTest`, `StudyVoiceTurnGateTest`
- **TTS:** `SpeechOrchestratorTest`, `SpeechQueueTest`, `SpeechChunkerTest`, `SpeechTextPreprocessorTest`, `MedicalPronunciationProcessorTest`, `MixedLanguageSegmenterTest`, `TtsVoiceSelectorTest`, `VoiceHandoffControllerTest`, `AcousticGapPolicyTest`
- **STT:** `SpeechRecognitionOrchestratorTest`, `RecognitionPolicyAndVocabularyTest`, `SttReliabilityChaosTest`, `VoiceCommandInterpreterTest`
- **Audio:** `PhoneModeLoopTest`, `SelfEchoAndPhoneMetricsTest`, `StudyAudioModeResolverTest`, `StudyAudioPreferencesMappingTest`, `StudyAudioRouteCoordinatorTest`
- **Network/Protocol:** `ProtocolJsonTest`, `ProtocolHandshakeTest`, `ProtocolFuzzTest`, `AuthenticationTest`, `IdempotencyTest`, `ConnectionLifecycleTest`, `ConnectionStateTest`, `ReconnectControllerTest`, `RequestCoordinatorTest`, `WebSocketIntegrationTest`, `FakeAgentConnectionTest`, `FakeAgentManagementTest`, `ProfileValidatorTest`
- **Persistence:** `AppSettingsPreferencesCodecTest`, `SettingsPersistenceRegressionTest`, `CapabilityStoreTest`, `ServerProfileTest`, `SessionStartConfigTest`, `StudyPresetTest`, `StudyControlRepositoryTest`, `ManagementTestDoubles`
- **Dashboard/Control/Diagnostics:** `DashboardModelsDeserializationTest`, `DashboardRepositoryTest`, `DashboardUiMapperTest`, `FreshnessPolicyTest`, `DiagnosticsExportPrivacyTest`
- **Cloud TTS:** **none (blind spot — code is dormant; deferred F5)**
- **UI/instrumented:** 7 instrumented classes (app shell, design system, diagnostics export, settings persistence, study controls, LAN cleartext, main-activity smoke) — device/emulator only, not PR gates.

### High-risk invariant smoke check (rule 38)

| Invariant | Represented by (existing, not duplicated) |
|---|---|
| One answer submission per turn | `SessionInvariantTest` ("at most one answer per turn"), `SessionIdempotencyTest` ("answering twice sends one answer") |
| One rating per turn | `SessionInvariantTest` ("at most one rating per turn"), `SessionIdempotencyTest` ("rating twice sends one rating") |
| TTS/STT do not overlap | `StudyVoiceTurnGateTest`, `VoiceHandoffControllerTest` ("speech still playing means the microphone never opens") |
| Stale callbacks do not mutate new turns | `SessionInvariantTest` ("only active epoch may mutate", "evaluation belongs to current turn"), `ReconnectAtEveryPhaseTest`, `StudyAgentChaosTest` |
| Route loss does not submit partial transcript | `StudyAudioRouteCoordinatorTest`, `PhoneModeLoopTest`, `PhoneModeMachineIntegrationTest`, `SessionInvariantTest` ("paused cannot open STT") |
| Connection recovery does not duplicate mutations | `ConnectionRestoreTest`, `ReconnectAtEveryPhaseTest`, `IdempotencyTest`, `ConnectionLifecycleTest` |
| Settings round-trip survives restart | `SettingsPersistenceRegressionTest`, `AppSettingsPreferencesCodecTest` |

**Blind spots (documented, not implemented here):** cloud-TTS JVM tests; instrumented TTS/STT hardware paths (require real device, see `docs/REAL_DEVICE_TEST_MATRIX.md`).

## 10. Known baseline limitations & environment blockers (exact, per rule 46)

1. **G1 — CI infrastructure:** the GitHub account (`Drmusab`) is **locked due to a billing issue**; every Actions job fails at zero steps with that annotation (runs `35700778737`, `35728104833`, and 28+ others). **Owner must resolve the billing/lock**, then re-run CI on the gate branch. Nothing in the repository can fix this.
2. **G2 — sandbox toolchain:** the Gate 00 execution environment has no JDK and cannot download one (egress allowlist: GitHub + PyPI only; services.gradle.org / dl.google.com / Maven Central / Debian mirrors unreachable). All Gradle tasks (`clean`, `testDebugUnitTest`, `lint`, `assembleDebug`, `assembleRelease`) are therefore `BLOCKED_BY_ENVIRONMENT` locally.
3. Android results in this document are the product of **static analysis** of a codebase that previously could not compile (A1–A3 proven by symbol cross-reference); a green CI run is the final confirmation still owed.

## 11. Deferred work (explicitly NOT done in Gate 00)

- **F1 — AnkiDroid integration** (all of it: AnkiBackend, AddContentApi/FlashCardsContract, permissions, models, screens, `implementation(project(":api"))` or Maven dependency) — starts at GATE 01.
- **F2 — SDK modernization** (compileSdk 35+, targetSdk 34/35 review) — separate change with device-matrix validation.
- **F3 — Dependency modernization** (incl. stabilizing `androidx.security:security-crypto` off the alpha line) — bulk upgrades forbidden in this gate; inventory in §8.
- **F4 — Release signing & minification strategy** (production key, `isMinifyEnabled`, R8/ProGuard rules) — current debug-signing release config is documented, not changed.
- **F5 — Cloud TTS activation**: production `WebSocketRemoteTtsTransport` implementation (only the interface + models exist; a docstring in `RemoteTtsTransport.kt` claims the implementation exists — correct when the class lands), AppContainer wiring, `AppSettings` persistence + Settings UI for `ttsProvider`/fallback/cache/model fields, and JVM tests for the cloud layer.
- **F6 — Real-device validation** (Bluetooth routing, TTS engines, STT, phone mode) — `docs/REAL_DEVICE_TEST_MATRIX.md`.
- **F7 — Cosmetic debt:** CRLF line endings in `.gitignore`; redundant `usesCleartextTraffic` attribute; `RemoteTtsTransport.kt` docstring over-claim (see F5).
- **F8 — CI note:** GitHub will migrate `ubuntu-latest` to Ubuntu 26 on 2026-10-19 (informational annotation on every run); pin `ubuntu-24.04` in a later CI change if the migration breaks the toolchain.

## 12. Final Gate verdict

**GATE 00: CONDITIONALLY BLOCKED**

- All repository-side baseline work is **complete and verified locally**: the two P0 source-level compile blockers and the P1 protocol-suite blocker are fixed with regression evidence; server/protocol deterministic tests are green (53 tests + end-to-end mock loop); hygiene, security, CI, manifest, docs and dependency audits are done; the branch is pushed with 6 logical commits + lock.
- The two remaining required conditions cannot be satisfied from inside the repository or this environment:
  1. **CI verification** is blocked by the owner-account billing lock (infrastructure; evidence in §7).
  2. **Local Gradle verification** is blocked by the sandbox toolchain (evidence in §10/G2).
- **Gate 00 becomes PASS the moment** a GitHub Actions run on this branch completes green (unit tests, lint, assembleDebug, assembleRelease, contract tests, TTS contract tests) after the account lock is lifted — no further source changes are anticipated; if that run fails for *source* reasons, that is a Gate 00 re-open with the run's log as evidence.

> Is the repository safe to use as the starting baseline for GATE 01? **YES, with the condition above** — the base commit is known, every known source-level blocker is fixed with evidence, the failure classification is explicit, and no failure is hidden. Do **not** merge GATE 01 work on top of this branch until at least one green CI run exists on it (or on master after the owner's billing fix).

# GATE 02 — AnkiDroid API Dependency, Detection & Permission Foundation

Date: 2026-09-22
Branch: `arena/01a0c996-study-agent-client` (session-pinned; all GATE 02 work in the working tree
until the gate-lock commit)
Scope: make Study-Agent answer **truthfully** whether it can talk to AnkiDroid — installed?
provider reachable? permission held? collection usable? API/provider spec supported? — and expose
one application-scoped health owner for that answer. **No deck/card read, no review, no rating, no
render, no mutation, no change to the PC study path.**

---

## 1. Gate verdict

**GATE 02: PASS on deliverables; Android compile/test confirmation remains
`BLOCKED_BY_ENVIRONMENT`, identical to GATE 00/01.**

What that means precisely: every deliverable in the gate brief exists in source, the design
decisions are recorded and cross-referenced, and the verification that can be performed in this
sandbox (static review, boundary greps, server-side suites, 73 new JVM test methods written) has
been performed. **No green Android build is claimed** — the sandbox has no JDK, no Android SDK and
no network route to a toolchain (see §2). The first CI run is the first real compile signal.

| Artifact | Path |
|---|---|
| Integration reference (dependency, authority/provider, permission, spec, states, recovery table, limitations) | `docs/ANKIDROID_INTEGRATION.md` |
| Architecture contract update (detection/permission/provider/readiness; obligations on GATE 03+) | `docs/ANKI_INTEGRATION_ARCHITECTURE.md` §25 (+ §15.1, §22, §23, §24 status) |
| Implementation | `app/src/main/java/com/studyagent/client/data/anki/ankidroid/` (10 files) |
| Tests (JVM) | `app/src/test/java/com/studyagent/client/anki/ankidroid/` (7 files, 73 tests) |
| Manifest (release + debug) | `app/src/main/AndroidManifest.xml`, `app/src/debug/AndroidManifest.xml` |

## 2. Environment validation matrix (honest status, GATE 00/01 conventions)

| Check | Result | Evidence / notes |
|---|---|---|
| `python3 server/test_contract.py` | **PASS** | Re-run after all gate edits: "All unit contract tests passed!" |
| `pytest server/test_tts_contract.py`, `server/test_tts_mock_provider.py` | **NOT RUN in this container** | pytest/pytest-asyncio/websockets are absent and there is no network egress to install them. GATE 01 recorded 22/22 + 28/28 on these files; **no server file was touched by GATE 02**, so that evidence stands for unchanged code. |
| `./gradlew testDebugUnitTest` | **BLOCKED_BY_ENVIRONMENT** | No JDK/Android SDK (GATE 00 §10-G2). The 73 new test methods are written but have never been compiled or executed. |
| `./gradlew lint` | **BLOCKED_BY_ENVIRONMENT** | Same. |
| `./gradlew assembleDebug` / `assembleRelease` | **BLOCKED_BY_ENVIRONMENT** | Same; CI runs all four in the `unit-tests` job once billing/CI is green. |
| `./gradlew --stop` / `clean` | **BLOCKED_BY_ENVIRONMENT** | No Gradle in the image. |
| Regression suites (Study session, Connection, Settings, TTS, STT, Audio) | **BLOCKED_BY_ENVIRONMENT** | Container wiring changed (`AppContainer`, `SettingsViewModel`, `DiagnosticsRepository` ctor, `MainActivity`), so these must be run in CI — see §6. Nothing here claims they pass. |
| AnkiDroid instrumented test | **NOT RUN, not claimed** | `AnkiDroidIntegrationInstrumentedTest` (5 tests, device-only, nightly/on-demand job) is written to need no AnkiDroid and to skip its launch assertion when one is installed. No AnkiDroid environment existed where this gate was written. |
| New-Kotlin static self-review | **PASS (static only)** | Read-through of every new/changed file; imports vetted against the sources they reference; `AnkiDroidIntegrationIsolationTest` scans the boundary; no unused-import/name-conflict signal found. This is not a compiler. |

### 2.1 What the static self-review actually covered

Because no compiler ran (§2), the review below was done mechanically wherever a machine can be
trusted, and by reading where it cannot. It is reported as **what was checked**, not as a
substitute for the first CI run:

| Check | Method | Result |
|---|---|---|
| Every project import in the new tests resolves to a declaration that exists (including the `statusCode`/`isReadyForReview` extensions, which are not visible to a naive name scan) | automated index of declarations in `app/src/**` compared against each `import` | clean |
| Every named constructor argument and every interface override used by the tests matches the production signature (probe/permission/detector/check seams, repository, snapshot, facts, failure) | automated signature/argument extraction + read | clean |
| Every enum constant referenced by tests exists (`AnkiDroidFailureCategory`, `AnkiDroidFailureEvidence`, `AnkiDroidOperationStage`, `AnkiDroidProviderSpecSource`) | automated comparison against the declared constants | clean |
| All ten `AnkiDroidIntegrationIsolationTest` scans would pass against the current tree (layer naming, debug-authority containment, `android.*` only in the two platform files, import allow-list, no writes/forbidden tech, one `AnkiAvailability`, manifest minimalism, debug-manifest-only endpoint) | the same scans re-implemented and executed over the real sources/manifests | 10/10 pass |
| Every `when` over `AnkiAvailability`/`AnkiError` stayed exhaustive after the amendments | grep + read of all call sites (`asCommitFailureClass`, `AnkiDroidGuidance.of`, `ankiDroidStatusLabel`) | clean |
| The GATE 01 domain contract test is unaffected by the new availability variants (no exhaustive `when`, no state-set assumptions) | read of `AnkiArchitectureContractTest.kt` | clean |
| Module discovery in the isolation test cannot silently scan nothing | walked the parent chain from both plausible working directories (module dir and repo root) | fixed to accept both before erroring |
| No warning-as-error risk from opt-in APIs (`UnconfinedTestDispatcher`, `advanceUntilIdle`) | `app/build.gradle.kts` opts the module into `kotlinx.coroutines.ExperimentalCoroutinesApi` for all compilations | clean |

What this cannot cover: type inference at a distance, overload resolution, Compose recomposition
correctness, resource/manifest-merger behaviour, lint. Only CI (or a machine with a JDK) can.

## 3. Decision of record: no compile-time AnkiDroid artifact

Detection talks to the **public ContentProvider contract** (`FlashCardsContract` +
`CardContentProvider`) and owns its constants with recorded provenance. Rationale and the full
channel evaluation (`com.ichi2.anki:api:2.0.0` unpublished; JitPack unreliable per tag —
`v2.24.1` never built; the older `api-v1.1.0` aar has no provider-spec constant;
`com.ichi2.anki` absent from Maven Central and JitPack's group path) are in
`docs/ANKIDROID_INTEGRATION.md` §2, including the exact snippet to adopt the official artifact
later. Consequences: we declare the permission ourselves, we resolve the provider exactly as
`AddContentApi.getAnkiDroidPackageName()` does, and no AnkiDroid code or class is linked.

## 4. What was built (deliverable → code)

| Brief requirement | Where |
|---|---|
| Provider/package discovery, spec detection, permission visibility, reachability (no deck/card query) | `AnkiDroidDetector.kt`, `AndroidAnkiDroidProbe.kt`, `AnkiDroidPermissionManager.kt` |
| Detection result model without raw Android objects | `AnkiDroidHealth.kt` (`AnkiDroidProviderFacts`, `AnkiDroidDetectionResult`) |
| Explicit availability states (no boolean) | `core/anki/AnkiAvailability.kt` (+`Checking`, +`ProviderUnavailable`) |
| API host/provider **spec** tracked separately from the app version | `AnkiDroidApiContract.kt` (`AnkiDroidProviderSpec`: min 1, validated 2, implicit 1), `AndroidAnkiDroidProbe` |
| One place that checks the permission | `AndroidAnkiDroidPermissionManager` (behind `AnkiDroidPermissionManager`) |
| Health check + snapshot (checkedAt, availability, spec, package, permission, collection) | `AnkiDroidHealthCheck.kt`, `AnkiDroidHealthSnapshot` |
| Single authoritative health owner (`StateFlow`) | `AnkiDroidHealthRepository.kt` (+ `AnkiDroidHealthPublicationGuard`), built once in `AppContainer` |
| Bounded read-only probe, non-mutating, cheap | `AndroidAnkiDroidProbe.probeCollection` (`selected_deck`, 1 column, 1 row) |
| `suspend fun refresh()` + bounded retry/refresh policy | `AnkiDroidHealthRepository.refresh()` / `requestRefresh()` / `onAppForeground()` |
| Single-flight, last-write-wins/stale protection | Mutex + coalescing + monotonic publication guard |
| Typed actionable error model | `AnkiDroidErrors.kt` (10 categories, evidence tokens, classifier) |
| Debug/technical diagnostics snapshot | `DiagnosticsRepository.ankiDroidDiagnosticsRows()` + export section "AnkiDroid integration" |
| Settings "Anki integration" section + launch helper | `SettingsScreen.kt`, `SettingsViewModel.kt`, `AnkiDroidLauncher.kt` |
| Manifest permission + minimal `<queries>`; debug gating | `AndroidManifest.xml`, `src/debug/AndroidManifest.xml` |
| No store lock-in | No artifact, no store check, distribution-neutral launch/resolve |

## 5. Validation scenarios (gate §112) — result per scenario

Evidence column: **JVM** = asserted by an automated test that runs in the normal CI unit-test job
(written, not executed in this sandbox — §2); **static** = enforced by the isolation scan.

| # | Scenario | Implemented behaviour | Evidence |
|---|---|---|---|
| 1 | No AnkiDroid installed | All endpoints resolve to nothing, package invisible → `NotInstalled`; a normal state, not a failure; initial state is `Checking`, so startup is never blocked and nothing crashes | JVM: `nothing installed is a normal state`, `no non-ready state is ever ready for review` |
| 2 | AnkiDroid present, provider missing | Package visible, no authority resolves → `ProviderUnavailable` (`PACKAGE_PRESENT_PROVIDER_MISSING`); package presence alone is never `Ready` | JVM: `package present without a provider is provider unavailable, never ready` |
| 3 | Permission unavailable | Permission is resolved **before** the probe → `PermissionRequired`, zero probe calls; a `SecurityException` from the provider is classified, never swallowed | JVM: `permission missing short-circuits before any provider read`, `a provider refusing despite a granted permission…`, classifier tests |
| 4 | Collection not initialized | Only a **documented** setup signature yields `CollectionNotInitialized` (+`collectionReady=false`); an undocumented `IllegalStateException` becomes a visible `Fault` | JVM: `a documented setup failure maps to collection not initialized`, `an undocumented IllegalStateException becomes a fault…`. Note: v2.24.1's shipping `FlashCardsContract` carries no such text (main-branch KDoc only) — the mapping is signature-based and must stay so |
| 5 | Supported, ready | provider + expected package + enabled + permission + spec ≥ 1 + the collection answered the bounded read → `Ready(AnkiCapabilities.NONE)`; `isReadyForReview` stays false | JVM: `provider, permission and an answering collection are ready` |
| 6 | Unsupported API / provider spec | spec < minimum → `Unsupported` (`SPEC_BELOW_MINIMUM`) with no permission check and no probe; a provider rejecting our query shape → `Unsupported` / `CONTRACT_MISMATCH` | JVM: `a provider spec below the minimum is unsupported and no probe is issued`, classifier contract-mismatch test |
| 7 | Return from background after Anki setup | `MainActivity.onStart` → `onAppForeground()` (debounced, single-flight); the state re-derives without an app restart | JVM: `installing AnkiDroid and returning to the app recovers without a restart`; on a device: `AnkiDroidIntegrationInstrumentedTest`'s bounded double-refresh + foreground-trigger test. *No device was available here, so the device half is written but unrun* |
| 8 | Multiple refresh calls | One check at a time (Mutex); fire-and-forget coalesces; only the newest request may publish; no crash, no stale overwrite, no leaked coroutine | JVM: `concurrent refresh requests never overlap on the provider`, `a superseded result can never be observed`, `repeated refreshes leak no coroutines`, guard unit test |
| 9 | PC disconnected while AnkiDroid ready | Anki health has no dependency on connection/PC state (the layer imports nothing from those packages) and the AnkiDroid section reports "Not checked (PC Agent state)" for PC states | static: `AnkiDroidIntegrationIsolationTest` import allow-list; guidance mapping is exhaustive in `AnkiDroidGuidance.of` |
| 10 | PC ready while AnkiDroid missing | Same independence plus startup containment: `Checking` → `NotInstalled`, no exception can reach the app, the PC study path is untouched | JVM: `before the first check the state is checking, never ready and never failed`, `a defect in the check becomes a fault snapshot instead of a crash`; static: allow-list scan |

Required coverage from the brief → test mapping:

| Required coverage | Test |
|---|---|
| `SecurityException` mapping | `AnkiDroidFailureClassifierTest` (`SecurityException is permission denied…`, `…wins over any other signature…`), detector tests |
| `IllegalStateException` collection-init mapping only when evidence justifies | `an undocumented IllegalStateException is a provider error not a setup problem` + detector counterparts |
| Provider-spec version match | `AnkiDroidContractTest` (spec policy), detector spec tests (below-minimum / implicit fallback / newer-than-validated) |
| Upper-layer isolation (core/domain tests must not need `ContentResolver`) | `AnkiArchitectureContractTest` (pure domain, unchanged) + `AnkiDroidIntegrationIsolationTest` (only the layer may name the contract; only two files may import `android.*`) |
| Concurrent refresh: no crash / stale overwrite / leaked coroutine | `concurrent refresh requests never overlap`, `a superseded result can never be observed`, `repeated refreshes leak no coroutines` |
| Refresh recovery without restart | `installing AnkiDroid and returning to the app recovers without a restart` |

## 6. Regression risk from container wiring (must be run in CI)

GATE 02 changes composition and two screens/tests, so the first green CI run must confirm:

- `AppContainer`: two new application-scoped members (`ankiDroidHealthRepository`,
  `ankiDroidLauncher`) → **all** suites that build a container (Study session, Connection,
  Settings, TTS, STT, Audio) must stay green.
- `SettingsViewModel`: two new constructor parameters → `SettingsPersistenceInstrumentedTest`
  updated accordingly; `SettingsScreen` gained one section.
- `DiagnosticsRepository`: new nullable constructor parameter (defaults to `null`, so existing
  call sites and `DiagnosticsExportPrivacyTest` are unaffected) → verify in CI.
- `MainActivity.onStart`: a new lifecycle hook whose body is wrapped so no AnkiDroid failure can
  block startup (INV-ANKI-DET-09).

Not runnable here (§2); no claim of green status is made anywhere in this report.

## 7. Tests added (JVM; 73 test methods across 6 classes + 1 doubles file)

`app/src/test/java/com/studyagent/client/anki/ankidroid/`:
`AnkiDroidTestDoubles.kt` (programmable probe/permission/detector/health-check fakes),
`AnkiDroidContractTest.kt` (7), `AnkiDroidFailureClassifierTest.kt` (14),
`AnkiDroidDetectorTest.kt` (23), `AnkiDroidHealthCheckTest.kt` (6),
`AnkiDroidHealthRepositoryTest.kt` (13), `AnkiDroidIntegrationIsolationTest.kt` (10).

Run with `./gradlew testDebugUnitTest`.

Device-only suite: `app/src/androidTest/java/com/studyagent/client/anki/AnkiDroidIntegrationInstrumentedTest.kt`
(5 tests) — optional, environment-tolerant, and in the nightly/on-demand instrumented job only
(`connectedDebugAndroidTest`), never a PR gate. It asserts platform honesty (the real probe answers
on the published contract, detection is coherent, refreshes stay bounded, no PC-backend vocabulary,
launcher reports `NotInstalled` when absent) and never requires AnkiDroid to be present.

## 8. Code added / changed (complete list)

**New — integration package (`data/anki/ankidroid/`, 10 files):** `AnkiDroidApiContract.kt`,
`AnkiDroidErrors.kt`, `AnkiDroidHealth.kt`, `AnkiDroidProbe.kt`,
`AnkiDroidPermissionManager.kt`, `AnkiDroidDetector.kt`, `AnkiDroidHealthCheck.kt`,
`AnkiDroidHealthRepository.kt`, `AndroidAnkiDroidProbe.kt`, `AnkiDroidLauncher.kt`.

**New — tests:** the 7 JVM files above, plus the device-only
`app/src/androidTest/java/com/studyagent/client/anki/AnkiDroidIntegrationInstrumentedTest.kt`
(5 tests, optional/non-gating).

**New — manifest:** `app/src/debug/AndroidManifest.xml` (debug endpoint only).

**Modified:** `core/anki/AnkiAvailability.kt` (+`Checking`, +`ProviderUnavailable`, +`statusCode`),
`core/anki/AnkiErrors.kt` (+3 error types, commit mapping), `di/AppContainer.kt`,
`data/repository/DiagnosticsRepository.kt`, `ui/screens/settings/SettingsViewModel.kt`,
`ui/screens/settings/SettingsScreen.kt`, `ui/screens/diagnostics/DiagnosticsViewModel.kt`,
`ui/screens/diagnostics/DiagnosticsScreen.kt`, `ui/navigation/AppNavHost.kt`, `MainActivity.kt`,
`app/src/main/AndroidManifest.xml`,
`app/src/androidTest/java/com/studyagent/client/ui/SettingsPersistenceInstrumentedTest.kt`
(constructor call site).

**Docs:** `docs/ANKIDROID_INTEGRATION.md` (new), `docs/ANKI_INTEGRATION_ARCHITECTURE.md` (§15.1,
§22, §23, §24 status, new §25), `docs/IMPLEMENTATION_PLAN.md` §3 ledger row, this report.

**Not touched:** `core/study/`, `core/voice/`, the AI evaluation path, `server/`, any TTS/STT
architecture file, any study-start behaviour.

## 9. Deferred (explicit)

- **Official `api` artifact adoption** — decision and recipe recorded; only revisited with a
  reliably pinnable coordinate (`docs/ANKIDROID_INTEGRATION.md` §2).
- **Broadening `Ready`** — deck listing (GATE 05), review/ratings + commit ledger (GATE 06),
  rendering/media (GATE 07+). Until then `AnkiCapabilities.NONE` is the truth.
- **Verification on a device that actually has AnkiDroid**: the instrumented test exists
  (`AnkiDroidIntegrationInstrumentedTest`) but no AnkiDroid environment was available here, so its
  device evidence is unrun. Still to verify on such a device: permission grant after
  reinstall/update, fresh-install collection initialization, the API 33 typed-flag branch of
  provider/package lookups, and a real `Ready` verdict.
- **Optional package-change trigger** — only if measurement shows a user-visible benefit over the
  foreground re-check.
- **Settings persistence of the backend mode / selector wiring** — GATE 03 (this gate is passive
  and does not route study).

## 10. Gate lock

The gate is committed as small, ordered commits on the session branch
`arena/01a0c996-study-agent-client` (this session is pinned to that branch; a separate
`gate/02-…` branch would fall outside it):

1. `feat(anki): AnkiDroid detection, permission and health foundation` — the integration layer,
   the `core/anki` amendments and the two manifests.
2. `feat(anki): wire AnkiDroid health into settings, diagnostics and app start` — DI container,
   screens, view models, `MainActivity`.
3. `test(anki): cover AnkiDroid detection, health and boundary isolation` — the 7 JVM files plus
   the device-only instrumented test.
4. `docs(anki): record AnkiDroid integration, gate notes and GATE 02 report`.
5. `gate02: establish AnkiDroid detection and permission foundation` — the empty gate-lock commit
   marking this point.

GATE 03 does **not** start from this commit automatically. The PC study path remains the only
study path.

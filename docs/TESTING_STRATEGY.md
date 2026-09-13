# Testing Strategy — Study Agent Client

**Audience:** anyone changing this client, and anyone reviewing such a change.
**Scope:** how this repository proves that a study session stays correct across multi-hour use,
Bluetooth route changes, connection loss, late callbacks, duplicate messages and process death —
on a real Android phone, without a real user.

> **Execution status.** At the commit that introduced this document the JVM suite had not been
> executed in the development sandbox (no JDK, no Android SDK, no network egress; see
> `docs/ENGINEERING_REPORT.md` §1 for the same finding). The suite is written to be *runnable and
> deterministic*; the first CI run on `master` is what turns "expected to pass" into "passes".
> That first run is also what fills the baseline tables in `docs/PERFORMANCE.md`.

---

## 1. What the suite is for

The client is a thin, stateful coordinator between four unreliable things: a human voice, a phone's
audio stack, a WebSocket, and an LLM-backed PC agent. Almost every serious bug in such a system is
an *interaction* bug — a callback that arrives after the turn that owned it, a route that changes
between saying a question and hearing the answer, a message that is retried after the user has
already moved on.

So the suite is aimed at three questions, in this order:

1. **Is the session state machine correct?** Every transition, guard, timeout and reconciliation
   rule, exercised deterministically (reducer tests, invariants).
2. **Do the interactions hold at scale?** Hundreds of cards, thousands of utterances, hundreds of
   frames, with bounded memory and no cross-talk between subsystems (integration, simulation,
   endurance).
3. **Does it survive hostility?** Random user actions, random network behaviour, seeded and
   reproducible (chaos).

Explicit non-goals:

* **No coverage for coverage's sake.** A test that asserts a getter returns what the constructor
  set is a liability: it slows the suite and has to be edited every time the type changes. §2 of
  the brief is enforced by review, not by a coverage number.
* **No rewriting of working tests.** The pre-existing suites stay; the new work *extends* the gaps
  (§3 of the brief).
* **No mocking of the thing under test.** The machine, the reducer, the reconciler, the effect
  executor, the ledger, the audio-route policy and the protocol codec are the *real* production
  classes in every JVM integration test.

---

## 2. Layers, and what each layer can prove

| Layer | Proves | Runs on | Cost | Example |
|---|---|---|---|---|
| Pure unit | One decision rule, exhaustively | JVM, virtual time | ms | `StudyReducerTest`, `SessionInvariantTest`, `RecognitionPolicyAndVocabularyTest` |
| Component | One subsystem's contract, incl. its failure modes | JVM + fakes | tens of ms | `SpeechOrchestratorTest`, `SpeechQueueTest`, `VoiceHandoffControllerTest`, `ReconnectControllerTest` |
| Integration harness | The *whole* study loop: machine + reducer + reconciler + ledger + audio policy + protocol | JVM + fakes + virtual time | ms–s | `StudySessionHappyPathTest`, `SessionIdempotencyTest`, `ReconnectAtEveryPhaseTest` |
| Simulation / endurance | Nothing unbounded; nothing leaks; nothing drifts over 100–1000 cards | JVM + virtual time | seconds | `StudySessionSimulationTest`, `StudySessionEnduranceTest` |
| Chaos | Illegal intermediate states, exactly-once under hostile ordering | JVM + virtual time + fixed seeds | seconds–minutes | `StudyAgentChaosTest`, `NetworkChaosTest`, `SttReliabilityChaosTest` |
| Instrumented | Android-specific behaviour: Compose semantics, DataStore on a real runtime, lifecycle, permissions | Emulator / device | minutes | `ui/StudyControlsInstrumentedTest`, `ui/SettingsPersistenceInstrumentedTest`, `ui/DiagnosticsExportInstrumentedTest`, `ui/AppShellInstrumentedTest`, `MainActivitySmokeInstrumentedTest` |
| Real device | Audio hardware: Bluetooth, A2DP/SCO, echo, screen-off, multi-hour battery and memory | Manually, per matrix | hours | `docs/REAL_DEVICE_TEST_MATRIX.md` |

The rule for choosing a layer: **push every decision into the lowest layer that can still prove it.**
If a rule can be expressed without Android, it belongs in a JVM test; instrumentation is reserved
for behaviour that only exists on Android (a `StateFlow` collected by a real `lifecycleScope`, a
`DataStore` write that survives process death, a `Compose` semantics tree).

---

## 3. The JVM session harness

`app/src/test/java/com/studyagent/client/testutil/StudySessionHarness.kt`

```
StudySessionMachineRepository              ← production coordinator under test
      │
StudySessionMachine ── StudyReducer ── StudyEffectExecutor
      │                    │
      │        FakeSpeechOrchestrator / FakeRecognitionOrchestrator     (voice hardware)
      │        StudyAudioRouteCoordinator + FakeAudioRouteManager        (device facts)
      │        FakeConnectionRepository ←→ FakeStudyServer               (transport + PC agent)
      │
PerformanceMetrics + DiagnosticTimeline                              (observability)
```

**Real:** the state machine, the reducer, the reconciler, the effect executor, the submission
ledger, the turn identity/generation rules, the audio-route policy, `ProtocolJson`, every timeout
constant, every guard.
**Faked:** the four boundaries a JVM cannot host — the TTS engine, the recognizer, `AudioManager`,
and the socket. The peer is faked too, because it *is* the other side of the socket.

Why the PC agent is faked and the client is not: the agent's scheduling logic is not in this
repository, and its behaviour *is* one of the inputs under test (duplicate frames, dropped replies,
a deck that ends). A scripted agent is the only way to produce those inputs on demand.

### Writing a test

```kotlin
@Test
fun `a paused session never opens the microphone`() = runTest {
    val h = newHarness(serverDeckSize = 10, autoAnswer = true)
    h.startSession()
    assertTrue(h.awaitWaitingForAnswer())

    h.pause()
    assertTrue(h.awaitPhaseIs(SessionPhase.Paused))

    h.advance(30_000)
    assertFalse("a paused session must not hold an open microphone", h.recognition.hasActiveTurn)
    h.assertInvariants("paused")
}
```

Everything the test needs is on the harness: `advance`/`await*` (virtual time), `answer`/`rate`/
`pause`/`resume`/`end`/`skip`/`hint`/`repeat` (the real repository API), `speakAnswer`/`pressToTalk`
(voice paths), `resources()`/`diagnostics()`/`timelineEvents()` (observability), and
`assertInvariants()`/`report()` (failure diagnosis).

### Invariants, checked from the outside

`checkInvariants()` runs after every `advance()` and covers the properties that must hold in *any*
state, including the ones that are only observable while they happen (a microphone opening while
speech is live). Current set:

1. TTS and STT never overlap (measured at the moment either side starts).
2. A finished session never returns to an active phase inside the same epoch.
3. The card the UI renders (`StudyState.currentCardOrNull`) is the card the machine owns.
4. A turn's generation never exceeds the session's card generation.
5. At most one answer and one rating submission in flight.
6. A paused session holds no recognition turn.
7. Bounded structures stay bounded (ledger ≤ 32, dedup ≤ 200, transitions ≤ 200, turn history ≤ 64).
8. The machine's own violation counter is zero.

When one fails, `report()` prints seed, epoch, phase, card, turn id, pending action, connection
label, speech/STT state, server counters, the resource ledger, the last 24 timeline events and the
last 20 transitions — enough to reproduce a chaos failure from the artifact alone (§172).

---

## 4. Inventory: what each suite is responsible for

### Preserved suites (existing, unchanged)

`FakeAgentConnectionTest`, `FakeAgentManagementTest`, `ProtocolJsonTest`, `ReconnectControllerTest`,
`StudyStateMachineTest`, `VoiceCommandManagerTest`, `WebSocketIntegrationTest`,
`audio/*` (mode resolver, route coordinator, phone-mode loop, self-echo, acoustic gap, preferences
mapping), `stt/*` (backend, policy/vocabulary, orchestrator, `SttReliabilityChaosTest`, command
interpreter), `tts/*` (engine adapter, orchestrator, queue, handoff, medical pronunciation,
mixed-language segmenter, chunker, text preprocessor, voice selector), `study/*` (reducer,
invariants, legacy repository, phone-mode machine integration, spoken command router),
`voice/StudyVoiceTurnGateTest`, `data/*` (settings codec, capability store, dashboard models,
dashboard repository, UI mapper, freshness, server profile, start config, control repository,
presets, management doubles).

### Added suites

| Suite | What it pins down |
|---|---|
| `study/StudySessionHappyPathTest` | The mandatory acceptance test: exactly 1 answer, 1 rating, 1 card transition, 0 stale callbacks, 0 TTS/STT overlap; the executed event order; duplicate question/evaluation; pause never opens the mic; finish leaves nothing behind; an early answer is refused |
| `study/SessionIdempotencyTest` | Repeated `SubmitAnswer`, `RateCard`, `Pause`/`Resume`, `End`, `Skip`, and a second `Start` are all refused without side effects |
| `study/ReconnectAtEveryPhaseTest` | A drop in every phase: voice frozen immediately, state requested from the server, nothing resubmitted, a legal resume state, a storm that accumulates nothing, usability after a two-minute outage |
| `study/SessionReconstructionTest` | Server authority over local belief; a reconnect during an answer re-issues the question instead of wedging; voice torn down and rebuilt; a terminal/ending session cannot be revived by a stale status frame; pause survives reconnect; cross-epoch ghost callbacks; an interrupted card can still be completed |
| `study/NetworkChaosTest` | Wire-level hostility: refused sends roll back immediately, reordering, duplicate rating ACKs, a lossy reply stream that never invents progress, slow transport, inert/foreign frames, reconnect during an in-flight answer, duplicate question after answering, hostile transcript content, failed start + retry, rate-before-evaluation, dead socket, reconnect storm, frame flood |
| `study/StudyAgentChaosTest` | Seeded random user actions (25 action kinds) with invariants after *every* step; seeds 100–199 in CI, 100–1000 nightly (`-Dstudyagent.chaos.full=true`); report contents; a dropped rating ACK leaves exactly one rating on the wire and a retryable state; a lost connection freezes the voice pipeline |
| `study/StudySessionSimulationTest` | 100 cards: exactly-once per card, bounded structures throughout, aggregate metrics complete, deck end frees everything, server card order preserved |
| `study/StudySessionEnduranceTest` | 1000 cards (2000 utterances, 1000 transcripts): bounded resources, `responses == requests`, no leftover turn, bounded timeline/log buffer, metrics that describe a session rather than a leak, quiescence after a local end, a 200-callback stale storm that changes nothing |
| `network/ProtocolFuzzTest` | Wire robustness: missing optionals take documented defaults, unknown fields/capabilities/enum strings are ignored (never fatal), malformed required data fails closed, empty/1 MB-scale lists, 50 KB strings, round trips for every client and server frame the study loop uses |
| `core/AppLoggerHardeningTest` | 10k rows stay bounded, sequence ids are unique and ordered, append cost does not grow with buffer size, secrets never reach the buffer, truncation, coalescing (and WARN never delayed), clear/reset semantics, row rendering |
| `data/SettingsPersistenceRegressionTest` | A corrupt cache can never change a setting; unknown keys and a newer schema survive; migration never touches cache keys; one bad field does not reset its neighbours; writes are idempotent; a cleared nullable stays cleared |
| `data/DiagnosticsExportPrivacyTest` | A clinical transcript never appears in an export; secrets are redacted before storage; the header identifies build/device/protocol; unmeasured metrics render `-` rather than `0ms`; every section tolerates missing collaborators; clear-then-export; the summary carries no log rows; the timeline export is bounded |

### Instrumented (Android-only behaviour)

Kept small on purpose: hardware behaviour is proven by the real-device matrix, not by an emulator
job that cannot reproduce Bluetooth or an actual microphone. Five classes, all in
`app/src/androidTest/java/com/studyagent/client/`:

| Suite | Proves |
|---|---|
| `ui/AppShellInstrumentedTest` | The real navigation graph composes on Android; Settings and Diagnostics are reachable and Back returns; clearing logs removes rows that were seeded through the app's *own* `AppLogger` (same process, same buffer) |
| `ui/StudyControlsInstrumentedTest` | Push-to-talk, the four rating buttons, Pause and End exist, are reachable, meet the 48 dp target, and claim nothing while no session exists (idle stays idle, ratings stay disabled) |
| `ui/SettingsPersistenceInstrumentedTest` | A flipped switch reaches the **real** DataStore, survives a fresh ViewModel reading the same store, and does not rewrite neighbouring settings |
| `ui/DiagnosticsExportInstrumentedTest` | The export and summary buttons reach the clipboard with the structured report; a bearer token logged by hand does not survive; the summary carries no log rows; a usable export exists before any session has run |
| `MainActivitySmokeInstrumentedTest` | `StudyAgentApp` → `ServiceLocator` → activity → Home actually launches (permissions granted by shell first, so no system dialog can swallow the test) |

Support (`AndroidTestSupport.kt`) is deliberately tiny: the real `AppContainer`, node-waiting helpers
and the clipboard/permission plumbing. A fake container here would only prove that the fake works.

Two consequences for production code, both deliberate:

* **Test tags where identity is ambiguous.** `PUSH_TO_TALK_TEST_TAG`, `ratingTestTag(rating)`,
  `StudyScreenTags`, `SETTINGS_LIST_TEST_TAG`, `settingSwitchTestTag(title)`,
  `DIAGNOSTICS_LIST_TEST_TAG`. These are additive semantics properties — no layout, no behaviour, no
  user-visible change — and they let a test select "the switch for Auto-play Question" instead of
  "the third toggle in the list". Screens that already expose a unique `contentDescription`
  (`Back`, `Clear logs`, `Export detailed diagnostics`) are matched on that and get no tag.
* **No always-running animation.** The push-to-talk pulse and the voice waveform only animate while
  the voice loop is active. That is a battery/CPU decision first (§144) — an idle Study screen was
  requesting a frame every 16 ms for as long as it stayed open — and it is also what keeps the
  instrumented suite able to reach an idle state at all (§177).

---

## 5. Determinism rules (non-negotiable)

* **Virtual time only.** `kotlinx-coroutines-test` + `TestCoroutineScheduler`. No `Thread.sleep`, no
  `delay(5_000)`, no wall-clock waits. A 1000-card session must finish in seconds.
* **Injected clocks where a decision depends on time.** `AppClock`/`TestClock` for the session,
  metrics, timeline and freshness rules; nothing else needs a clock.
* **Fixed seeds.** Chaos uses `Random(seed)` with seeds from a fixed list, printed in the failure
  report. A failure is reproducible with one number.
* **No order dependence.** Every harness construction resets the process-wide state a test could
  leak into another: `AppLogger` (buffer, coalescing, debug switch), `NetworkStatsRegistry`,
  `EffectIds`. Suites must pass in any order and individually (`--tests`).
* **Instrumented tests never assume a starting value.** The settings suite reads the current switch
  state, flips it, and asserts the opposite — a suite that assumed a default would fail on any device
  where a user already changed it. Clipboard assertions seed a sentinel first, so a stale clip can
  never satisfy a `contains` check on its own.
* **No retries.** A flaky test is a bug in the test or in the code; it is fixed, not requeued (§177).
* **Bounded failure output.** Reports are capped (25 recorded violations, 10 printed, 24 timeline
  events, 20 transitions) so a failure is readable, but they always include the seed and the event
  history that produced it.

---

## 6. Test quality rules

* **Names describe behaviour, not methods.** ``a dropped rating acknowledgement times out and leaves
  a retryable state``, not ``testRateTimeout``.
* **Assertions are about observable outcomes.** Phases, wire frames, counters, diagnostics rows and
  exports — not private fields. Where a private counter is asserted (e.g. `injectedStaleCallbacks`)
  the test first proves the injection actually happened.
* **Mutation thinking.** Before adding an assertion, ask which single-line change would make it fail.
  If no plausible bug makes it fail, delete it.
* **Fakes stay small.** A fake doubles a boundary and is programmable; it does not re-implement the
  production policy it is meant to feed. If a fake grows a state machine, the test is in the wrong
  layer (the pre-existing phone-mode suite keeps its own nested fakes for exactly this reason — they
  are local to the behaviour that suite exercises).
* **Failure output is part of the contract.** Assertions in the harness suites pass `seed` and
  history into the message, so a CI log is a reproduction recipe.

Anti-patterns that are rejected in review: asserting `0 == 0`-style invariants, sleeping to "let
things settle", retrying until green, mocking the class under test, adding a dependency for a test
that ten lines of JSON would cover, and instrumented tests for logic that a JVM test can already
prove.

---

## 7. Flaky-test policy

1. A flaky result is treated as a **defect**, with the flakiness itself as the symptom: first
   reproduce it locally, then fix the root cause (usually a real-time dependency, a shared
   singleton, or an unawaited coroutine).
2. If a fix needs more than a day, the test is **quarantined by name** in the PR that reports it,
   with an issue link and an expiry date. Quarantine means "excluded and tracked", never "retried".
3. Order dependence is investigated with `--tests` on the single suite plus a shuffled full run.
4. Global state is the usual suspect: `AppLogger`, `NetworkStatsRegistry`, `EffectIds`,
   `AppDiagnostics`, `AppPerformanceMetrics` are all process-wide and must be reset by anything that
   observes them (the harness factory does it centrally).

---

## 8. Coverage map by risk area × layer (§168)

Priority order follows the risk table in §169: state transitions and voice coordination first,
formatting/visual helpers last.

| Risk area (§169) | Unit | Integration (harness) | Simulation / endurance | Chaos | Instrumented | Real device |
|---|---|---|---|---|---|---|
| Session state transitions | `StudyReducerTest`, `SessionInvariantTest`, `StudyStateMachineTest` | Happy path, Idempotency, Reconnect | Simulation, Endurance | `StudyAgentChaosTest` | — | — |
| Voice coordination (half-duplex, handoff, gaps) | `VoiceHandoffControllerTest`, `AcousticGapPolicyTest`, `StudyVoiceTurnGateTest`, `SpeechQueueTest` | Happy path, Network chaos | Simulation, Endurance | `StudyAgentChaosTest` (PTT, stop, TTS fail) | PTT semantics | BT/SCO switch, echo |
| Answer/rating exactly-once | `SubmissionLedger` via reducer tests | Happy path, Idempotency, Network chaos | Simulation, Endurance | `StudyAgentChaosTest` | — | — |
| Reconnection / reconstruction | `ReconnectControllerTest`, `SessionInvariantTest` | Reconnect, Reconstruction | Endurance (after finish) | Connection loss/restore, storm | — | Airplane-mode, process death |
| Audio route changes | `StudyAudioModeResolverTest`, `StudyAudioRouteCoordinatorTest`, `PhoneModeLoopTest`, `SelfEchoAndPhoneMetricsTest` | Reconstruction, Reconnect | — | `ROUTE_LOST`/`ROUTE_RESTORED` steps | — | Bluetooth matrix |
| Settings persistence | `AppSettingsPreferencesCodecTest`, `SettingsPersistenceRegressionTest`, `ServerProfileTest`, `StudyPresetTest` | — | — | — | DataStore on Android runtime | Kill/relaunch |
| Protocol / wire | `ProtocolJsonTest`, `ProtocolFuzzTest`, `WebSocketIntegrationTest`, `FakeAgentConnectionTest` | Network chaos | — | Malformed/foreign frames | — | Wi-Fi↔LTE handover |
| Observability & privacy | `AppLoggerHardeningTest`, `DiagnosticsExportPrivacyTest` (+ `DiagnosticsFormatting`) | Export from a live session | Bounded timeline | Frame floods | Export action | Multi-hour export |
| Management (Dashboard/Control) | `DashboardRepositoryTest`, `DashboardUiMapperTest`, `DashboardModelsDeserializationTest`, `CapabilityStoreTest`, `StudyControlRepositoryTest`, `FreshnessPolicyTest`, `SessionStartConfigTest` | — | — | — | Dashboard Start/Resume, Control save | Slow agent |
| UI state & accessibility | — | — | — | — | Compose semantics, 48dp targets | TalkBack pass |

### What is deliberately *not* covered here

* Bluetooth codec/SCO behaviour, echo cancellation and real engine timing — a device is required
  (`docs/REAL_DEVICE_TEST_MATRIX.md`).
* The PC agent's scheduling, LLM scoring quality and deck semantics — a different repository, and
  the wrong thing for the client to assert.
* Wall-clock performance of a release build on low-end hardware — measured, not asserted; see
  `docs/PERFORMANCE.md`.
* Full process-death recovery of the *machine* (the local snapshot is intentionally not persisted
  yet): what is covered is reconnection within one process, plus the server-authoritative path that
  a fresh process takes.

---

## 9. How to run

```bash
# The fast gate — everything that needs no device (~minutes)
./gradlew testDebugUnitTest

# One suite, while iterating
./gradlew testDebugUnitTest --tests "com.studyagent.client.study.StudySessionEnduranceTest"

# One chaos seed (the failure report prints the seed it failed on)
./gradlew testDebugUnitTest --tests "com.studyagent.client.study.StudyAgentChaosTest"

# The wide chaos sweep (seeds 100-1000) — nightly in CI, minutes locally
./gradlew testDebugUnitTest -Dstudyagent.chaos.full=true \
  --tests "com.studyagent.client.study.StudyAgentChaosTest"

# Quality gates that CI also runs
./gradlew lint
./gradlew assembleDebug
./gradlew assembleRelease

# Android-specific behaviour (emulator or device)
./gradlew connectedDebugAndroidTest
```

CI (`.github/workflows/android-ci.yml`) runs the fast gate on every push and PR with no
`continue-on-error` anywhere, uploads test/lint/APK artifacts on failure, runs the wide sweep and
the instrumented smoke test nightly and on demand, and cancels superseded runs on the same ref.

---

## 10. Definition of Done for a change to the session path

A change that touches the machine, reducer, effects, voice coordination, protocol or settings is
done when:

1. It has a test in the **lowest layer that can prove it** — usually the reducer or the harness.
2. Every harness-based test calls `assertInvariants()` at least once, and the change does not add a
   new invariant violation under the chaos sweep.
3. The endurance suites still complete with bounded resources (they are the leak detector).
4. `testDebugUnitTest`, `lint`, `assembleDebug`, `assembleRelease` are green.
5. New user-visible behaviour has a diagnostics row, a timeline event or a counter behind it — if a
   failure in production would be invisible, the change is not finished (§181).
6. Anything that could not be verified in this environment is stated as such in the PR, not implied
   to be verified.

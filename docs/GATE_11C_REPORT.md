# GATE 11C — AnkiDroid rating commit report

**Report date:** 2026-10-07
**Decision:** **DO NOT LOCK GATE 11C**

The production implementation and source audit are complete, and the JVM evidence is green. The
Gate remains unlocked because this environment has no Android device/emulator, AnkiDroid
installation, disposable profile/collection, `adb`, or runnable Gradle distribution. No real
AnkiDroid mutation is claimed.

## Scope delivered

- Audited the pinned AnkiDroid `v2.24.1` public provider contract. The tag resolves to source
  commit `9f579c10bb151146728220729c510acbbd8faba7`; the pinned scheduler backend is Anki
  `25.09.2`.
- Documented the exact public mutation symbol, URI, permission, fields, rating/ease mapping,
  return values, swallowed scheduler-exception behavior, and failure windows in
  `docs/GATE_11C_ANKIDROID_COMMIT_AUDIT.md`.
- Centralized `Rating` to public ease mapping in
  `AnkiDroidCommitEvidence.kt`; the gateway has no second mapping table.
- Kept one scheduler-answer mutation path:

  ```text
  AnkiDroidRatingGateway.submitAnswer()
    -> AnkiDroidProviderClient.safeUpdate()
    -> ContentResolver.update(content://<authority>/schedule, ...)
  ```

  Temporary `selected_deck` configuration writes are separate from the scheduler-answer path and
  are restored after an attempt. The answer path has no adapter retry.
- Added explicit pre-boundary validation for backend/session/turn/card identity, positive
  note/ordinal identity, bound deck, publicly known collection keys, permission, collection
  readiness, capability, scheduled-review readiness, queue front, and durable mutation-entry
  success.
- Centralized mutation-boundary classification to the only three backend results:
  `ConfirmedCommitted`, `ConfirmedNotCommitted`, and `OutcomeUnknown`.
- Preserved the locked coordinator/ledger order: durable `SUBMITTING` before the provider answer,
  durable `COMMITTED` before the next-card query, and no adapter ownership of StudyState or the
  ledger.
- Declared AnkiDroid semantics as `AT_MOST_ONCE_FAIL_CLOSED`, with no idempotent replay,
  transaction receipt, or authoritative reconciliation claim.
- Added focused classifier/ease/row-count tests and an opt-in disposable-collection Android test
  that checks real scheduler counters, duplicate-input suppression, mutation count, and next-card
  progression.

## Classification contract

| Evidence at the adapter boundary | Backend result | Durable coordinator status |
|---|---|---|
| Refusal or validation failure before answer dispatch | `ConfirmedNotCommitted` | `RETRY_ALLOWED` |
| Returned row count `1` plus immediate normal-card state consistent with one review | `ConfirmedCommitted` | `COMMITTED` |
| Timeout, `-1`, provider/process/binder failure, swallowed/inconclusive scheduler result, or exception after entry | `OutcomeUnknown` | `AMBIGUOUS` |

A card-state read after process death is deliberately not treated as a transaction-correlated
receipt. Card identity, card counters, timestamps, and `ReviewTurnId` do not establish idempotency.

## Validation performed

### JVM fallback harness

Gradle could not bootstrap in this sandbox, so the repository's documented standalone JVM harness
was bootstrapped with a JDK 17 runtime, Kotlin compiler, Android 34 API jar, and pinned
coroutines-test source. The harness is evidence only; it is not a replacement for AGP/Gradle.

- Main compilation: **0 errors**, 198 Kotlin source files.
- JVM test compilation: **0 errors**, 143 test source files.
- Focused GATE 11C/backend/coordinator run: **32 passed, 0 failed, 0 ignored**.
- Full JVM harness run: **123 classes, 1,381 tests passed, 0 failed, 0 ignored** in 16.6 seconds.
- Source whitespace check: clean.

The new Android instrumentation source was also type-checked against the compiled pure-Kotlin
outputs and Android API jar using small local test-runner/container stubs. This does not prove an
AGP compile; the real `androidTest` task remains pending.

### Gradle/Android validation

The required combined command was attempted:

```text
./gradlew --no-daemon clean test lint assembleDebug assembleRelease connectedAndroidTest
```

It failed before Gradle task execution while downloading the wrapper distribution
`gradle-8.7-bin.zip` from `services.gradle.org`:

```text
javax.net.ssl.SSLHandshakeException: Remote host terminated the handshake
Caused by: java.io.EOFException: SSL peer shut down incorrectly
```

Therefore there is no honest Gradle result for clean, `test`, lint, debug assembly, release
assembly, or connected tests. No Android APK was assembled and no connected test was run.

## Evidence still required before locking

1. Run the opt-in instrumentation test on a disposable AnkiDroid profile/collection containing
   only test cards. It requires explicit operator arguments:

   ```text
   -e studyagent.ankidroid.allowMutation true
   -e studyagent.ankidroid.disposableProfileConfirmed true
   -e studyagent.ankidroid.disposableDeckId <disposable-deck-id>
   ```

2. Record real public-provider evidence that one offered rating changes the disposable card's
   scheduler state and that the duplicate logical input does not issue a second scheduler answer.
3. Record the actual Gradle clean/unit/lint/debug/release/connected results in this report.
4. If a real post-boundary response-loss or process-death experiment is ever performed, keep it
   on the disposable profile and report the outcome as ambiguous unless a future public,
   transaction-correlated receipt is audited. Do not intentionally double-answer a valuable card.

Until those items are reproduced, this report intentionally leaves GATE 11C **unlocked** rather
than overstating device or build evidence.

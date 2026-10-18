# GATE 21M — Android Validation Status

**Date:** 2026-10-18 (UTC)
**Original audit source HEAD:** `19c8a0d76169b18e4f2bf1787d107fbc96c41069`
**Principle:** a harness PASS is not a Gradle PASS; Kotlin compilation is not an APK build; a fake PC
test is not PC Anki support.

> The environment and “no GATE 21M instrumentation test was added” statements below preserve the
> original audit at `19c8a0d…`. Current-checkout attempts and the newly added, still-unexecuted test
> source are recorded in §7 and supersede those statements for the Arena branch only.

```text
GRADLE:          UNVERIFIED
ANDROID LINT:    UNVERIFIED
ANDROID BUILD:   UNVERIFIED
INSTRUMENTATION: UNVERIFIED
JVM HARNESS:     PASS (equivalence evidence only)
CAPABILITY PINS: PASS (source evidence only)
```

No Android result in this document is reported as PASS.

---

## 1. Environment

| Item | Value |
|---|---|
| Host | isolated Linux sandbox |
| JDK for harness | bootstrapped `jdk4py` Java runtime at `/tmp/pp/jdk/jdk4py/java-runtime` |
| System Java | absent (`java: command not found` before bootstrap) |
| Gradle cache | absent (`~/.gradle` empty before attempts) |
| Android SDK | absent (`ANDROID_HOME` unset; no SDK directory found) |
| Emulator/device | absent |
| Network allowlist | GitHub/PyPI only; `services.gradle.org` and Google Maven are not reachable |

---

## 2. Evidence table

| Check | Command | Available | Executed | Exit | Result | Log / limitation |
|---|---|---:|---:|---:|---|---|
| Gradle wrapper | `JAVA_HOME=/tmp/pp/jdk/jdk4py/java-runtime ./gradlew --version` | yes | yes | 1 | **BLOCKED** | `Downloading https://services.gradle.org/distributions/gradle-8.7-bin.zip` → `SSLHandshakeException: Remote host terminated the handshake` |
| Unit tests | `./gradlew testDebugUnitTest --offline` | yes | yes | 1 | **BLOCKED** | Wrapper distribution cannot be downloaded; task never configured |
| Lint | `./gradlew lint --offline` | yes | yes | 1 | **BLOCKED** | Same wrapper failure; no Android SDK |
| Debug build | `./gradlew assembleDebug --offline` | yes | yes | 1 | **BLOCKED** | Same |
| Release compilation | `./gradlew assembleRelease --offline` | yes | yes | 1 | **BLOCKED** | Same |
| Instrumentation | `./gradlew connectedDebugAndroidTest --offline` | yes | yes | 1 | **BLOCKED** | Same; additionally no device/emulator |
| JVM harness bootstrap | `tools/jvm-harness/bin/bootstrap-gate20.sh` | yes | yes | 0 | **PASS (harness)** | main 344 files 0 errors; compose GATE 16 closure 0 errors; test 271 files 0 errors |
| JVM GATE 21M suite | `tools/jvm-harness/bin/run.sh 'Gate21m'` | yes | yes | 0 | **PASS (harness)** | 7 classes / 115 tests / 0 failed |
| JVM full suite | `tools/jvm-harness/bin/run.sh` | yes | yes | 0 | **PASS (harness)** | 237 classes / 2924 tests / 0 failed |
| Capability pins | `tools/check-capability-pins.sh /tmp/gate21m-pins` | yes | yes | 0 | **PASS (source)** | 13 hold / 0 disagree / 0 not verifiable |

The exact Gradle failure (all five tasks) is:

```text
Downloading https://services.gradle.org/distributions/gradle-8.7-bin.zip
Exception in thread "main" javax.net.ssl.SSLHandshakeException: Remote host terminated the handshake
...
Caused by: java.io.EOFException: SSL peer shut down incorrectly
```

This is an environment/network failure recorded from the wrapper output; the excerpt above is
abridged at the `...` line. It is not an observed compile or test failure and must not be recorded as
a PASS or as a FAIL of the app.

---

## 3. Harness equivalence limits

From `tools/jvm-harness/README.md`:

| Item | Gradle | Harness |
|---|---|---|
| Kotlin | 1.9.24 (K1) | 2.3 (K2) |
| coroutines-test | 1.8.1 | 1.10.2 (compiled from source) |
| serialization | 1.6.3 | 1.9.0 |
| JUnit | real 4.13.2 | shims (`@Test`, `Assert`, `@Before`, `@After`) |
| DataStore | real | in-memory shim |
| OkHttp/security-crypto | real | API-shaped stubs |
| android.jar | AGP mockable jar | mockable jar + method-defaulting generator |
| Compose UI | real | **not built/executed** (GATE 16 closure compiles only selected UI files) |
| Instrumentation | real device | **not built/executed** |

Consequences:

- A harness PASS is equivalence evidence for the pure Kotlin/main and JVM test scopes, not proof that
  `./gradlew testDebugUnitTest` would pass under Gradle.
- The Settings screen and diagnostics screen are **not** in the default harness Compose scope. The
  GATE 16 closure (24 files) compiles selected card/note UI; the GATE 21M card is outside it.
- The optional `GATE16_COMPOSE_ALL=1` diagnostic sweep compiles all framework-importing files against
  the shims. It reports **147 errors**, overwhelmingly pre-existing shim/API gaps in Home, Study,
  ReviewerActionMenu, AppNavHost and other screens. No error is reported at the lines GATE 21M changed
  in `DiagnosticsScreen.kt` (226–236); that file has three pre-existing shim errors at 205, 358 and 359.
  `SettingsScreen.kt` has one pre-existing error at line 778
  (`NavigationBarItemDefaults.colors(containerColor = …)`), outside the backend section.
  This sweep is a diagnostic, not a gate, and is not evidence that the app compiles under Gradle.
- Because the production wiring fix is inside a Compose screen, the harness does not compile it in the
  default `main`/`test` scopes; it is covered by the source audit and the diagnostic sweep only.

---

## 4. Instrumentation status

The repository contains instrumented tests for prior gates (card rendering, AnkiDroid integration,
settings persistence, diagnostics export), but **none for GATE 21M**.

No GATE 21M instrumentation test was added by this run because:

1. the environment cannot compile or run `androidTest`;
2. adding an unverified test file would create a false appearance of executed Compose coverage;
3. the R2 contract requires BLOCKED to be reported honestly when mandatory environment is unavailable.

As a result, these remain **BLOCKED**, not PASS:

- TalkBack announcements for selected/disabled/unavailable rows;
- keyboard/focus traversal;
- large-text and RTL rendering;
- dark/light theme visibility of critical status;
- ViewModel/screen recreation and preference restoration on a real device;
- actual Settings navigation tap.

---

## 5. Capability-pin source revisions

The pin script clones and checks the pinned upstream sources. This run:

```text
ankitects/anki        tag 25.09.2    3890e12
ankidroid/Anki-Android tag v2.24.1   9f579c10bb151146728220729c510acbbd8faba7
FooSoft/anki-connect  master (UNPINNED)  4064fa142785975255457abd6a496015f5b71f38
```

Results: 13 hold, 0 disagree, 0 not verifiable. The AnkiConnect pin remains unpinned by design and is
printed on every run; that absence is itself a GATE 20 item.

---

## 6. Required language for any future claim

Until a real Gradle/Android/device run succeeds, the only truthful Android statement is:

```text
GATE 21M ANDROID VALIDATION: UNVERIFIED
GATE 21M IMPLEMENTATION:     COMPLETE AT SOURCE HEAD
GATE 21M LOCK:               BLOCKED PENDING ANDROID EVIDENCE
```

If a future run executes the tasks, replace this section with the actual command, exit code, log
artifact and environment; do not copy the harness result or a prior gate's Gradle result.

---

## 7. Current checkout revalidation — 2026-10-18 UTC

This update was performed at `e22a32798259c3abd88001963afe511fd950cb2e` on
`arena/95bebe16-study-agent-client`. The prior source HEAD `19c8a0d…` is unavailable in the local
Git object database. Current environment inspection found no `java`, `gradle`, or `adb` on `PATH`,
`ANDROID_HOME` / `ANDROID_SDK_ROOT` unset, and no SDK directory in the checked standard locations.
The JVM harness had bootstrapped a temporary Java runtime at
`/tmp/pp/jdk/jdk4py/java-runtime`.

| Attempt | Exit | Result |
|---|---:|---|
| `JAVA_HOME=/tmp/pp/jdk/jdk4py/java-runtime ./gradlew --version` | 1 | **BLOCKED before Gradle starts**; wrapper could not fetch Gradle 8.7 (`java.io.EOFException: SSL peer shut down incorrectly`) |
| `JAVA_HOME=/tmp/pp/jdk/jdk4py/java-runtime ./gradlew testDebugUnitTest --offline` | 1 | **BLOCKED before configuration**; same wrapper fetch failure |
| `JAVA_HOME=/tmp/pp/jdk/jdk4py/java-runtime ./gradlew lint --offline` | 1 | **BLOCKED before configuration**; same wrapper fetch failure |
| `JAVA_HOME=/tmp/pp/jdk/jdk4py/java-runtime ./gradlew assembleDebug --offline` | 1 | **BLOCKED before configuration**; same wrapper fetch failure |
| `JAVA_HOME=/tmp/pp/jdk/jdk4py/java-runtime ./gradlew assembleRelease --offline` | 1 | **BLOCKED before configuration**; same wrapper fetch failure |
| `JAVA_HOME=/tmp/pp/jdk/jdk4py/java-runtime ./gradlew connectedDebugAndroidTest --offline` | 1 | **BLOCKED before configuration**; same wrapper fetch failure; also no SDK/emulator/device |

No Gradle task reached project configuration or execution. Consequently Gradle unit tests, lint, APK
builds and instrumentation are **not PASS and remain unverified**. The Android SDK/device blocker is
independently confirmed.

### Current GATE 21M instrumentation source

`app/src/androidTest/java/com/studyagent/client/ui/Gate21mBackendSelectionInstrumentedTest.kt`
was added in this checkout with two Compose tests against the production Settings screen, production
registry/resolver wiring and the real DataStore-backed preference repository. The baseline is read
from the atomic `backendPreferenceSnapshotFlow` and decoded with
`decodeStoredBackendPreference`; it does not rely on the repository `StateFlow`'s initial `AUTO` value.
The verified tags match production semantics:

- AUTO: `settings_anki_backend_preference:auto` (`AnkiBackendAutoRowUi.KEY`);
- configured AnkiDroid: `settings_anki_backend_ankidroid_local` (exact stable ID);
- informational PC kind: `settings_anki_backend_kind:PC_AGENT` (`kind:${kind.name}`).

`assertExists()` is used as in existing repository instrumentation tests (it is available on the
Compose node interaction; no extra import is needed). These two methods are **source only**: they were
not compiled or executed. The JVM harness does not compile `androidTest`. No claim is made for screen
recreation, TalkBack runtime behavior, RTL, text scaling, keyboard traversal, theme screenshots, or a
real Settings navigation tap.

### Current-source JVM and capability evidence (not Android evidence)

```text
GATE 21M focused JVM suite: 7 classes / 115 tests / 0 failed
Full JVM harness:           237 classes / 2924 tests / 0 failed
Capability pins:             13 hold / 0 disagree / 0 not verifiable
```

These commands were rerun against the current checkout. They do not change the Android-validation
status or unlock the GATE 21M lock.

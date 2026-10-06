# JVM unit-test harness (no Gradle, no Maven)

This is a fallback for environments where Gradle cannot resolve dependencies, e.g. a sandbox where
every Maven mirror is blocked. It compiles the app's pure-Kotlin sources and the JVM unit tests with a
standalone Kotlin compiler, then runs the JUnit4 tests with a small reflective runner.

**It is not a substitute for `./gradlew testDebugUnitTest`.** Results from this harness are
evidence, not proof of what CI would report. The deviations are listed below. It was used to produce
the GATE 11 evidence in `docs/GATE_11_RATING_COMMIT.md`.

## What it builds

| Part | Built? | Notes |
|---|---|---|
| `app/src/main` non-UI Kotlin | yes | Excludes `ui/` (except files with no `android.`/`androidx.` import), `service/`, `di/`, `MainActivity`, `StudyAgentApp` |
| `app/src/debug` fixtures | yes, if pure Kotlin | `AnkiCardRenderFixtures` |
| `app/src/test` | yes, except 4 classes | `FakeAgentConnectionTest` (turbine), `WebSocketIntegrationTest` (mockwebserver), `RatingControlsPolicyTest` and `StudyPhaseMappingTest` (need Compose UI files) |
| Compose UI, instrumented tests, lint, APKs | **no** | |

## Deviations from the Gradle build

| Item | Gradle build | Harness |
|---|---|---|
| Kotlin | 1.9.24 (K1) | 2.3 (K2), from the `kotlin-jupyter-kernel` fat jar on PyPI |
| kotlinx-coroutines(-test) | 1.8.1 | 1.10.2 (test module compiled from source) |
| kotlinx-serialization | 1.6.3 | 1.9.0 |
| JUnit 4.13.2 | real | `shims/junit`: `Assert`/`@Test`/`@Before`/`@After` only |
| DataStore | real | `shims/datastore`: in-memory `Preferences`/`DataStore` |
| OkHttp, security-crypto | real | API-shaped stubs; never exercised by the included tests |
| `BuildConfig` | generated | `shims/buildconfig` (`DEBUG = true`, like `testDebugUnitTest`) |
| android.jar | AGP mockable jar (`isReturnDefaultValues = true`) | `mockgen/MockGen.kt` rewrites `android-34.jar` so that methods return default values. Constructors keep only their `super` call |
| `@Test(timeout)` | JUnit rule | Each test runs on its own thread with a join timeout (`-Dharness.timeoutMs`, default 60000). The stack of a stuck thread is reported |

## Bootstrap

Work dir `$HARNESS_WORK` (default `/tmp/h`) holds every downloaded jar and every build output; none
of it is committed. `bin/bootstrap-sandbox.sh` performs the whole recipe for a sandbox where Maven
Central and `dl.google.com` are unreachable but PyPI and GitHub are not:

```bash
# one-time inputs (see the script header for the exact commands)
#   PyPI  : jdk4py (Java 17+), kotlin-jupyter-kernel (Kotlin compiler + stdlib +
#           kotlinx-coroutines-core 1.10.2 + the kotlinx-serialization compiler plugin)
#   GitHub: Sable/android-platforms (android-34/android.jar),
#           Kotlin/kotlinx.coroutines tag 1.10.2 (kotlinx-coroutines-test sources)
HARNESS_WORK=/tmp/h HARNESS_JAVA=... HARNESS_KOTLIN_JARS=... \
  tools/jvm-harness/bin/bootstrap-sandbox.sh
```

What the script has to get right, and why (each was a real failure in this environment):

| Step | Requirement |
|---|---|
| `libs/kotlinx-coroutines-core-jvm-1.10.2.jar` | carved out of the kernel fat jar and **must** keep `META-INF/kotlinx-coroutines-core.kotlin_module`, or every shim fails with `unresolved reference 'withLock'` / `interface MutableStateFlow<T> ... does not have constructors` |
| `libs/kotlinx-atomicfu-*.jar` | the repo's `shims/atomicfu` compiled into its own jar; `kotlinx-coroutines-test` uses `atomic(...)` at compile *and* run time |
| `libs/kotlinx-coroutines-test-jvm-1.10.2.jar` | compiled from source by `bin/prep-coroutines-test.py` (it collapses KMP `expect`/`actual` into one module, which Kotlin 2.3 refuses otherwise) **and** must contain `jvm/resources/META-INF/services/*`, or every `runTest` throws `IllegalStateException: Exception handler was not found via a ServiceLoader` |
| `serplugin.jar` | only the serialization plugin classes plus their `META-INF/services` entries; passing the whole fat jar as `-Xplugin` fails with "ScriptDefinitionProvider duplicated" |
| `android-34-mockable.jar` | `mockgen-out/` then `java -cp ... mockgen.MockGenKt <android.jar> <out>`; shims must be built **after** `libs/` exists, because the shim step strips `@Metadata` from `org/junit/Assert.class` |
| `shims-out/` | `bin/build.sh shims` |

Tooling: `HARNESS_JAVA` points to any Java 17+ runtime (the `jdk4py` wheel works);
`HARNESS_KOTLIN_JARS` points to the Kotlin compiler jars (the `kotlin-jupyter-kernel` wheel works).

## Use

```bash
tools/jvm-harness/bin/build.sh all                     # main + tests; prints error counts
HARNESS_JAVA_OPTS=-Dharness.timeoutMs=15000 \
  tools/jvm-harness/bin/run.sh                         # all tests
tools/jvm-harness/bin/run.sh 'ReviewCommit|RatingCommit' # regex on class names
```

`run.sh` runs from `app/`, the working directory Gradle uses for unit tests, because
source-scanning architecture tests resolve paths relative to it.

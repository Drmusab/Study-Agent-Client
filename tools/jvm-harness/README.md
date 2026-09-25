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

## Bootstrap (manual, one-time)

Work dir `$HARNESS_WORK` (default `/tmp/h`) must contain the following. None of it is committed.

- `libs/`: `android-34.jar` (e.g. from the `Sable/android-platforms` Git repo),
  `kotlin-stdlib`, `annotations-13.0`, `kotlinx-coroutines-core-1.10.2`,
  `kotlinx-coroutines-test-1.10.2`, `kotlinx-serialization-{core,json}-jvm-1.9.0`.
- `serplugin.jar`: the kotlinx-serialization compiler plugin classes plus their
  `META-INF/services` entries, extracted from the kernel fat jar.
  Passing the whole fat jar as `-Xplugin` fails with "ScriptDefinitionProvider duplicated".
- `mockgen-out/`: `bin/kc mockgen-out "<fat jar>" mockgen/MockGen.kt`, then
  `java -cp mockgen-out:<fat jar>:<stdlib> mockgen.MockGenKt libs/android-34.jar android-34-mockable.jar`.
- `shims-out/`: `bin/build.sh shims`.

Tooling: `HARNESS_JAVA` points to any Java 17+ runtime, e.g. the `jdk4py` wheel.
`HARNESS_KOTLIN_JARS` points to the Kotlin compiler jars, e.g. the `kotlin-jupyter-kernel` wheel.

## Use

```bash
tools/jvm-harness/bin/build.sh all                     # main + tests; prints error counts
HARNESS_JAVA_OPTS=-Dharness.timeoutMs=15000 \
  tools/jvm-harness/bin/run.sh                         # all tests
tools/jvm-harness/bin/run.sh 'ReviewCommit|RatingCommit' # regex on class names
```

`run.sh` runs from `app/`, the working directory Gradle uses for unit tests, because
source-scanning architecture tests resolve paths relative to it.

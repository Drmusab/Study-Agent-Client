# AnkiDroid Integration — Dependency, Detection, Permission & Health

Date: 2026-09-22 · Gate: **GATE 02** (AnkiDroid API dependency, detection & permission foundation)
Normative companion: [`docs/ANKI_INTEGRATION_ARCHITECTURE.md`](ANKI_INTEGRATION_ARCHITECTURE.md)
Code: `app/src/main/java/com/studyagent/client/data/anki/ankidroid/`

This document is the reference for **how Study-Agent talks to AnkiDroid**: what it may depend on,
how it finds the provider, what the permission really is, how the API version is tracked, what
every availability state means, what the user should do about each one, what one health check
does, and what is explicitly *not* proven.

---

## 1. Scope

**This gate ships the foundation, not the features.** The AnkiDroid layer answers exactly four
questions, truthfully:

1. Is AnkiDroid installed?
2. Does it expose the integration provider Study-Agent can talk to?
3. May Study-Agent use it (permission), and does it publish a supported API spec?
4. Can it answer a bounded read right now (collection reachable)?

**In scope (implemented):** provider/package discovery, API-host spec detection, permission
visibility, a bounded read-only probe, explicit availability states, a typed failure model, an
application-scoped health owner (`StateFlow`), refresh policy (single-flight + stale-result
protection), retry/refresh without restart, diagnostics rows, Settings status section, an
"Open AnkiDroid" helper, and the manifest permission/`<queries>` the public contract requires.

**Out of scope (deliberately absent, §89/§90 of the gate):** deck browsing, card/render access,
review sessions, scheduler integration, ratings, bury/suspend, card editing, media playback,
`ReviewInfo` queries, any `AddContentApi` mutation, any cache, any Room/SQLite, any WorkManager
job, any network requirement, any change to the PC study flow, and any AnkiDroid behaviour in
`StudyState`.

**Invariants this document and the code together protect** (gate INV-ANKI-DET-01…10): absence
never crashes the app; package presence alone is never `Ready`; `Ready` requires provider +
permission + supported spec + an answering collection; Anki health is independent of PC Agent
state; availability is derived at runtime and never persisted as truth; only this package touches
the Android provider APIs; health checks never mutate; a stale result cannot overwrite a newer
one; a health failure cannot block startup; and the PC study flow works with or without
AnkiDroid.

---

## 2. Dependency decision and artifact evaluation

The decision this gate made: **Study-Agent does not link an AnkiDroid artifact.** It talks to the
documented public provider contract (`FlashCardsContract` + `CardContentProvider`) and owns its
constants, verified against upstream sources.

### 2.1 What was evaluated

| Channel / coordinates | Result (verified 2026-09-22) | Notes |
|---|---|---|
| `com.ichi2.anki:api:2.0.0` — the official `api` module | **Not published to any public repository** | `api/build.gradle.kts` @ v2.24.1 declares the module and a `maven-publish` of the release variant to a *local* repo; i.e. the only supported "install" is `./gradlew :api:publishToMavenLocal`. |
| Maven Central (`com/ichi2/anki/…`) | **404** | Maven Central is not a distribution channel for this artifact. |
| JitPack `com.github.ankidroid:Anki-Android` | **Unreliable per tag** | JitPack's `jitpack.yml` builds only `:api:publishToMavenLocal` (JDK 21, installed via sdkman). Per-tag status API: `v2.23.3` = built (ok); `v2.24.1` = `status:"none"` (**never built**); several other tags report `Error`. |
| JitPack `com.github.ankidroid:Anki-Android:api-v1.1.0` (older, Java-era API module) | **Resolvable** | POM returns 200 with packaging `aar`; its only dependency is `androidx.annotation:annotation:1.1.0`. This artifact predates the provider-spec constant entirely (no `apiHostSpecVersion`), so it cannot express the API version this gate tracks. |
| JitPack `com/ichi2/anki/api/1.1.0` | **404 "Repository not found"** | JitPack does not serve the `com.ichi2.anki` group — only the `com.github.ankidroid:Anki-Android` coordinate works. |
| `ankidroid/apisample` (official sample consumer) | **Reference recipe only** | Confirms the intended consumption shape: JitPack coordinate + the SDK 30+ `<queries>` snippet + the permission auto-merged from the artifact's own manifest. Its build files also show a raw-GitHub `api/repository` path, which is a maintainer setup, not a distributable coordinate. |

### 2.2 Packaging facts that informed the decision

- The `api` module's `minSdk` is 16 and the AnkiDroid app's `minSdk` is 24 — neither is a blocker
  for our `minSdk` 26, so **Android-version compatibility was not the deciding factor**.
- The artifact is **not merely a contract library**: it carries BuildConfig constants
  (`AUTHORITY`, `READ_WRITE_PERMISSION`), its own `<queries>`/permission manifest entries, and
  utilities (`AddContentApi`) whose version has to be pinned exactly (no `latest.release`, no
  dynamic versions — gate rule). Every channel that could serve it is either absent from public
  repositories or unreliable per tag, which converts "pin the API version" into "pin a
  JitPack-built commit artifact whose availability we do not control".
- The very API that would justify the dependency **cannot be used as a presence signal**:
  `AddContentApi.apiHostSpecVersion` returns the documented fallback
  `DEFAULT_PROVIDER_SPEC_VALUE = 1` when the provider (or its metadata) is absent — a
  "provider exists" answer it cannot give. The KDoc claim that it returns `-1` is wrong in the
  shipping code. Detection therefore has to resolve the provider itself in any case.
- **Licensing (technical facts only, no legal conclusion):** the AnkiDroid repository is
  published under GPL-3.0; the `api` module carries its own license declaration upstream
  (LGPL-3.0 as published). A licensing review remains deferred to the release gate, exactly as
  the architecture contract's §14 records.

### 2.3 Decision and its consequences

| Consequence | Handling |
|---|---|
| The artifact normally **auto-merges the permission**; without it, we must declare it | Declared explicitly in `app/src/main/AndroidManifest.xml` (§3.4) |
| We must discover the provider ourselves | `PackageManager.resolveContentProvider(authority, GET_META_DATA)` — the same mechanism `AddContentApi.getAnkiDroidPackageName()` uses (§3.1) |
| We must read the provider spec ourselves | `ProviderInfo.metaData[com.ichi2.anki.provider.spec]` with the documented fallback of 1 (§4) |
| Constants could drift from upstream | Every constant and signature is pinned in `AnkiDroidApiContract.kt` / `AnkiDroidErrors.kt` **with its provenance** (upstream file + value), and reviewed whenever the AnkiDroid release we verify against changes |
| No AnkiDroid code ships in the APK | Verified by the isolation test (`AnkiDroidIntegrationIsolationTest`): no source file outside this package may name the contract |

**Adoption recipe, if a later gate decides to link the official artifact** (recorded for that
decision, deliberately *not* used here):

```kotlin
// build.gradle.kts (app) — coordinate as published by the AnkiDroid project / JitPack,
// pinned to an exact tag or version; never a dynamic or SNAPSHOT version.
dependencies {
    implementation("com.github.ankidroid:Anki-Android:<exact-tag>")
}
```

```xml
<!-- AndroidManifest.xml — required on API 30+ so the provider is visible to us -->
<queries>
    <package android:name="com.ichi2.anki" />
</queries>
```

Adopting it must not change detection semantics: the provider must still be resolved and probed
before any state is reported, because the artifact's own version constant cannot answer "is it
there".

---

## 3. Authority, provider and permission relationship

### 3.1 Authority and provider discovery

AnkiDroid exposes one exported provider per application id:

| Endpoint | Application id | Authority | Permission |
|---|---|---|---|
| Release | `com.ichi2.anki` | `com.ichi2.anki.flashcards` | `com.ichi2.anki.permission.READ_WRITE_DATABASE` |
| Debug | `com.ichi2.anki.debug` | `com.ichi2.anki.debug.flashcards` | `com.ichi2.anki.debug.permission.READ_WRITE_DATABASE` |

The authority follows the documented template `${applicationId}.flashcards`, so the constants are
derived, not hand-copied, and only one file in the codebase may name the application id
(`AnkiDroidApiContract.kt`).

Discovery order, in one line: **provider first, package second.**

1. `resolveContentProvider(authority, GET_META_DATA)` — yields the real `ProviderInfo`:
   `packageName`, `enabled`, and the provider metadata. The release endpoint is always searched
   before the debug endpoint, and debug endpoints are only reachable in debug builds (the
   composition root passes `BuildConfig.DEBUG`).
2. The package is consulted **only** when no authority resolved, to distinguish "not installed"
   from "installed but not exposing its provider". A provider is stronger evidence than a
   package name, and package-name matching is never used as the primary signal.

Non-results are reported, not guessed: an authority served by an unexpected package, a disabled
provider, or a package present without a provider all become `ProviderUnavailable` (never
`NotInstalled`, never `Ready`).

### 3.2 Package visibility (API 30+)

Since `targetSdk` is 34, package-visibility filtering applies. Without a `<queries>` entry the
provider cannot be resolved and AnkiDroid's own documentation names the symptom
("Failed to find provider info for com.ichi2.anki.flashcards"). Study-Agent declares **one
package** in `<queries>` — never `QUERY_ALL_PACKAGES`, never a broad storage permission. The
debug package is declared in the debug manifest only.

### 3.3 The permission is third-party-declared and enforced dynamically

Verified against v2.24.1:

- AnkiDroid **declares** `…permission.READ_WRITE_DATABASE` itself with
  `protectionLevel="dangerous"`.
- Its provider does **not** enforce it through a manifest `readPermission`; it enforces it
  dynamically in `CardContentProvider.query()` (`throwSecurityException` →
  `SecurityException("Permission not granted for: …")`). Denial therefore appears at the *call*,
  not at discovery, and Study-Agent maps `SecurityException` explicitly — it is never swallowed.
- Because the permission is declared by a third-party app, Android resolves the grant at
  install/update time. There is **no runtime dialog** for this permission and the app must not
  present one; the install-order case is documented upstream ("e.g. due to install order bug"),
  meaning: if Study-Agent was installed *before* AnkiDroid, the grant may not have resolved.

Study-Agent declares the permission itself (see §2.3) and checks it *before* issuing a probe, so
the normal "no permission" path costs no provider call and produces the accurate
`PermissionRequired` state instead of a stack of `SecurityException`s. If a `SecurityException`
happens anyway, it is classified as `PERMISSION_DENIED` (§5).

### 3.4 Manifest declarations

```xml
<!-- app/src/main/AndroidManifest.xml -->
<uses-permission android:name="com.ichi2.anki.permission.READ_WRITE_DATABASE" />

<queries>
    <package android:name="com.ichi2.anki" />
</queries>
```

```xml
<!-- app/src/debug/AndroidManifest.xml (debug builds only) -->
<uses-permission android:name="com.ichi2.anki.debug.permission.READ_WRITE_DATABASE" />
<queries>
    <package android:name="com.ichi2.anki.debug" />
</queries>
```

No storage, contacts, or broad-database permission is requested. Installation source (Play,
F-Droid, GitHub) is never assumed or inspected — the integration is distribution-neutral.

---

## 4. API version: the provider spec

AnkiDroid has **two independent version axes** and this gate tracks both separately:

| Axis | Where it lives | What Study-Agent does with it |
|---|---|---|
| App/marketing version | Not published to third parties | Not used. Never inferred from the package name or a preference. |
| **Provider spec** | Provider metadata `com.ichi2.anki.provider.spec` | Read from `ProviderInfo.metaData`; drives the "supported API" decision and is shown in diagnostics. |

Policy (`AnkiDroidProviderSpec`):

- `MIN_SUPPORTED_SPEC = 1` — everything this gate needs (provider exists, permitted, answers a
  bounded read) exists at spec 1. Rejecting older installs for a capability this gate does not use
  would take AnkiDroid away from users for no reason.
- `MAX_VALIDATED_SPEC = 2` — the highest spec whose contract this build verified (v2.24.1
  publishes `2`). A newer spec is reported as reachable but **not validated**: capabilities stay
  unclaimed instead of assumed.
- `IMPLICIT_WHEN_METADATA_ABSENT = 1` — mirrors the official API's fallback
  (`AddContentApi.DEFAULT_PROVIDER_SPEC_VALUE = 1`). The fallback is applied **only after a
  provider has actually resolved**, so "metadata absent" can never be mistaken for "AnkiDroid
  missing"; diagnostics render it as `1 (implicit fallback: no metadata)`.

The spec is never used as a detection signal, and diagnostics always distinguish *published* from
*fallback* values.

---

## 5. Failure classification → availability mapping

One classifier (`AnkiDroidFailureClassifier`, pure Kotlin, JVM-testable) turns an observed failure
into a category with *evidence*; the detector maps the category to the unified availability. The
table below is the normative mapping.

| Failure category | Typical evidence | Availability | Meaning for the user |
|---|---|---|---|
| `PERMISSION_DENIED` | `SecurityException` (type), or the documented `"Permission not granted"` signature | `PermissionRequired` | Access was not granted to Study-Agent |
| `COLLECTION_NOT_READY` | a **documented** setup signature (`"storage is not yet configured"`, `"not been initialized"`, `"no collection"`, …) | `CollectionNotInitialized` | AnkiDroid's first-run setup is not finished |
| `COLLECTION_UNAVAILABLE` | AnkiDroid storage exception classes (`SystemStorageException`, `SQLite…`, …) | `Fault(CollectionUnavailable)` | The collection exists but cannot be opened |
| `COLLECTION_LOCKED` | lock signatures (`"database is locked"`, `CollectionNotOpen`, …) | `TemporarilyUnavailable` | AnkiDroid is busy; try again shortly |
| `TIMEOUT` | the probe budget expired (`timedOut()`) — never inferred from an exception type | `Fault(QueryFailure("timeout"))` | AnkiDroid did not answer in time |
| `UNSUPPORTED_API` | spec below the supported minimum | `Unsupported` | This AnkiDroid is too old for the integration |
| `CONTRACT_MISMATCH` | `IllegalArgumentException` / `UnsupportedOperationException` / `"is not supported"`, `"Unknown column"`, `"Unknown URI"` | `Unsupported` | The installed AnkiDroid rejects the documented probe shape |
| `PROVIDER_ERROR` | binder transport failures; an **unclassified** `IllegalStateException` | `Fault(QueryFailure("provider-error"))` | A provider-side failure with no documented cause |
| `ENDPOINT_UNAVAILABLE` | nothing resolved, foreign package, provider disabled, package without provider | `ProviderUnavailable` | The provider itself cannot be reached |
| `UNEXPECTED` | anything else | `Fault(Unknown(exceptionClass))` | An unexpected failure; visible, not guessed |

Guarantees that hold regardless of the table:

- **`SecurityException` is never swallowed and never relabelled** — it is the provider's own
  typed refusal and is checked first, before any signature matching.
- **An unrecognised `IllegalStateException` is never reported as "setup incomplete"**: the type is
  known but the cause is not, so it becomes `PROVIDER_ERROR` and remains visible. Only documented
  setup signatures may produce `CollectionNotInitialized`.
- `NotInstalled` is a *state*, not a failure: it carries no failure code.
- `collectionReady` is `false` only when the evidence is about the collection
  (`COLLECTION_NOT_READY` / `COLLECTION_UNAVAILABLE` / `COLLECTION_LOCKED`) and `null` otherwise —
  a timeout or a foreign provider leaves the collection question unanswered rather than answered
  "no".
- Failures are **content-free**: category + evidence token + exception *simple class name*. No
  provider message text, no paths, no user content. Adding a new signature requires upstream
  evidence — an invented signature is how "finish setup" gets shown for unrelated breakage.

---

## 6. Availability states and the user recovery table

One vocabulary for everything Anki (`AnkiAvailability`), with stable diagnostics tokens
(`statusCode`). The Settings section renders the short label; the guidance line renders the
headline and the action below.

| State (token) | Short label | Meaning | What the user should do |
|---|---|---|---|
| `CHECKING` | Checking… | No result yet (app start / first refresh in flight). Deliberately not `Ready` and not a failure. | Nothing — the first frame never waits for AnkiDroid. |
| `NOT_INSTALLED` | Not installed | AnkiDroid is not installed, or nothing resolved and the package is absent. | Optional: install AnkiDroid to study your local Anki collection on this phone. |
| `PROVIDER_UNAVAILABLE` | Not reachable | AnkiDroid is present but its integration provider cannot be reached (not resolvable, disabled, served by an unexpected package, or the package disappeared between checks). | Open AnkiDroid once, then refresh. If this keeps happening, update AnkiDroid. |
| `PERMISSION_REQUIRED` | Access not granted | The integration permission is not held by Study-Agent. | Android grants this access when Study-Agent is installed: install or update Study-Agent while AnkiDroid is already installed, then refresh. |
| `COLLECTION_NOT_INITIALIZED` | Setup incomplete | AnkiDroid's first-run setup/collection is not configured yet (documented setup signature). | Open AnkiDroid and complete its first-run setup, then refresh. |
| `TEMPORARILY_UNAVAILABLE` | Busy | The collection is locked / reopening right now. | Leave AnkiDroid on its deck list (or close it) and refresh. |
| `UNSUPPORTED` | Unsupported version | The provider spec is below the minimum, or the installed AnkiDroid rejects the documented contract. | Update AnkiDroid to the latest version, then refresh. |
| `FAULT` | Check failed | A classified failure (timeout, storage, provider error, unknown). Technical detail lives in Diagnostics only. | Refresh to try again; if it keeps failing, open AnkiDroid and complete its setup. |
| `READY` | Ready | Provider + permission + supported spec + the collection answered a bounded read. **This is "we can talk", not "every Anki feature works"** — capabilities are still unclaimed. | Nothing. |
| `AGENT_DISCONNECTED`, `AGENT_ANKI_UNAVAILABLE` | Not checked | PC-path states. They are *not* AnkiDroid states, so the AnkiDroid section renders "Not checked (PC Agent state)" rather than borrowing a local message. | Use the PC Agent / Anki section of the app. |

**Independence:** AnkiDroid availability never influences the PC path and vice versa. The
valid states "PC disconnected + AnkiDroid ready", "PC ready + AnkiDroid missing" and "both ready"
are all representable, and nothing gates app startup on either.

---

## 7. The health check and refresh policy

### 7.1 What one check does

```
resolve provider authority (release first; debug only in debug builds)   → no read, no permission
  ├─ nothing resolves → package visible?  → ProviderUnavailable : NotInstalled
  ├─ resolved but not ours / disabled      → ProviderUnavailable
  ├─ spec below minimum                    → Unsupported            (no permission check, no probe)
  ├─ permission not granted                → PermissionRequired     (no probe issued)
  └─ bounded read probe: content://<authority>/selected_deck
        projection = [ deck_id ], one row, moveToFirst()               → Ready
        failure                                                        → mapped per §5
```

- **The probe is the cheapest documented read.** `selected_deck` is served from a one-row
  `MatrixCursor`; the projection is a single column and only `moveToFirst()` is evaluated. No
  deck id, deck name, card or note data is read, kept, logged or cached — the probe records
  "the provider answered", nothing else.
- **Why a probe is needed at all:** a resolvable provider proves the app exists, not that its
  collection is usable. AnkiDroid's provider opens its own collection *before* answering any
  query, so a successful bounded read is positive evidence that the collection layer answered
  (and §8 records that side effect honestly).
- **Budget:** 3 000 ms per check. A cold provider call can be slow because the collection is
  opened first; a 1-second budget would produce false timeouts exactly on the devices that need
  patience. The budget bounds *how long we wait*, not the binder call itself (§8).
- **Timing:** `checkedAtEpochMs` and `durationMs` are measured with the app clock for diagnostics.
- **Containment:** a check never throws for an AnkiDroid or platform failure — it returns a
  snapshot describing it. Only *caller* cancellation propagates, unchanged.

### 7.2 Refresh policy

| Trigger | Call | Notes |
|---|---|---|
| App start / return to foreground | `MainActivity.onStart` → `onAppForeground()` | Also covers "user opened AnkiDroid, finished setup, came back" — no restart needed. |
| Settings screen opened | `onAppForeground()` | Same entry point, debounced. |
| User taps Refresh / Retry | `requestRefresh()` | Explicit user intent always allowed past the debounce. |
| Tests / one-shot callers | `suspend refresh()` | Returns the snapshot. |

No polling loop, no periodic timer, no `PACKAGE_REPLACED` receiver in this gate, no retry ladder.
AnkiDroid state changes only when the user changes it, and a foreground re-check is the simplest
robust trigger; a receiver would buy a fraction of a second in a case where the user is looking at
the screen anyway.

### 7.3 Concurrency and stale-result protection

- **Single-flight, serialized:** one `Mutex` guarantees at most one provider check is in flight per
  process; fire-and-forget refreshes *coalesce* into a running check instead of queueing a ladder
  of probes.
- **Debounce:** foreground triggers closer together than 2 000 ms are ignored (a startup burst or a
  quick Settings visit collapses into one provider call), while a user who fixes AnkiDroid and
  comes straight back still sees the new state immediately.
- **Publication guard:** every refresh takes a monotonically increasing request id and may only
  publish while it is still the newest request. A slow check can therefore never overwrite a newer
  result, even though checks are serialized.
- **Cancellation-safe:** cancelling a refresh (screen gone, app shutting down) never writes a
  state and never wedges the next check.

### 7.4 Ownership

Exactly one instance exists per process, created by `AppContainer` and exposed as two `StateFlow`s
(full snapshot + availability projection). Settings, Diagnostics and any future backend selector
*observe* it; none of them re-derives readiness, and the UI contains no `PackageManager`,
`ContentResolver` or permission logic. Availability is derived at runtime and never persisted —
there is no cached "Ready" that can go stale while the app is closed.

---

## 8. Known limitations and recorded side effects

1. **AnkiDroid's provider opens its collection before answering *any* query**
   (`CardContentProvider.query` → `CollectionManager`, v2.24.1). On a completely fresh install, a
   probe can therefore cause AnkiDroid to initialize its own collection. Study-Agent issues
   read-only requests and never writes; the read still happens inside AnkiDroid's implementation,
   so the effect is recorded here instead of being hidden.
2. **The timeout bounds our wait, not the binder call.** A provider that never returns keeps a
   thread in AnkiDroid's process; Study-Agent cancels from its side and the next check starts from
   a clean state.
3. **`Ready` ≠ all Anki features work.** This gate validates reachability only; capabilities stay
   `AnkiCapabilities.NONE` and `isReadyForReview` stays `false`, because review, deck listing,
   rendering, media and editing are later gates. `Ready` is a statement about the communication
   foundation.
4. **Detection on API 30+ depends on `<queries>`.** If the declaration were ever removed, the
   symptom is indistinguishable from "not installed" — the isolation test guards the manifest.
5. **Debug builds only:** a device carrying only a debug AnkiDroid looks `NotInstalled` to a
   release build. That is deliberate: release behaviour must never contain debug authorities.
6. **Environment-verified vs not.** All logic is covered by JVM tests; *device* behaviour
   (permission granted at install time, fresh-install collection initialization, the API 33
   typed-flag branches) is **not** verified in this environment — see §11.
7. **Not implemented on purpose:** deck browsing, review, ratings, card/media access, editing,
   `ReviewInfo`, and every `AddContentApi` mutation (§1).

---

## 9. Diagnostics and privacy

Diagnostics renders a technical section (`AnkiDroid`, export section "AnkiDroid integration")
with sanitized, content-free rows:

`Status` (the status token) · `Endpoint` · `Authority` · `Authorities checked` · `Package` ·
`Provider` · `Provider package expected` · `Provider spec` (published vs implicit fallback) ·
`Permission` (granted + `protectionLevel`) · `Collection usable` · `Last check` (age) ·
`Check duration` · `Last failure code` (`CATEGORY/EVIDENCE/token`) · `Last failure exception`
(simple class name) · `Probe` (`selected_deck (1 row, read-only)`).

Unknown values render as `Unknown`/`-`, never as a plausible-looking default. Nothing here can
contain card content, note fields, collection paths, tokens or PC credentials — the layer never
reads them, and failures store only the exception simple class name and a bounded evidence token.
There is no analytics or telemetry of any kind.

---

## 10. Tests and how to run them

JVM suite (runs in the normal CI unit-test job; no AnkiDroid, no emulator):

| File (`app/src/test/java/com/studyagent/client/anki/ankidroid/`) | Focus |
|---|---|
| `AnkiDroidTestDoubles.kt` | Programmable fakes for the probe/permission/detector/check seams + builders |
| `AnkiDroidContractTest.kt` | Pinned contract values, application-id templates, release-never-debug, spec policy |
| `AnkiDroidFailureClassifierTest.kt` | Exception/signature mapping, ordering, `SecurityException` priority, content-free failures |
| `AnkiDroidDetectorTest.kt` | The full availability matrix, endpoint precedence, `Ready` semantics, invariants |
| `AnkiDroidHealthCheckTest.kt` | Timing, the timeout budget, cancellation, defect containment |
| `AnkiDroidHealthRepositoryTest.kt` | Initial `Checking`, stale-result guard, single-flight/coalescing, debounce, recovery without restart, no leaks |
| `AnkiDroidIntegrationIsolationTest.kt` | Source/manifest boundary scans (who may name AnkiDroid, no writes, no forbidden tech, one state hierarchy) |

```bash
./gradlew testDebugUnitTest        # the whole suite, including the 7 files above
```

Instrumented suite (device only; the `instrumented` CI job, nightly and on demand — never a PR
gate, because AnkiDroid is not installed on the standard emulator image):

| File (`app/src/androidTest/java/com/studyagent/client/anki/`) | Focus |
|---|---|
| `AnkiDroidIntegrationInstrumentedTest.kt` | The real `AndroidAnkiDroidProbe` answering on the published contract without throwing; detection coherence on the actual device; two bounded refreshes plus foreground triggers; "never a PC-backend state, never left `Checking`"; the launcher reporting `NotInstalled` when AnkiDroid is absent |

`AnkiDroidIntegrationInstrumentedTest` never requires AnkiDroid: it asserts platform honesty rather
than a particular verdict, and its one focus-stealing action (launching AnkiDroid) is skipped with
`assumeTrue` when AnkiDroid is actually installed. **No result for it is claimed in this
repository**: there was no AnkiDroid environment and no Android SDK where this gate was written
(§11).

---

## 11. Verification provenance

Facts in this document were verified against upstream sources, not recalled:

| Fact | Source |
|---|---|
| Authority / permission / debug variants | `api/build.gradle.kts` @ v2.24.1 and `main` (BuildConfig fields, `buildTypes.debug`) |
| Permission declared by AnkiDroid, `protectionLevel="dangerous"` | `AnkiDroid/src/main/AndroidManifest.xml` @ v2.24.1 |
| Permission enforced dynamically (`SecurityException`) | `CardContentProvider.kt` @ v2.24.1 (`throwSecurityException`, `query()`) |
| Provider metadata key and value `2` | AnkiDroid manifest `<meta-data android:name="com.ichi2.anki.provider.spec" android:value="2" />`; read by `AddContentApi.PROVIDER_SPEC_META_DATA_KEY` |
| Fallback spec `1` | `AddContentApi.DEFAULT_PROVIDER_SPEC_VALUE = 1` |
| `selected_deck` is a one-row `MatrixCursor` | `CardContentProvider.query` `DECK_SELECTED` branch @ v2.24.1 |
| Provider opens the collection before answering | `CardContentProvider.query` → collection accessor @ v2.24.1 |
| Distribution / JitPack status | JitPack builds API per tag; `api-v1.1.0` POM; `com.ichi2.anki` 404 on Maven Central and on JitPack's group path |
| Consumer recipe (dependency + `<queries>`) | AnkiDroid API wiki + `ankidroid/apisample` |

**Not verified here** (and therefore not claimed): runtime permission-grant behaviour on a real
device after reinstall/update, the fresh-install collection-initialization case, and the API 33
`ComponentInfoFlags` branch of `resolveContentProvider`/`getPackageInfo`. These are the first
things an instrumented run with AnkiDroid available should confirm.

---

## 12. Open items for later gates

- Broadening `Ready` semantics as capabilities are actually proved: deck listing (GATE 05),
  review/ratings and the commit ledger (GATE 06), rendering/media (GATE 07+).
- Optional `PACKAGE_REPLACED`-style package-change trigger (only if measurement shows a user-visible
  benefit over the foreground re-check).
- Re-evaluating the official artifact, or replacing the bounded probe with `AddContentApi` calls,
  once a reliable pinned coordinate exists — with unchanged detection semantics (§2.3).
- Instrumented AnkiDroid contract test (environment-guarded, non-gating) and device verification of
  the items listed at the end of §11.

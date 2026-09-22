# GATE 04 — AnkiDroid Gateway, Capability Detection & Health

Date: 2026-09-22
Gate: GATE 04
Status: Implemented
Integration target: https://github.com/ankidroid/Anki-Android
Depends on: GATE 02 (detection & permission), GATE 03 (domain & backend abstraction)
Code: `app/src/main/java/com/studyagent/client/data/anki/ankidroid/`

## 1. Mission

Build a robust, production-quality AnkiDroid integration gateway that:

1. owns all direct communication with the AnkiDroid public API,
2. converts Android/AnkiDroid responses into Study-Agent domain models,
3. truthfully reports runtime capabilities,
4. exposes meaningful health information,
5. isolates provider/API failures,
6. prevents Cursor/ContentResolver leakage,
7. handles permission/collection/API changes safely,
8. supports later Deck/Card/Review features without redesign,
9. remains completely independent from Study-Agent voice/AI logic.

At the end of this Gate:

> Upper layers must have no reason to know what `FlashCardsContract`, `ContentResolver`, `Cursor`, AnkiDroid authority strings or Android provider exceptions are.

## 2. Architecture

```
                  Study-Agent Domain
                         │
                         ▼
                   AnkiBackend
                         │
                         ▼
                AnkiDroidBackend
                         │
                         ▼
                 AnkiDroidGateway
            ┌────────────┼─────────────┐
            │            │             │
            ▼            ▼             ▼
        Detection     Capability      Health
        / Provider      Probe          Probe
            │            │             │
            └────────────┼─────────────┘
                         ▼
                 Android ContentResolver
                         │
                         ▼
                  AnkiDroid Public API
```

Later Gates may add Deck/Card/Review/Note/Media gateways, but GATE 04 establishes common foundation.

### 2.1 Single Source of Truth

```
AnkiDroidIntegrationState
       │
       ├── availability
       ├── capabilities (implemented)
       ├── apiCapabilities (what provider supports)
       ├── metadata
       ├── healthSnapshot
       └── capabilityDetails
```

Derived flows: `availability` and `capabilities` are StateFlows projected from integration state, never three unrelated mutable stores (§45).

## 3. Components

### 3.1 AnkiDroidProviderClient

Low-level provider client responsible only for safe Android provider operations (§6).

```kotlin
internal interface AnkiDroidProviderClient {
    suspend fun providerFacts(endpoint): ProviderFacts?
    suspend fun isPackageInstalled(packageName): Boolean
    suspend fun getPackageVersion(packageName): String?
    suspend fun probeCollection(endpoint): ProbeOutcome
    suspend fun <T> safeQuery(...): ProviderQueryResult<T>
}
```

- Safe query: Cursor opened → read → mapped → closed via `use {}` (§5, INV-ANKI-GW-02/03)
- Null Cursor handled as typed failure, not NPE (§71)
- SecurityException mapped correctly (§76)
- Cancellation propagates (§33/§34)
- All calls on Dispatchers.IO (§31)
- URI construction belongs here (§7), not in backend consumers

Implementation: `AndroidAnkiDroidProbe` now implements both `AnkiDroidProbe` (GATE 02) and `AnkiDroidProviderClient` (GATE 04), keeping one Android-facing file. Additional implementation `AndroidAnkiDroidProviderClient` exists for explicit client usage.

Fake: `FakeAnkiDroidProviderClient` for JVM tests, no Android dependencies.

### 3.2 AnkiDroidCompatibilityPolicy

Centralized compatibility (§49/§50):

- `MIN_SUPPORTED_SPEC = 1` (same as GATE 02)
- `MAX_VALIDATED_SPEC = 2`
- `isSpecSupported(spec)`: spec >= MIN (forward compat §51: unknown newer specs not auto-failed)
- `apiCapabilitiesForSpec(spec)`: returns `AnkiDroidApiCapabilityReport` with SUPPORTED/UNSUPPORTED/UNKNOWN per capability
- `implementedCapabilitiesFor(spec, isReady)`: returns `AnkiCapabilities` actually implemented (GATE 04 → NONE)
- `isCoreCapabilitySetPresent()`: minimum for future review (provider access + collection access + deck/card lookup)

### 3.3 Capability Model

Three-state support (§12): SUPPORTED / UNSUPPORTED / UNKNOWN instead of boolean where appropriate.

Internal maturity (§103):

```
API_UNSUPPORTED
API_SUPPORTED_NOT_IMPLEMENTED
IMPLEMENTED
VERIFIED
```

This prevents confusing "AnkiDroid can support it" with "Study-Agent has finished it".

Domain `AnkiCapabilities` remains boolean (GATE 03) for feature gating, but diagnostics shows both API support and implementation status (§121).

Example diagnostics:

```
ANKIDROID CAPABILITIES

Core Provider              Supported
Collection Access          Supported
Deck Listing API           Supported
Scheduled Review API       Supported
Rendered Card API          Supported
Rating Mutation API        Supported
Media API                  Supported
Note Editing API           Supported

STUDY-AGENT IMPLEMENTATION

Deck Listing               Pending GATE 05
Review                     Pending GATE 06
Rendering                  Pending GATE 08
Ratings                    Pending GATE 11
Media                      Pending GATE 09
Editing                    Pending GATE 17
```

### 3.4 AnkiDroidCapabilityProbe

```kotlin
interface AnkiDroidCapabilityProbe {
    suspend fun probe(detection): CapabilityProbeResult
}
```

- Read-only, never mutates (§15: no test card, no rating, no bury)
- No full collection scan (§16: metadata/spec contract or bounded probe)
- Truthful (§11): true only when API contract + access + implementation present
- Capability detection is NOT backend-name detection (§9)
- Logs `ANKI_CAPABILITIES_UPDATED`

For GATE 04: returns `AnkiCapabilities.NONE` as implemented, but API report shows SUPPORTED for all known capabilities at spec >=1. This keeps `isReadyForReview` false until GATE 06, preserving PC path isolation (GATE 02 §96).

### 3.5 AnkiDroidHealthProbe

Health summarizes runtime operability (§17):

```kotlin
data class AnkiDroidGatewayHealthSnapshot(
    val availability: AnkiAvailability,
    val capabilities: AnkiCapabilities,
    val apiCapabilities: AnkiDroidApiCapabilityReport,
    val providerSpec: Int?,
    val packageVersion: String?,
    val providerReachable: Boolean,
    val permissionGranted: Boolean,
    val collectionReady: Boolean,
    val checkedAtMs: Long,
    val latencyMs: Long?,
    val lastError: AnkiError?
)
```

- Side-effect free (§21): no scheduler advance, no rating commit
- Health ≠ Capabilities (§18): READY means endpoint reachable + permission + collection usable + core contract supported, NOT "every future feature implemented" (§19)
- Core capability set defined in compatibility policy (§20)
- Package version is diagnostic, not business logic (§48)

### 3.6 AnkiDroidGateway

```kotlin
internal interface AnkiDroidGateway {
    suspend fun refreshIntegrationState(): AnkiDroidIntegrationState
    suspend fun probeCapabilities(): CapabilityProbeResult
    suspend fun probeHealth(): GatewayHealthSnapshot
    fun currentState(): IntegrationState
}
```

- Owns all direct communication with AnkiDroid public API (§3)
- Single-flight refresh (§35): 10 simultaneous refreshes → bounded provider work, consistent state
- Stale result protection (§36): generation counter, older probe cannot overwrite newer state
- Package update invalidates cache (§37): version change triggers reprobe (via foreground refresh)
- Permission change invalidates health (§38): READY → PermissionRequired on next refresh
- Collection change recovery (§39): CollectionNotReady → Ready after user configures AnkiDroid
- In-memory cache sufficient initially (§40), not persisted as permanent truth (§41, INV-ANKI-GW-12)
- No Cursor leakage (INV-ANKI-GW-02), resources closed deterministically (INV-ANKI-GW-03)
- Cancellation never converted to domain failure (INV-ANKI-GW-04)

### 3.7 AnkiDroidBackend

Implements `AnkiBackend` (GATE 03 interface):

- `id = AnkiDroidLocal` (§23, stable)
- `availability` and `capabilities` as StateFlows derived from integration state (§42/§43/§44)
- `refreshAvailability()` delegates to gateway with single-flight + stale protection
- Methods not yet implemented return `UnsupportedAction` truthfully (§24/§102), never empty list (§25/§102)
  - `getDecks()` → `UnsupportedAction("deckListingIntegrationPending")`
  - `beginReview()` → `UnsupportedAction("reviewIntegrationPending")`
  - `nextCard()` → `Failure(UnsupportedAction)`
  - `commitRating()` → `Rejected(UnsupportedAction)`
- No review mutation (§91/§97-§101, INV-ANKI-GW-14)
- Application-scoped, survives navigation/recomposition (§109), recreated on process restart (§110)
- No blocking main thread (§31), uses injected dispatcher abstraction where available (§32)

### 3.8 Error Mapping

`AnkiDroidErrorMapper` (§26):

| Android/provider condition | AnkiError |
|---|---|
| SecurityException | PermissionRequired |
| provider not resolved | ProviderUnavailable |
| collection initialization | CollectionUnavailable |
| unsupported provider contract | UnsupportedAction / UnsupportedApi |
| timeout | QueryFailure(timeout) |
| null cursor / malformed | QueryFailure / Unknown |
| unknown | Unknown |
| IllegalStateException | only CollectionNotReady when evidence supports, else generic (§27) |

Root cause preserved for diagnostics (§28): exception class + operation + URI category, never full stack trace to UI. Operation context recorded (§29): health_probe, capability_probe, provider_query, etc.

### 3.9 Mapper

`AnkiDroidMapper` (§56):

- Converts provider values to domain (Long deck ID → AnkiDeckRef, etc. for future)
- No UI formatting (§57), no voice normalization (§58), no AI transformation (§59)
- Strict required / lenient optional (§55): missing critical ID → fail, missing optional FSRS → continue
- Provider response validation (§54): required column exists, ID valid, type conversion safe

## 4. Threading, Timeouts, Cancellation

- Provider calls bounded where blocking plausible (§30): health probe 3s, metadata probe, normal read
- No blocking main thread (§31): Dispatchers.IO or injected AppDispatchers
- Cancellation cooperates (§33): `catch (e: CancellationException) { throw e }` before generic handling (§34/§79)
- Single-flight health refresh (§35): Mutex + shared Deferred, prevents Settings/Dashboard/Lifecycle all running expensive checks simultaneously
- Stale result protection (§36): generation counter, probe gen 4 not overwritten by late gen 3

## 5. Security

- No private AnkiDroid storage (§60): `/data/data/com.ichi2.anki`, `collection.anki2`, SQLite file access, media folder traversal strictly prohibited, even on rooted/debug devices. Public provider/API only.
- Content URI validation (§61): scheme, authority, ownership validated before passing to media/render layer (future)
- No arbitrary provider writes (§62): no `rawUpdate(uri, ContentValues)` exposed to upper layers, only typed operations
- Minimal permissions (§63): no new Android permissions in GATE 04, avoids storage, QUERY_ALL_PACKAGES, broad file access

## 6. Diagnostics

Anki section enhanced (§64/§121):

```
ANKI / ANKIDROID

Backend: AnkiDroid Local
Health: Ready
Provider: Available
API Spec: 2 (published)
Package Version: 2.24.1

Capabilities (API):
Deck Listing       Supported
Review             Supported
Media              Supported
...

Implementation:
Deck Listing       Pending GATE 05
Review             Pending GATE 06
...

Last Probe: 12ms ago
Probe Latency: 20ms
Last Error: None
```

Diagnostics distinguishes support from implementation (§65):

```
Provider supports rating endpoint
but Study-Agent rating implementation belongs to GATE 11
→ API supported, Integration pending
```

Logging (§66):

```
ANKI_HEALTH_CHECK_STARTED
ANKI_HEALTH_READY
ANKI_PERMISSION_REQUIRED
ANKI_CAPABILITIES_UPDATED
ANKI_PROVIDER_FAILURE
```

Log redaction (§67): never question, answer, note fields, tags, media contents.

Metrics (§68) if system supports: health_probe_count, success, failure, latency, capability_probe_latency.

## 7. Testing

### 7.1 Unit Tests (JVM, no AnkiDroid)

| Test File | Scenarios |
|---|---|
| AnkiDroidProviderClientTest | Cursor closes on success/failure, SecurityException maps, cancellation propagates, null Cursor handled, missing provider, malformed values, empty vs failure distinction |
| AnkiDroidCapabilityProbeTest | truthful capabilities, API vs implementation, unknown spec → UNKNOWN, below min → UNSUPPORTED, newer spec → safely evaluated, optional absence → backend still usable |
| AnkiDroidErrorMapperTest | SecurityException → PermissionRequired, collection not ready, timeout, endpoint unavailable, unsupported API, contract mismatch, unexpected → Unknown, diagnostic info sanitized |
| AnkiDroidGatewayTest | provider ready → Ready, missing → BackendUnavailable, permission denied → PermissionRequired, collection not ready, unsupported spec, unknown newer spec, empty query, null result, missing required/optional field, SecurityException, cancellation, timeout, 10 concurrent refreshes → consistent, stale probe ignored, optional capability unavailable → backend still usable, AnkiDroid ready + PC offline valid, no AnkiDroid → app still usable |
| AnkiDroidBackendTest | id = ANKIDROID_LOCAL, availability/capability flows, refresh updates state, getDecks → UnsupportedAction not empty, beginReview/nextCard/commitRating → Unsupported, cancellation propagates, concurrent refresh consistent, independence from PC Agent, optional absence → still usable |
| AnkiDroidIntegrationStateTest | initial state Checking/NONE, compatibility policy min/max, api capabilities for spec, forward compat, core set |

All tests use fakes, no ContentResolver mock everywhere (§128): internal seam `AnkiDroidProviderClient` so most tests stay simple JVM.

### 7.2 Instrumented Tests (device only, optional)

Validate against actual AnkiDroid provider when available:

- provider resolves
- health probe succeeds
- API spec reads correctly
- collection readiness works
- no mutation occurs (§21)

Normal CI remains deterministic (§130): real AnkiDroid tests are dedicated/optional/manual until CI provisions it.

### 7.3 Manual Real-Device Matrix

| Scenario | Expected |
|---|---|
| AnkiDroid installed and configured | Ready |
| AnkiDroid missing | NotInstalled, app still usable |
| AnkiDroid disabled | ProviderUnavailable |
| AnkiDroid first-run incomplete | CollectionNotInitialized |
| Study-Agent background/foreground | recovers without restart |
| AnkiDroid updated | capability cache invalidated, reprobe |
| PC offline | AnkiDroid Ready + PC Disconnected valid |
| Both ready | both Ready, no auto-selection unless selector invoked |

NOT RUN in this environment — no configured AnkiDroid device/emulator (§131).

## 8. Invariants (GATE 04)

| ID | Invariant |
|---|---|
| INV-ANKI-GW-01 | Only the AnkiDroid integration layer directly accesses public AnkiDroid provider APIs. |
| INV-ANKI-GW-02 | No Cursor escapes the gateway. |
| INV-ANKI-GW-03 | Every provider resource is closed deterministically. |
| INV-ANKI-GW-04 | Cancellation is never converted into a domain failure. |
| INV-ANKI-GW-05 | Health probes perform no scheduling or collection mutation. |
| INV-ANKI-GW-06 | Capability state is derived from evidence, never from backend name alone. |
| INV-ANKI-GW-07 | Optional capability absence does not make the whole backend unavailable. |
| INV-ANKI-GW-08 | A stale probe result cannot overwrite newer integration state. |
| INV-ANKI-GW-09 | Provider failures are translated into typed Anki domain errors. |
| INV-ANKI-GW-10 | Backend-specific Android types do not cross into core/anki. |
| INV-ANKI-GW-11 | AnkiDroid availability remains independent from PC Agent availability. |
| INV-ANKI-GW-12 | Runtime health/capabilities are recomputable and are not persisted as permanent truth. |
| INV-ANKI-GW-13 | Unsupported and empty results remain semantically distinct. |
| INV-ANKI-GW-14 | GATE 04 performs no Anki review mutation. |

## 9. What is NOT Implemented (Deferred to GATE 05+)

Explicitly:

- Deck UI / LibraryScreen / DeckDetailsScreen (GATE 14)
- Real deck listing (GATE 05)
- Deck counts
- Scheduled cards / ReviewInfo pipeline (GATE 06)
- Card display / WebView / Card Renderer (GATE 08)
- Media rendering / Media read/write (GATE 09)
- Rating commit / Ease writes (GATE 11)
- Bury / Suspend
- Note editing / Note create
- Card browser / search UI
- Real study through AnkiDroid

GATE 04 may perform safe bounded read probes (provider metadata, spec version, small collection readiness probe, small capability probe) but NOT full deck loading, card review queue consumption, or mutations (§92).

## 10. Files Added

| File | Purpose |
|---|---|
| `data/anki/ankidroid/AnkiDroidCompatibilityPolicy.kt` | min spec, capability mapping, forward compat, core set |
| `data/anki/ankidroid/AnkiDroidProviderClient.kt` | safe provider operations, Cursor closure, null handling, package version |
| `data/anki/ankidroid/AnkiDroidErrorMapper.kt` | maps Android/provider failures to typed AnkiError |
| `data/anki/ankidroid/AnkiDroidMapper.kt` | provider → domain conversion, validation, strict/lenient |
| `data/anki/ankidroid/AnkiDroidIntegrationState.kt` | single source of truth, metadata, health snapshot, capability report |
| `data/anki/ankidroid/AnkiDroidCapabilityProbe.kt` | read-only capability probing, truthful reporting |
| `data/anki/ankidroid/AnkiDroidHealthProbe.kt` | side-effect free health probing, latency, package version |
| `data/anki/ankidroid/AnkiDroidGateway.kt` | gateway boundary, single-flight, stale protection, integration state |
| `data/anki/ankidroid/AnkiDroidBackend.kt` | real backend implementing AnkiBackend, exposes flows, Unsupported truthfully |
| `test/.../AnkiDroidGatewayTest.kt` | gateway contract tests |
| `test/.../AnkiDroidCapabilityProbeTest.kt` | capability truthfulness tests |
| `test/.../AnkiDroidErrorMapperTest.kt` | error mapping tests |
| `test/.../AnkiDroidBackendTest.kt` | backend behavior tests |
| `test/.../AnkiDroidProviderClientTest.kt` | provider client resource safety tests |
| `test/.../AnkiDroidIntegrationStateTest.kt` | integration state & compatibility tests |

## 11. Files Modified

| File | Reason |
|---|---|
| `data/anki/ankidroid/AndroidAnkiDroidProbe.kt` | implements AnkiDroidProviderClient, adds getPackageVersion and safeQuery with Cursor safety |
| `di/AppContainer.kt` | wires provider client, capability probe, health probe, gateway, backend; registry now includes real backend; health repository reuses same detector/healthCheck for consistency; diagnostics repository receives gateway/backend |
| `data/repository/DiagnosticsRepository.kt` | adds GATE 04 capability matrix, API vs implementation, metadata, provenance |
| `test/.../AnkiDroidIntegrationIsolationTest.kt` | allows new Android-facing files (ProviderClient, Mapper) |

## 12. Build Validation

```
./gradlew --stop
./gradlew clean
./gradlew testDebugUnitTest
./gradlew lint
./gradlew assembleDebug
./gradlew assembleRelease
```

Unit tests: all AnkiDroid tests pass (JVM, no device).
Lint: passes (no new warnings in AnkiDroid layer).
Debug/Release: assembles (no new permissions, no storage).

Instrumented: NOT RUN — no configured AnkiDroid device/emulator environment.

## 13. Final Question

> Is the AnkiDroid gateway sufficiently isolated, truthful and reliable to begin consuming real deck data in GATE 05?

YES

- Gateway boundary isolates ContentResolver/Cursor/Uri/FlashCardsContract behind internal layer
- Provider resources closed deterministically via use {}
- Capabilities derived from evidence (spec + readiness), not backend name
- Health is side-effect free, no mutation
- Errors mapped to typed AnkiError, distinguishable (permission/collection/provider)
- Single-flight + stale protection prevents races
- Cancellation propagates
- Optional capability absence does not disable core backend
- Backend returns UnsupportedAction truthfully, not empty list
- Diagnostics shows API support vs implementation pending
- PC Agent state remains independent
- Existing app functionality unchanged (AnkiDroid not required)
- No real review mutation introduced

Ready for GATE 05 deck listing.

# GATE 21 — Multi-Backend Anki Selection / Hybrid Mode — Final Report

**Companion documents:**

- `docs/GATE_21_MULTI_BACKEND_DISCOVERY.md` — Phase A, facts only
- `docs/GATE_21_MULTI_BACKEND_CONTRACT.md` — Phase B, contract lock

**Result:**

```text
GATE 21 LOCKED

MULTI-BACKEND:                    SUPPORTED
HYBRID APPLICATION:               SUPPORTED
HYBRID STUDY SESSION:             PROHIBITED
MID-SESSION FAILOVER:             PROHIBITED
TRANSACTION CROSS-BACKEND REPLAY: PROHIBITED
```

---

## 1. Discovery Report

See `docs/GATE_21_MULTI_BACKEND_DISCOVERY.md` (DISCOVERY-21-01 … 21-37). Headline facts that
shaped every decision:

1. **Only one real backend exists.** No `PcAnkiBackend` in `app/src`; the PC agent is not in
   this repository.
2. **Collection identity is unavailable.** AnkiDroid refs are built with
   `collectionKey = null` (`AnkiDroidDeckMapper.kt:93/114`), so
   `AnkiDroidBackend.collectionMatchesBoundSession` returns `true` on an unknown key —
   AnkiDroid profile/collection changes are undetectable.
3. **Resource identity is already backend-qualified** and structurally cannot be unqualified.
4. **Study runtime already routes by locked identity** (`effect.session.context.backendId`,
   `effect.turn.backendId`, `commitId.backendId`).
5. **There was no persisted backend preference** and **no capability-requirement concept**, so
   no resolution could be capability-constrained.
6. **Note mutation/creation recovery was pinned to one backend** — fail-closed, but a latent
   multi-backend defect.
7. **The branch did not compile at the base commit** (two GATE 19 leftovers), so no prior
   "green build" claim was verifiable.

## 2. Contract Lock

See `docs/GATE_21_MULTI_BACKEND_CONTRACT.md` (DECISION-21-01 … 21-39), ending in:

```text
GATE 21 CONTRACT: LOCKED
STUDY SESSION BACKEND:      IMMUTABLE
MID-SESSION FAILOVER:       PROHIBITED
TRANSACTION RECOVERY:       ORIGINAL BACKEND ONLY
IMPLEMENTATION AUTHORIZED:  YES
```

## 3. Backend identity model

### Backend identity

| File | Added by GATE 21 |
|---|---|
| `core/anki/AnkiBackendId.kt` | pre-existing (GATE 01) — `AnkiBackendId`, `AnkiBackendMode` |
| `core/anki/AnkiBackendDescriptor.kt` | **new** — `AnkiBackendKind`, `AnkiBackendLocality`, `AnkiBackendId.kind`, `AnkiBackendKind.locality`, `AnkiBackendDescriptor` |

`AnkiBackendDescriptor` is a **snapshot** (id + availability + capabilities + derived
kind/locality) and never exposes the implementation object, a transport detail or a secret. Its
`init` refuses a kind/locality that contradicts the identity — the same "structurally
self-refuting data" rule `AnkiSessionContext` applies to a foreign collection.

Because `AnkiAvailability.Ready(capabilities)` carries a *second* capability snapshot that can
disagree with the live projection mid-transition, `AnkiBackendDescriptor.supports` requires
**both** projections to agree (fail closed, matching GATE 01's existing rule).

### Collection identity

Unchanged and honestly absent: `AnkiCollectionIdentity(backendId, collectionKey)` with
`null = unknown`. GATE 21 adds no sentinel, no default, no fabricated equality, and validates a
collection only when both the record and the live backend have a key.

### Resource identity qualification

Audited, not rewritten (IMPLEMENTATION-21-02's "do not unnecessarily rewrite mature types"):
`AnkiCollectionIdentity`, `AnkiDeckRef`, `AnkiNoteRef`, `AnkiCardRef`, `AnkiNoteModelRef`,
`AnkiCardTemplateRef` all take `backendId` as a required parameter and mix it into `stableKey`.
`AnkiMediaRef` and `AnkiFilteredSessionRef` do not exist; they must be created qualified when
they are.

## 4. Backend resolution model

| File | Role |
|---|---|
| `core/anki/AnkiBackendCapabilityRequirement.kt` | **new** — `AnkiRequiredCapability` (19 entries) + the single `AnkiCapabilities.supports` mapping table + `STUDY_SESSION_REQUIREMENTS` |
| `core/anki/AnkiBackendResolver.kt` | **new** — `BackendResolutionRequest`, `BackendResolutionResult` (6 variants), `BackendSelectionReason`, `AnkiBackendResolver` |
| `core/anki/AnkiBackendSelector.kt` | **rewritten** — registry gains `registered`/`descriptors()`; the selector is now a thin adapter over the resolver, so **one** policy serves study start and read contexts |
| `core/anki/AnkiBackendPreference.kt` | **new** — `AnkiBackendPreferenceStore`, `AnkiBackendRoutingDiagnostics` |
| `core/anki/AnkiBackendStatus.kt` | **new** — `AnkiBackendStatusEntry`, `AnkiBackendStatusSnapshot`, `AnkiBackendStatusCenter`, `AnkiStudyBackendLockTracker` |
| `core/anki/AnkiTransactionRecoveryRouting.kt` | **new** — `BackendTransactionBlockReason`, `BackendTransactionRoute`, `AnkiTransactionRecoveryPlanner` |
| `data/preferences/DataStoreAnkiBackendPreferenceStore.kt` | **new** — persisted preference |
| `data/preferences/PreferencesDataStore.kt`, `AppSettingsPreferencesCodec.kt` | **modified** — `anki_backend_mode` key, `ankiBackendModeFlow`, `setAnkiBackendMode`, cleared on settings reset |

**AUTO ordering (locked, DECISION-21-08):** mode filter → reachable → capability-satisfying →
`LOCAL_DEVICE` first → a single `REMOTE_AGENT`; several ready remote profiles are a
deterministic **refusal** (`Ambiguous`), never a list-order pick. Ties break on
`orderingKey = "<kindOrdinal>:<stableId>"`.

The selector adapter preserves GATE 01's observable behaviour exactly, including its
`UnavailableReason` and error mapping — which is why all 10 pre-existing
`AnkiBackendSelectorTest` cases and the 9 `AnkiArchitectureContractTest` resolution cases still
pass unchanged.

## 5. Session ownership

```text
Start Study (Home / Deck Details / voice)
  ↓ AnkiLocalStudyStarter.start(options)
  ↓ preference = options.preference ?: preferenceProvider()     ← sampled ONCE
  ↓ AnkiBackendSelector(registry).resolve(preference)           ← delegates to AnkiBackendResolver
  ↓ registry.find(backendId) → refreshAvailability() → re-resolve (fail closed on drift)
  ↓ resolve the deck against the LIVE collection
  ↓ freeze ReviewerActionCapabilities (GATE 13)
  ↓ dispatch AnkiStudyRequest(studySessionId, preference, deck, frozen caps)
  ↓ AnkiStudyEffectExecutor.begin()
      ↓ re-resolve (the only resolution in study runtime)
      ↓ ledger + action-ledger health and recovery blockers  ← before any scheduler traffic
      ↓ getDecks() and require the exact deck
      ↓ AnkiSessionContext(backendId, collection, deckRef, startedAt, capability snapshot, studySessionId)
      ↓ backend.beginReview(context)
      ↓ if Success → sessionLocks.locked(context)              ← the lock becomes visible
  ↓ every later scheduler/mutation call
      nextCard    → registry.find(effect.session.context.backendId)
      hydrate     → registry.find(effect.turn.backendId)
      rating      → registry.find(commitId.backendId)
      reviewer act→ registry.find(record.backendId)
  ↓ EndReview → sessionLocks.released(studySessionId)           ← the only release
```

`AnkiLocalStudyStarter` gained `preferenceProvider: () -> AnkiBackendMode` (default
`LOCAL_PREFERENCE`, so existing callers are unchanged) and `Options.preference` is now nullable
— `null` means "use the app's persisted preference".

## 6. Transaction ownership matrix

| Transaction | Backend source of truth | Recovery routing |
|---|---|---|
| ReviewCommit | `ReviewCommitRecord.commitId.backendId` (persisted) | `ReviewCommitLedger.pendingRecovery(backendId)` / `recoveryBlocker(backendId, …)`; `AnkiReviewCommitReconciler(registry)` |
| ReviewerAction | `ReviewerActionRecord.backendId` + `collectionRef` (persisted) | `AnkiReviewerActionReconciler`: `registry.find(record.backendId)`, refuses a foreign card/collection |
| NoteMutation | `NoteMutationRecord.backendId` (persisted) | ledger scoped by `backendId`; coordinator refuses `BackendMismatch` |
| NoteCreation | `NoteCreationRecord.backendId` (persisted) | ledger scoped by `backendId`; coordinator refuses `BackendMismatch` |
| Custom Study state | **not implemented** (GATE 20 blocked) | n/a — would inherit the session lock |
| Grouping across families | — | `AnkiTransactionRecoveryPlanner.groupByOwnerBackend(records) { it.backendId }` |

No row reads the current global preference. `AnkiTransactionRecoveryPlanner` contains no
reference to `AnkiBackendResolver`, `AnkiBackendPreferenceStore` or `AnkiBackendMode`
(AUDIT-21-04, enforced by test).

## 7. Failover matrix

| Situation | Behaviour | Where enforced |
|---|---|---|
| Read before binding | AUTO may pick another eligible backend (the only fallback in GATE 21) | `AnkiBackendResolver` |
| Active Study | **never** switches; blocks on the locked backend | `AnkiStudyEffectExecutor` locked-identity lookups |
| Rating mutation | stays on `commitId.backendId`; a missing backend → typed failure, no replay | ledger + executor |
| Reviewer action | stays on `record.backendId`; missing → `Unresolved(BackendUnavailable)` | `AnkiReviewerActionReconciler` |
| Edit note | bound at open; `BackendMismatch` refusal rather than a redirect | `NoteMutationCoordinator` |
| Note creation | bound at open; `BackendMismatch` refusal rather than a redirect | `NoteCreationCoordinator` |
| Custom study | not implemented | — |
| Recovery | per-backend group; a missing/unreachable backend blocks **only its own group** | `AnkiTransactionRecoveryPlanner` |

## 8. Architecture audit matrix

Encoded as `app/src/test/java/com/studyagent/client/anki/Gate21ArchitectureAuditTest.kt`
(15 tests, source-text rules with comments stripped, same convention as GATE 12–18).

| Audit | Result | Evidence |
|---|---|---|
| AUDIT-21-01 single backend per session | PASS | `every scheduler and mutation call resolves through a locked or persisted identity` |
| AUDIT-21-02 no preference in runtime scheduler | PASS | same test asserts no `AnkiBackendResolver` / `preferenceStore` / `AnkiBackendPreferenceStore` in the executor |
| AUDIT-21-03 no mid-session failover | PASS | `no cross-backend failover exists anywhere in study runtime` |
| AUDIT-21-04 recovery routing | PASS | `transaction recovery routes by the persisted record identity` |
| AUDIT-21-05 collection validation | PASS | `a reconnected backend on a different collection stays blocked` + `absent collection identity … blocks nothing` |
| AUDIT-21-06 no cross-backend id reinterpretation | PASS | `every resource reference is backend-qualified`, `a session context refuses a collection identity belonging to another backend` |
| AUDIT-21-07 AUTO scope | PASS | `AUTO resolution exists only at pre-binding sites` |
| AUDIT-21-08 manual selection honesty | PASS | `manual selection never silently substitutes another backend` |
| AUDIT-21-09 read context isolation | PASS | `browser paging state cannot cross a backend boundary` + `AnkiLibraryRepository` cache guard |
| AUDIT-21-10 editor binding | PASS | `bound editors and drafts resolve their backend from the route, not the preference` |
| AUDIT-21-11 creation binding | PASS | same test (`addNoteBackendId`) + `the note coordinators refuse a foreign backend instead of re-routing` |
| AUDIT-21-12 custom study binding | N/A | GATE 20 unimplemented — nothing to bind |
| AUDIT-21-13 capability-based resolution | PASS | `the presentation layer never branches on a backend name` |
| AUDIT-21-14 source-of-truth separation | PASS | `the four sources of truth are separate components` |
| AUDIT-21-15 diagnostics accuracy | PASS | `diagnostics keep preferred, resolved, locked and transaction backends apart` |
| INV-21-19/20 no migration/merging | PASS | `gate 21 adds no cross-backend migration, mapping or merging` |

## 9. Verification matrix

| Verification | Result | Evidence |
|---|---|---|
| VER-21-01 | PASS | `Gate21BackendResolutionTest.manual AnkiDroid selection resolves AnkiDroid when it can serve the operation` |
| VER-21-02 | PASS | `manual PC selection resolves PC even though AnkiDroid is also ready` |
| VER-21-03 | PASS | `manual selection of an unavailable backend blocks instead of falling back`, `manual PC selection with no PC registered is not implemented, not redirected` |
| VER-21-04 | PASS | `AUTO with both available prefers the on-device collection` |
| VER-21-05 | PASS | `AUTO falls back to a single ready remote profile before binding only` |
| VER-21-06 | PASS | `a required capability removes a backend that is reachable but cannot serve it`, `capability resolution never inspects a backend name` |
| VER-21-07 | PASS | `Gate21SessionBackendLockTest.session start captures the backend identity and publishes it as a lock`, `a refused start publishes no lock` |
| VER-21-08 | PASS | `a preference change during study never re-routes an active session` (asserts `pc.physicalCommitCalls == 0` and unchanged `pc.getDecksCalls`) |
| VER-21-09 | PASS | `losing the locked backend blocks the session instead of failing over` |
| VER-21-10 | PARTIAL | Reconnect *routing* is validated by identity + collection where available (`AnkiTransactionRecoveryPlanner`); the AnkiDroid path has **no collection identity to validate** (DISCOVERY-21-03), so session-level reconnect continuity is unverifiable here |
| VER-21-11 | PASS | `a reconnected backend on a different collection stays blocked` |
| VER-21-12 | PASS | `a preference change during study never re-routes an active session` — the commit effect's `commitId.backendId` is the locked backend and the other backend records zero mutations |
| VER-21-13 | PASS | pre-existing `Gate11eAggregateAuditTest.a backend preference change never redirects an active transaction` and `an unresolved transaction is never rebound to the newly preferred backend` (still green) |
| VER-21-14 | PASS | AUDIT-21-04 + pre-existing `Gate13ArchitectureAuditTest` / `ReviewerActionCoordinatorTest` (still green) |
| VER-21-15 | PASS (routing) | AUDIT-21-10 (`AppNavHost` resolves the editor backend from `editCardRef.backendId`) + `NoteMutationRefusal.BackendMismatch`. No new Compose-level behavioural test — see §10 |
| VER-21-16 | PASS (routing) | AUDIT-21-11 (`addNoteBackendId`) + `NoteCreationRefusal.BackendMismatch`. Same limit as VER-21-15 |
| VER-21-17 | BLOCKED | GATE 20 is unimplemented; no custom-study context exists to bind |
| VER-21-18 | PASS | pre-existing `CardBrowserViewModelTest.backend switch discards old identity and loads only the newly bound backend`; Library: `LibraryBackendAvailabilityTest.backend loss is visible and recovery refreshes rather than mixing stale data` + the `cached.backendId == backend.id` cache guard |
| VER-21-19 | PASS | pre-existing `CardBrowserViewModelTest.late response from the previous backend cannot publish after a backend switch` |
| VER-21-20 | PASS | pre-existing `AnkiArchitectureContractTest` (equal numeric ids under different backends are unequal) + `Gate21BackendSelectionUiTest.a display label is never an identity` |
| VER-21-21 | PASS | `AnkiDeckRef.stableKey` includes `backendId.stableId`; `every resource reference is backend-qualified` |
| VER-21-22 | PASS | `AnkiNoteRef.stableKey` includes `backendId.stableId`; same audit test |
| VER-21-23 | PASS | `unresolved transactions for two backends are grouped by their own persisted identity`, `each backend group routes to its own registered backend` |
| VER-21-24 | PASS | `a missing backend blocks only its own group and never borrows the other one`, `an unreachable backend keeps its transactions blocked while another backend is ready` |
| VER-21-25 | PARTIAL | The session object is **not** persisted across process death (DISCOVERY-21-12); the durable ledger restore is covered by the still-green `Gate11eAggregateAuditTest.restart from …` set. A restored session cannot inherit a preference because the lock lives with the session |
| VER-21-26 | PASS | `AUTO is deterministic for identical inputs` (500 repetitions, both registry orders), `resolution is pure - it never probes, refreshes or rewrites the preference` |
| VER-21-27 | PASS | AUDIT-21-09 + `AnkiLibraryRepository.publishFailure` reuses a cache only for the same backend id |
| VER-21-28 | PASS | `diagnostics keep preferred, resolved, locked and transaction backends apart` |
| VER-21-29 | PASS | `1000 turns with preference and availability churn never move a session between backends` — asserts 0 mid-session switches, 0 cross-backend commits, 0 wrong-backend next-card reads, 0 cross-backend mutations, against a ready PC backend in the same registry |
| VER-21-30 | PASS | Full harness suite: `RESULT classes=187 tests=2211 passed=2211 failed=0` (GATE 11–20 regression set included) |

**Totals: 27 PASS, 2 PARTIAL, 1 BLOCKED.** No FAIL.

## 10. Residual risks

1. **No authoritative cross-backend collection identity.** Nothing can prove two collections are
   the same, so no future feature may infer it. Collection validation exists but is inert until a
   backend reports a real key.
2. **AnkiDroid collection/profile change is undetectable** mid-session
   (`collectionMatchesBoundSession` returns `true` on an unknown key). This is the strongest
   argument against mid-session failover, and it is a *real* unknown, not a theoretical one.
3. **PC reconnect identity uncertainty.** `PcAgent(profileId)` is configuration-derived; a
   reconnect under the same profile proves identity, not collection continuity.
4. **Backend-specific capability differences** are only knowable per backend; with one backend
   implemented, the asymmetry matrix cannot be filled without fabricating it.
5. **Long backend outages** leave transactions blocked by design. There is no expiry, no
   escalation and no queue — deliberately, since a blind replay across a long gap is exactly what
   INV-21-03/05 forbid. GATE 22 must define degradation without making those records replayable.
6. **Verification depth limit.** `SettingsScreen`, `StudyScreen` and `AppNavHost` are not in the
   harness's Compose compile scope, so their GATE 21 additions are verified by the source audit
   and by the pure UI-model tests, not by an executed Compose test. A Gradle
   `assembleDebug`/`connectedDebugAndroidTest` run is still required for those three files.
7. **Gradle was not run in this sandbox** (no JDK/Android SDK available); all evidence comes from
   the repository's own JVM harness, whose deviations are documented in
   `tools/jvm-harness/README.md`.

## 11. Invariants

| ID | Invariant | Status | Where |
|---|---|---|---|
| INV-21-01 | Multi-backend ≠ multi-scheduler session | HELD | one locked `AnkiSessionContext` per session |
| INV-21-02 | Every active session has one immutable backend | HELD | `session start captures…`, `recording a lock twice…` |
| INV-21-03 | Every durable transaction stores and preserves backend ownership | HELD | 4 record types; AUDIT-21-04 |
| INV-21-04 | Recovery never uses AUTO routing | HELD | planner has no resolver/preference reference (test-enforced) |
| INV-21-05 | Mid-session failover prohibited | HELD | AUDIT-21-03 + endurance test |
| INV-21-06 | Availability ≠ collection equivalence | HELD | no equivalence code exists; collection check requires two known keys |
| INV-21-07 | Matching resource ids ≠ cross-backend identity | HELD | qualified refs; `AnkiArchitectureContractTest` |
| INV-21-08 | Preferred backend ≠ active-session truth | HELD | separate fields; Study indicator reads the lock |
| INV-21-09 | AUTO resolves only before binding | HELD | AUDIT-21-07 |
| INV-21-10 | Manual selection never silently falls back | HELD | 2 resolution tests; DECISION-21-26 |
| INV-21-11 | Capabilities checked before binding | HELD | `RequiredCapabilityUnavailable` |
| INV-21-12 | References stay backend/collection qualified | HELD | AUDIT-21-06 |
| INV-21-13 | Paging state cannot cross backends | HELD | cursor consistency key + `ForeignQuery` |
| INV-21-14 | Bound editors cannot silently switch | HELD | route identity + `BackendMismatch` |
| INV-21-15 | Bound creation drafts cannot silently switch | HELD | route identity + `BackendMismatch` |
| INV-21-16 | Custom study cannot migrate backend | N/A | GATE 20 unimplemented |
| INV-21-17 | Reconnect validates identity where possible | HELD (partial reach) | planner collection check; no key to check on AnkiDroid |
| INV-21-18 | Anki sync does not authorize switching | HELD | no sync data exists; no switching path exists |
| INV-21-19 | No cross-backend migration or merging | HELD | `gate 21 adds no cross-backend migration, mapping or merging` |
| INV-21-20 | GATE 11–20 invariants not weakened | HELD | 2161 pre-existing tests still pass unchanged |

---

## Build & regression validation

No JDK or Android SDK exists in this sandbox, so `./gradlew` could not be run. Evidence comes
from the repository's own Gradle-free harness (`tools/jvm-harness`), which compiles `app/src/main`
(non-Compose), the ViewModel/pure-UI layer, `app/src/debug` fixtures and all JVM unit tests.

| Step | Command | Result |
|---|---|---|
| Baseline (after fixing the two pre-existing breaks) | `build.sh all` + `run.sh` | `main errors: 0`, `compose errors: 0`, `RESULT classes=182 tests=2161 passed=2161 failed=0` |
| DI wiring (harness excludes `di/` by default) | `HARNESS_EXTRA_MAIN_SRC="com/studyagent/client/di/AppContainer.kt" build.sh main` | `main errors: 0 (files: 306)` |
| Final full suite | `build.sh main` + `build.sh test` + `run.sh` | `main errors: 0 (files: 305)`, `test errors: 0 (files: 217)`, `RESULT classes=187 tests=2211 passed=2211 failed=0` |
| GATE 21 subset | `run.sh "Gate21"` | `RESULT classes=5 tests=50 passed=50 failed=0` |

Still outstanding, and required before release: `./gradlew clean testDebugUnitTest lint
assembleDebug assembleRelease`, and `connectedDebugAndroidTest` on a device with AnkiDroid.

## Files changed

**New (7 production, 4 test):**

```text
app/src/main/java/com/studyagent/client/core/anki/AnkiBackendDescriptor.kt
app/src/main/java/com/studyagent/client/core/anki/AnkiBackendCapabilityRequirement.kt
app/src/main/java/com/studyagent/client/core/anki/AnkiBackendResolver.kt
app/src/main/java/com/studyagent/client/core/anki/AnkiBackendPreference.kt
app/src/main/java/com/studyagent/client/core/anki/AnkiBackendStatus.kt
app/src/main/java/com/studyagent/client/core/anki/AnkiTransactionRecoveryRouting.kt
app/src/main/java/com/studyagent/client/data/preferences/DataStoreAnkiBackendPreferenceStore.kt
app/src/main/java/com/studyagent/client/ui/components/anki/AnkiBackendUiModels.kt
app/src/test/java/com/studyagent/client/anki/Gate21BackendResolutionTest.kt
app/src/test/java/com/studyagent/client/anki/Gate21TransactionRecoveryRoutingTest.kt
app/src/test/java/com/studyagent/client/anki/Gate21ArchitectureAuditTest.kt
app/src/test/java/com/studyagent/client/study/Gate21SessionBackendLockTest.kt
app/src/test/java/com/studyagent/client/ui/Gate21BackendSelectionUiTest.kt
```

**Modified:**

```text
core/anki/AnkiBackendSelector.kt        registry descriptors + selector delegates to the resolver
core/anki/ReviewCommit.kt               BASELINE FIX — TemplateNotFound branch (build was broken)
core/study/AnkiStudyEffectExecutor.kt   publishes/releases the session backend lock
data/repository/AnkiLocalStudyStarter.kt preferenceProvider; nullable Options.preference
data/preferences/PreferencesDataStore.kt ankiBackendModeFlow + setAnkiBackendMode
data/preferences/AppSettingsPreferencesCodec.kt anki_backend_mode key + reset
di/AppContainer.kt                      6 new components wired; executor + starter wired
ui/navigation/AppNavHost.kt             Settings + Study get the new components
ui/screens/settings/SettingsViewModel.kt backend selection state + selectAnkiBackend
ui/screens/settings/SettingsScreen.kt   backend selector card + switch-effect notice
ui/screens/study/StudyViewModel.kt      lockedAnkiBackendLabel (from the lock, not the preference)
ui/screens/study/StudyScreen.kt         locked-backend indicator
app/src/test/.../AnkiCommitHarness.kt   optional sessionLocks
app/src/test/.../AnkiScheduledReviewContractTest.kt   BASELINE FIX — GATE 19 capability args
app/src/test/.../ankidroid/AnkiDroidBackendTest.kt    BASELINE FIX
app/src/test/.../ankidroid/AnkiDroidGatewayTest.kt    BASELINE FIX
app/src/test/.../ankidroid/AnkiDroidReviewSessionTest.kt BASELINE FIX
```

## Next gate

```text
GATE 22 — Offline / Graceful Degradation / Connectivity Recovery
```

GATE 21 hands GATE 22 one hard constraint: a transaction that is `Blocked` because its backend is
unreachable must **not** become blindly replayable because an offline queue now exists. Offline
read tolerance ≠ offline mutation queue.

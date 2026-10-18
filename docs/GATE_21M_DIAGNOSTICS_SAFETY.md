# GATE 21M — Diagnostics Safety and Read-Only Contract

**Date:** 2026-10-18 (UTC)
**Source HEAD:** `19c8a0d76169b18e4f2bf1787d107fbc96c41069`
**Primary files:**
`ui/components/anki/AnkiBackendDiagnosticsUi.kt`,
`ui/screens/diagnostics/DiagnosticsViewModel.kt`,
`ui/screens/diagnostics/DiagnosticsScreen.kt`,
`core/anki/AnkiUnresolvedTransactions.kt`,
`data/repository/LedgerUnresolvedTransactionReader.kt`
**Production changes in this run:** two wiring corrections: `DiagnosticsScreen.kt` (observe the
asynchronous read) and `AnkiBackendSectionUi.kt` (withhold a dead launcher action). Presentation logic otherwise unchanged.

---

## 1. Architecture

```text
AnkiBackendStatusCenter.states ─────────────┐
BackendPreferenceRepository.preference ─────┤
AppContainer.ankiBackendRouting ────────────┤
AnkiStudyBackendLockTracker.locks ──────────┤
AnkiIdentityAssurancePolicy ────────────────┼──> AnkiBackendDiagnosticsInput
AnkiBackend.currentCollectionIdentity ──────┤         (immutable, content-free)
AnkiBackend.customStudyCapabilities ────────┤
AnkiUnresolvedTransactionReader.read() ─────┘
                                                      │
                                                      ▼
                                       buildAnkiBackendDiagnosticsLines()
                                                      │
                                                      ▼
                                       DiagnosticsRowsCard (read-only)
```

The projection is pure. `DiagnosticsViewModel` reads it in `ankiBackendRows()` when the screen is
composed; it does not write, probe, retry, reset or reassign anything. The `anki-backends` item
observes `ankiPendingTransactions`, so the asynchronous ledger read replaces `NOT_PROJECTED` with the
real counts instead of being frozen on the first frame.

### What the ViewModel holds

| Dependency | Read surface | Write capability |
|---|---|---|
| `AnkiBackendStatusCenter.states` | observations | none |
| `BackendPreferenceRepository.preference` | current preference | none |
| `ankiBackendRouting` | existing four-role snapshot | none |
| `AnkiStudyBackendLockTracker.locks` | active locks | none |
| `AnkiBackendRegistry.registered` | identity + current collection discriminator + static custom-study claim | none |
| `AnkiUnresolvedTransactionReader` | one `read()` of four `unresolved()` projections | none (one method, no write path) |

The four ledgers are **not exposed** through `AppContainer`'s interface to the UI. The reader is
constructed with read lambdas only; it cannot reach a ledger, backend or coordinator.

---

## 2. Rendered fields

For each configured backend, in `stableId` order:

```text
Backend                         <stableId>
  kind / locality               <enum>/<enum>
  availability                  <AnkiAvailability.statusCode>
  unavailable reason            <token or NOT_APPLICABLE>
  retryability                  <enum name or NOT_APPLICABLE>
  capability evidence           STATIC_CLAIM / RUNTIME_NEGOTIATED / NONE
  collection identity           NOT_MEASURED
                                none published (identity unverifiable)
                                published (value withheld)
  <CAPABILITY>                  SUPPORTED/NOT_READY … (two dimensions, every requirement)
  CUSTOM_STUDY                  CUSTOM_STUDY_NOT_IMPLEMENTED
                                CUSTOM_STUDY_LIFECYCLE_NOT_IMPLEMENTED
                                NOT_PROJECTED
```

Plus:

```text
Preferred backend (token)       auto | explicit:<stableId> | explicit_kind:<KIND> | invalid_stored:<ISSUE>
Configured backends             none | <count>
Configured ANKIDROID_LOCAL      0 (no adapter wired in this build) | <count>
Configured PC_AGENT             0 (no adapter wired in this build) | <count>
Resolved backend                <stableId> | NOT_MEASURED | NOT_PROJECTED
Resolution reason               <enum name> | NOT_MEASURED
Locked session backend          <stableId> | NOT_MEASURED | NOT_PROJECTED
Locked session count            <count>
Transaction backend             <stableId> | NOT_MEASURED | NOT_PROJECTED
Active study session            none | <stableId> | N (no single owner named)
  study mode                    <class simple name>
  bound collection key          present | absent
  live collection key           present | absent
  collection assurance          <verbatim GATE 21C category>
  frozen review capability      SUPPORTED | UNSUPPORTED
Read-context owners             NOT_PROJECTED (no projection wired) | one line per owner
Unresolved REVIEW_COMMIT        NOT_PROJECTED | 0 | STATUS=count, …
Unresolved REVIEWER_ACTION      …
Unresolved NOTE_MUTATION        …
Unresolved NOTE_CREATION        …
Unresolved FilteredSession      NOT_APPLICABLE (GATE 21L BLOCKED)
Report revision                 <token, when supplied>
```

`NOT_MEASURED`, `NOT_PROJECTED` and `NOT_APPLICABLE` are distinct on purpose:

| Token | Meaning |
|---|---|
| `NOT_MEASURED` | The surface has no observation for this field |
| `NOT_PROJECTED` | No read-only projection was supplied |
| `NOT_APPLICABLE` | The family/feature does not exist in this build |
| `0` | The projection was read and contains no unresolved records |

A family that could not be read is never rendered as `0`.

---

## 3. Transaction families

`AnkiTransactionFamily` preserves exactly the four durable families that exist:

```text
REVIEW_COMMIT
REVIEWER_ACTION
NOTE_MUTATION
NOTE_CREATION
```

Each unresolved record is reduced to:

```kotlin
data class AnkiUnresolvedTransactionFact(
    val family: AnkiTransactionFamily,
    val backendId: AnkiBackendId?,   // persisted owner, never re-derived
    val statusToken: String          // family's own enum name
)
```

- `ReviewCommitRecord.status.name`
- `ReviewerActionRecord.status.name`
- `NoteMutationRecord.status.name`
- `NoteCreationRecord.status.name`

Therefore `CONFLICT` and `AMBIGUOUS` remain distinct where the family defines them. They are never
collapsed into a generic “pending” bucket. A timeout is never rendered as proof of failure or success.

Records are grouped by the owner **persisted in the record**:

```kotlin
records.groupBy { backendOf(it) }
```

No grouping by current preference, resolved backend, readiness or kind is possible through this API.

### FilteredSession

There is no filtered-session lifecycle in this build. `AnkiTransactionFamilyUi.FILTERED_SESSION`
exists only so the omission is stated:

```text
NOT_APPLICABLE (no filtered-session lifecycle exists in this build; GATE 21L BLOCKED)
```

No fabricated records, counts, rebuild status or end status are produced.

---

## 4. Collection identity assurance

The diagnostics section renders the GATE 21C category **verbatim**:

```text
AUTHORITATIVE_MATCH
SESSION_CONTINUITY_VERIFIED
IDENTITY_UNVERIFIABLE(nominal)
IDENTITY_UNVERIFIABLE(degraded)
AUTHORITATIVE_MISMATCH
ForeignBackend
```

Rules:

- A collection **key value** is never rendered. Only presence (“present” / “absent”) and the category.
- Continuity is never reworded as “same collection”.
- Matching deck id, note id, profile name, endpoint or connection success does not create continuity.
- `AUTHORITATIVE_MISMATCH` is displayed as a mismatch, not as “reconnecting”.
- When no live observation exists, the category reflects the policy result; it is not upgraded to
  verified.
- The lock tracker's bound context is the session's own live binding
  (`boundIsLiveInThisProcess = true`); the live key comes from `AnkiBackend.currentCollectionIdentity`.

Evidence: `every identity assurance category is rendered verbatim`;
`the active session owner line reports assurance without inventing continuity`;
`a collection key is never measured into a value, only into presence`.

---

## 5. Forbidden capabilities (and absence proof)

The diagnostics surface has no:

- ledger write;
- status reset;
- forced recovery;
- retry-all;
- force-commit;
- clear/delete of unresolved records;
- cross-backend reassignment;
- mutation probe.

`AnkiBackendUiAction` is the only action vocabulary, and it contains no mutation action. The
diagnostics screen already had unrelated existing actions (copy summary, export, clear logs/timeline);
GATE 21M adds none of those to the Anki backend card and does not use them to touch Anki state.

The matrix row `AUDIT-21M-R2-13` (diagnostics read-only), together with
`Gate21mArchitectureAuditTest` “the read-only surfaces hold no mutation authority” and
“the four selection roles are read from the existing projection, never re-derived”, verifies:

- the reader interface has exactly one `suspend fun read()`;
- the reader is constructed from read lambdas only;
- the diagnostics ViewModel contains no ledger/coordinator type;
- the container interface does not expose the ledgers;
- no rendered row offers mutation/retry/reassignment;
- no Custom Study lifecycle control exists.

---

## 6. Security and privacy

### Rendered

- backend `stableId` (identity; for PC, the configured profile discriminator — never an endpoint);
- enum names and stable tokens;
- counts;
- presence booleans.

### Never rendered

- passwords, tokens, credentials, auth headers;
- credential-bearing URLs or endpoints;
- collection key values;
- card answers, note fields, tags, deck names, media paths, transcripts;
- raw exception messages or stack traces;
- raw stored preference text.

`AnkiBackendDiagnostics` has no free-text field, so there is no place for availability `detail` /
`reason` payloads. The diagnostics test injects hostile free text into
`ProviderUnavailable("content://…")`, `PermissionRequired("…key…")`,
`TemporarilyUnavailable("…")`, `Unsupported("…")` and `Fault(...)`, then asserts none of those strings
appears in any row.

Controller failure logging passes only `failure.javaClass.simpleName`. The test throws
`IllegalStateException("content://com.ichi2.anki/key-9f3a …")` and asserts neither the URL nor the key
reaches the log.

### Export / copy

The existing Diagnostics export (`DiagnosticsRepository.getExportText()`) and summary are **not**
changed by GATE 21M. The new backend rows are rendered on screen but are not silently appended to the
export. This is deliberate:

- the export path has its own privacy contract and `DiagnosticsExportPrivacyTest`;
- widening the export would require a separate privacy decision and test;
- the current state is recorded as a residual limitation, not as a completed export feature.

The matrix row `AUDIT-21M-R2-25` (scope discipline), via `Gate21mArchitectureAuditTest`
“the export path is unchanged and the new rows are not silently exported”, asserts that
`DiagnosticsRepository` is untouched and the screen has exactly one `ankiBackendRows()` call outside
the export helpers.

---

## 7. Failure presentation

Diagnostics use the domain vocabulary rather than inventing user-facing recovery:

| Situation | Rendered |
|---|---|
| Backend not configured | `Configured <KIND> 0 (no adapter wired in this build)` |
| Backend unavailable | `availability` token + `unavailable reason` + `retryability` |
| Permission missing | `PERMISSION_REQUIRED` (distinct from `NOT_INSTALLED`) |
| Authentication required | `AGENT_AUTHENTICATION_REQUIRED` (PC only, absent today) |
| Capability unsupported | `<CAPABILITY> UNSUPPORTED/NOT_READY` |
| Capability unknown | `<CAPABILITY> UNKNOWN/NOT_READY` or `UNKNOWN/UNKNOWN` |
| Collection mismatch | `AUTHORITATIVE_MISMATCH` |
| Identity unavailable | `IDENTITY_UNVERIFIABLE(nominal|degraded)` |
| Protocol mismatch | `ClientOutdated` / `ServerOutdated` tags from the existing domain, if carried by the backend |
| Operation outcome ambiguous | family status token `AMBIGUOUS`, displayed verbatim |

The surface does not offer a fabricated recovery. The only recovery path is each family's existing
coordinator, not a diagnostics button.

---

## 8. Wiring at HEAD

`DiagnosticsViewModel` is constructed at `AppNavHost.kt:576` with:

```kotlin
ankiBackendStates = container.ankiBackendStatusCenter.states
ankiBackendPreference = container.backendPreferenceRepository.preference
ankiBackendRouting = container.ankiBackendRouting
ankiSessionLocks = container.ankiStudyBackendLocks
ankiBackendRegistry = container.ankiBackendRegistry
ankiUnresolvedTransactions = container.ankiUnresolvedTransactions
```

The unresolved read happens once in `init` (`reader.read()`), not per recomposition. The
`anki-backends` item collects the resulting `ankiPendingTransactions` state and recomputes the rows
when it changes; without that observation the card would never leave `NOT_PROJECTED`. If no reader is
wired, the state remains `NOT_READ`, which renders `NOT_PROJECTED`, not `0`.

`readContextOwners` is not supplied because GATE 21F's `AnkiReadContextController` has no public
read-only projection of active leases at this HEAD. The diagnostics row therefore says
`NOT_PROJECTED (no read-context projection is wired into this surface)`. No label is derived from the
current preference. This is a documented verification gap, not a fabricated ownership claim.

# Backend Failure & Recovery Matrix

**Gate:** GATE 21B — Backend Contract Resolution & Architecture Lock (Sub-Gate 21B of 21A–21N)
**Repository:** `Drmusab/Study-Agent-Client`
**Branch:** `arena/e0f0b8dd-study-agent-client`
**HEAD:** `236cff8f2733203ccacbc479a1463b283fcf2ecf`
**Date:** 2026-10-11
**Status:** LOCKED — normative contract. **No production code was changed to produce this document.**
**Prerequisite:** GATE 21A PASS/LOCKED; zero source change since.
**Companion documents:** `GATE_21B_CONTRACT_LOCK.md`, `BACKEND_IDENTITY_CONTRACT.md`,
`BACKEND_SELECTION_POLICY.md`, `BACKEND_OWNERSHIP_INVARIANTS.md`, `GATE_21B_DECISION_EVIDENCE.md`.

This document owns **DECISION-21B-13** (no mid-session failover), **DECISION-21B-23** (reconnect and
identity revalidation), **DECISION-21B-26** (failure and failover matrix), **DECISION-21B-27**
(diagnostics and privacy) and **DECISION-21B-28** (security preconditions).

Labels: **IMPLEMENTED** (source-verified at this HEAD), **LOCKED** (normative for 21C–21N),
**FUTURE CONTRACT** (specification for a component that does not exist yet). Nothing here was measured on
a device: no Anki collection, no AnkiDroid installation and no PC agent were exercised.

---

## 1. The one rule behind every cell of this document

```text
Availability is not equivalence.
A backend being reachable says nothing about which collection it holds,
what it already applied, or whether another backend could stand in for it.
```

Therefore: **no failure converts a bound context into an unbound one, and no failure converts an
ambiguous mutation into a safe retry.**

---

## 2. Backend unavailable / capability loss / identity mismatch (LOCKED)

### 2.1 Backend unavailable (per context)

| Context | Original backend unavailable | Alternate backend available | Required behavior |
|---|---|---|---|
| **New AUTO read** (pre-binding) | AUTO may select another eligible instance — this is the only legal fallback | Yes — pre-binding only | `Resolved(other)` with reason; the read context is created fresh; no data from the unavailable backend is carried over |
| **New manual read** (pre-binding) | **Block** | No fallback | `ExplicitBackendUnavailable` with the instance's typed reason; UI shows a retry/refresh action |
| **Bound read** (already showing data from A) | **Invalidate / block / reload explicitly** | No silent substitution | Show a blocking state over the stale data; clear backend-scoped caches, cursors and counts; re-bind only on an explicit user action, producing a new context |
| **Active Study** | **Pause / block** | **No failover** | Preserve the session identity and its lock; surface the typed reason (`BackendUnavailable` / `PermissionRequired` / `CollectionUnavailable`); resume only against the original backend |
| **Pending rating** | **Preserve durable state** | No replay | Record stays as written; the owning family's status machine decides; the session refuses progression while it is unresolved |
| **Pending reviewer action** | **Preserve durable state** | No replay | Same rule, ownership in `ReviewerActionRecord.backendId` |
| **Active note edit** | **Bound context remains** | No redirect | Block Save with the typed refusal; preserve the draft locally; offer "retry" or "cancel and reopen elsewhere" |
| **Pending creation** | **Preserve durable state** | No recreate | The draft stays bound to A; nothing is created on B; an unresolved creation record recovers only through A |
| **Custom Study** | **Block / pause** | No migration | (Not implemented — GATE 20 BLOCKED. Contract fixed so 21L cannot weaken it.) |
| **Transaction recovery** | **Original backend only** | **No substitution** | `Blocked(BACKEND_UNREACHABLE)` per group |

### 2.2 Capability loss (LOCKED)

| Situation | Behavior |
|---|---|
| A capability is lost **before** binding (probe says no longer supported) | The instance becomes ineligible for operations requiring it → `RequiredCapabilityUnavailable` |
| A capability is lost **after** binding | The frozen `AnkiSessionContext.capabilities` / session capability freeze is not rewritten; the next operation that needs the lost capability may be **refused** (`usabilityError()` path) |
| A capability is lost while a mutation is unresolved | No effect on ownership; ambiguity semantics unchanged |
| A capability is (re)added after binding | Must **not** retroactively enable a path that was refused at binding time; it applies to new contexts only |

**LOCKED.** Capability loss never selects another backend, and capability gain never retroactively
permits an irreversible operation inside a context that was bound without it.

### 2.3 Identity mismatch (LOCKED)

| Situation | Behavior |
|---|---|
| `AUTHORITATIVE_MISMATCH` on a bound read | Invalidate the context; never reinterpret refs; require an explicit re-bind |
| `AUTHORITATIVE_MISMATCH` on a session | Block/pause the session; do **not** terminate silently and do **not** continue against the new collection; the user decides (end the session, or investigate) |
| `AUTHORITATIVE_MISMATCH` on transaction recovery | `Blocked(COLLECTION_MISMATCH)` for that group only |
| Degraded `IDENTITY_UNVERIFIABLE` | Mismatch-grade: block continuation and recovery for that binding (see `BACKEND_IDENTITY_CONTRACT.md` §3.2/§3.5) |
| Nominal `IDENTITY_UNVERIFIABLE` | Continue under the recorded category; never described as verified |

---

## 3. Reconnect and identity revalidation (LOCKED — DECISION-21B-23)

### 3.1 The five conditions are not equivalent

```text
R0  TRANSPORT_RECONNECTED        — a socket/health probe is answering again
R1  AGENT_AUTHENTICATED          — the peer accepted our credentials/handshake
R2  ANKI_BACKEND_READY           — the Anki backend beneath is initialized and usable
R3  COLLECTION_CONTINUITY_VERIFIED — the collection is provably the same one (key match)
R4  SESSION_RESTORATION_PERMITTED — the operation class's assurance cell allows continuation
```

**LOCKED.** Each level implies only itself. In particular, **R0/R1/R2 never imply R3**, and R3 never
implies R4 (a schedule operation may still be forbidden because its binding is gone or ambiguous).

**LOCKED.** A new successful network connection must never be used as evidence that a mutation was
not applied. The only evidence for "not applied" is the owning family's own proof (a durable record
that shows the mutation boundary was not entered).

### 3.2 Operation classes → mandatory conditions (LOCKED)

| Operation class | Mandatory conditions |
|---|---|
| New unbound read | R2 + R3(capability) |
| Existing bound read | R2 + R3(capability) + the binding's assurance cell permits `OP-R2` |
| New StudySession | R2 + R3(capability) + assurance cell permits `OP-S1` |
| Session continuation | R2 + R3(capability for the specific operation) + assurance cell permits `OP-S2` |
| New mutation | All of the above for its binding + the operation's own capability |
| Retry of a known non-applied mutation | Original backend at R2 + the family's non-application proof |
| Reconciliation of an ambiguous mutation | Original backend at R2 + the family's authoritative reconciliation capability (**not available on AnkiDroid**) |
| Recovery after process death | Original identity decodable + instance registered + R2 + checkable continuity (or the family's rule that tolerates nominal absence) |
| Session restoration (if ever durably added) | All of `BACKEND_OWNERSHIP_INVARIANTS.md` §2.5, including a valid backend identity and an allowed assurance cell |

### 3.3 Required reconnect cases (LOCKED)

| # | Case | Conservative behavior |
|---|---|---|
| 1 | **Same endpoint, same verified collection** | `AUTHORITATIVE_MATCH`. Continuation permitted if the operation class allows it. This is the only case that authorizes resumed bound work. |
| 2 | **Same endpoint, different verified collection** | `AUTHORITATIVE_MISMATCH`. Block. Bound read contexts invalidated; the session blocks; transaction groups become `Blocked(COLLECTION_MISMATCH)`. |
| 3 | **Same endpoint, collection unverifiable** | Nominal case (no key was ever produced, e.g. AnkiDroid): bound work continues **only** under the recorded nominal category, and the app must not claim verification. Degraded case (a key was recorded and is now missing): **blocked**, mismatch-grade. |
| 4 | **Different configured endpoint** | Same logical identity only if it is the same configuration record (identity is configuration-scoped, `BACKEND_IDENTITY_CONTRACT.md` §1.4). Continuity is **not** implied: the assurance rules decide, and with no key the case degrades to case 3. A *different configuration record* is a different instance → the old instance's records stay blocked (`BACKEND_NOT_REGISTERED`). |
| 5 | **Backend removed and recreated** | Same discriminator ⇒ same logical owner, records become eligible again, but continuity must be re-established first. Different discriminator ⇒ different owner; the old records stay orphaned. |
| 6 | **PC agent restarted with a different Anki profile** | Same `PcAgent(profileId)` if the profile id is unchanged → same logical owner, **different collection** as far as this architecture is concerned. With no key: nominal-unverifiable ⇒ case 3 nominal rules apply, and **no client-side detection is claimed**. When a key exists (future), it must mismatch and block. **This is the single most dangerous reconnect scenario in the design and is recorded as residual risk.** |

**LOCKED.** The app must never present case 6 as a successful reconnection in a way that implies the
same collection. Sub-Gate 21M's indicators must be capability/assurance-based, not "connected/disconnected".

---

## 4. Process death and restart (LOCKED)

| What survives | What does not |
|---|---|
| Durable ledgers with their `backendId` (review commits, reviewer actions, note mutations, note creations) | The `AnkiStudyBackendLockTracker` map (process memory) |
| The session/preference DataStore values (the preference is a mode, not a lock) | `AnkiSessionContext` (not `@Serializable`) |
| Navigation state that the platform restored | Any in-flight request, cursor, cache or generation counter |

**LOCKED sequence on restart:**

```text
1. load durable ledgers; their health check converts SUBMITTING left by a dead process into AMBIGUOUS
2. evaluate recovery blockers BEFORE any scheduler traffic
3. group unresolved records by their persisted backend identity
4. route each group to that exact registered instance (never AUTO, never the preference)
5. recover through each record's own family
6. a session is NOT restored: the user starts a new session, and any new session resolves fresh
7. a mid-turn session whose durable mutation is ambiguous must not silently resume review
```

IMPLEMENTED evidence: `AppContainer.kt:486-488` (ledgers loaded off the splash path, study state not
mutated); `AnkiStudyEffectExecutor.kt:556-567` (blockers checked before scheduler traffic);
`AnkiTransactionRecoveryPlanner` (per-group routing); `ReviewCommitLedger` decode-time `Unreadable`
guards.

**LOCKED.** "Recovery after process death" means transaction recovery. It never means resuming a
scheduler session on "whichever backend happens to be online".

---

## 5. Ambiguous mutations (LOCKED — the sharpest boundary)

An **ambiguous** mutation is one whose outcome is unknown: the client cannot prove whether the backend
applied it.

| Rule | Statement |
|---|---|
| A1 | Ambiguity is a status, not an error to be cleared by connectivity. |
| A2 | An ambiguous record stays owned by its original backend. |
| A3 | An ambiguous record is never replayed through another backend, and never replayed through the same backend without the family's proof that replay is safe (idempotence or non-application evidence). |
| A4 | A reconnect, a re-probe, a foreground refresh or an app restart does **not** make replay safe. |
| A5 | Only the owning family's mechanism may resolve ambiguity, and only against the original backend. |
| A6 | AnkiDroid does not advertise authoritative mutation reconciliation (`authoritativeMutationReconciliation = false`, `authoritativeCreationReconciliation = false`), so an ambiguous AnkiDroid mutation cannot be *proven* applied or not applied by the client today. It therefore stays blocking. |
| A7 | Ambiguity in one backend's group never blocks another backend's group. |

IMPLEMENTED evidence: `SUBMITTING → AMBIGUOUS` on first ledger load after a dead process; the session
refuses progression while a commit is unresolved (`NextCardResult.Failure(CommitConflict)` path);
`Gate11bRecoveryMatrixTest` / `Gate11dRecoveryAuditTest` / `Gate11eEnduranceTest` pin the no-double-apply
rules (present at this HEAD; **their runtime re-execution is not claimed by GATE 21B** — see
`GATE_21B_CONTRACT_LOCK.md` §2.3).

---

## 6. Historical records with unknown or insufficient identity (LOCKED)

Full case table in `BACKEND_IDENTITY_CONTRACT.md` §6.2. Behavioral summary:

| Case | Behavior |
|---|---|
| `backendId` present, collection unknown (normal AnkiDroid case) | Recover through the original family; the collection check is inert; uncertainty preserved |
| `backendId` present, instance not registered | `Blocked(BACKEND_NOT_REGISTERED)` — waiting, not failing, and never reassigned |
| `backendId` present, key recorded, live key now unknown, equal, or different | Unknown/degraded ⇒ blocked; different ⇒ `Blocked(COLLECTION_MISMATCH)`; equal ⇒ routed |
| `backendId` absent | **Unrecoverable** and surfaced as such. Never assigned to the current preference |
| `backendId` undecodable (unknown future variant) | The record decodes as `Unreadable`; only that record's recovery is blocked |
| Only a card/note id persisted, no owner | Does not occur in current formats; if it ever does, it is a schema defect → unrecoverable |
| Session with no backend identity | Cannot occur today; if durable sessions are added, such a record is invalidated, never defaulted |

**LOCKED.** Migration preserves uncertainty evidence. Nothing in this gate erases a null key, an unknown
owner or an ambiguous status in order to make a record loadable.

---

## 7. Read-context switch (LOCKED)

| Situation | Behavior |
|---|---|
| User switches read context from A to B | A new logical context is created. A's data, refs, cursor, total count, model/template cache and in-flight requests are invalidated |
| A's response arrives after B is current | Discarded by token check (backend id + generation + query + cursor). Never published, never blended |
| A ref from A is used against B | Structurally different (`backendId` is part of identity). The backend refuses a foreign ref with a typed error; the client never "adapts" it |
| Deck-level route carries a raw deck id | Resolved against the producing (pinned) backend only, never against the preference — until 21F makes the token backend-qualified |
| The same numeric deck/card id exists on both backends | Two different resources. No cross-backend equality, no translation |
| Custom Study context | A filtered session may have several logical contexts (rebuilds, several study sessions), but exactly **one** scheduler owner at a time: the filtered session's backend identity, which must equal the owning study session's lock (`INV-21B-17`). Not implemented — GATE 20 BLOCKED. |

---

## 8. Failure & failover summary matrix (LOCKED — DECISION-21B-26)

Legend: `R` = required; `P` = prohibited.

| Context | Original backend unavailable | Alternate backend available | Result semantics | UI semantics |
|---|---|---|---|---|
| New AUTO read | Policy may choose alternate — **pre-binding only** | Allowed | `Resolved(alternate)` | Show which backend answered |
| New manual read | Block | Prohibited (P) | `ExplicitBackendUnavailable(reason)` | Message + refresh action; preference untouched |
| Bound read | Invalidate / block / reload explicitly | Prohibited (P) | Stale context invalidated; re-bind is a new context | Blocking state over/overwhelming stale data; explicit action to continue on another backend |
| Active Study | Pause / block | Prohibited (P) | `NextCardResult.BackendUnavailable` / typed reason | Session paused; "locked to <backend>" indicator stays truthful |
| Pending rating | Preserve durable state | Prohibited (P) | Status machine unchanged; ambiguity preserved | "Cannot confirm" state; never a silent retry |
| Pending reviewer action | Preserve durable state | Prohibited (P) | Record unresolved | Reason surfaced; retry only after the owner returns |
| Active note edit | Bound context remains | Prohibited (P) | `BackendMismatch`/typed refusal | Save blocked; draft preserved; cancel-and-reopen offered |
| Pending creation | Preserve durable state | Prohibited (P) | Record unresolved; nothing created elsewhere | Same as above |
| Custom Study | Block / pause | Prohibited (P) | (Not implemented) | — |
| Transaction recovery | Original backend only | Prohibited (P) | `Blocked(BACKEND_UNREACHABLE / COLLECTION_MISMATCH / BACKEND_NOT_REGISTERED)` per group | Per-backend recovery status; one backend's outage must not be shown as another's failure |

**LOCKED.** There is no "fallback if possible" clause anywhere in the table. The only fallback in the
architecture is AUTO choosing an instance for a **new, not-yet-bound** context.

---

## 9. Diagnostics and privacy contract (LOCKED — DECISION-21B-27)

### 9.1 Safe diagnostic metadata (allowed)

```text
PreferredBackendId          (mode token, e.g. "auto")
ResolvedBackendId           (stableId, or NOT_MEASURED)
Resolution reason           (enum token)
LockedSessionBackendId      (stableId, or a count when several sessions hold locks)
LockedSessionCount
TransactionBackendId        (stableId)
Required capability         (enum token)
Missing capability per candidate
Backend availability        (statusCode token per backend)
Collection assurance category (AUTHORITATIVE_MATCH | SESSION_CONTINUITY_VERIFIED |
                               IDENTITY_UNVERIFIABLE(nominal|degraded) | AUTHORITATIVE_MISMATCH)
Readiness level             (R0..R4 token)
Resolution outcome token
Blocked recovery reason     (BACKEND_NOT_REGISTERED | BACKEND_UNREACHABLE | COLLECTION_MISMATCH)
Stale-response drop count   (aggregate only)
```

These are already the shape of what exists: `AnkiBackendRoutingDiagnostics`
(`preferred`, `resolved`, `resolvedReason`, `lockedSessionBackend`, `lockedSessionCount`,
`transactionBackend`, `availability` map) rendered as one row per role
(`DiagnosticsRepository.kt:594-605`, `DiagnosticsFormatting.NOT_MEASURED`). 21M extends this vocabulary;
it must not restate it in a second format.

### 9.2 Prohibited in diagnostics, logs and support exports

```text
authentication tokens            passwords / secrets
full card answers or question content   user transcripts / audio content
private collection content (field values, note text, media bytes)
unredacted endpoint secrets (credentials in URLs, tokens in query strings)
raw provider rows / cursors / exception messages carrying content
```

### 9.3 Rules

1. Diagnostics are **content-free tokens only**. A diagnostic must be reproducible without any collection content.
2. Backend identity in diagnostics is the `stableId`. A **raw configured endpoint is never logged**; where an endpoint must be identifiable to support, it is represented by the instance's `stableId`.
3. Retention and export follow the project's existing conventions (the settings/diagnostics export path and its privacy test `DiagnosticsExportPrivacyTest`, which already asserts no secret appears in an export). 21M must extend that test rather than add a parallel one.
4. Error classification uses the existing typed error vocabulary (`AnkiError` subtypes and the stable `statusCode` tokens). No new free-text error channel is introduced for backend failures.
5. Assurance categories are logged **verbatim**, including `IDENTITY_UNVERIFIABLE`. Logging an unverified collection as "matched" — or omitting the category so it looks verified — is prohibited.

---

## 10. Security preconditions for selection (LOCKED — DECISION-21B-28)

A backend must not become selectable for a privileged operation merely because its transport is
reachable. Selection preserves every backend-specific trust requirement.

| Precondition | AnkiDroid (IMPLEMENTED) | PC Anki backend (FUTURE CONTRACT — none exists) |
|---|---|---|
| Provider permission | `com.ichi2.anki.permission.READ_WRITE_DATABASE` via `AnkiDroidPermissionManager`; absence surfaces as `PermissionRequired`, never a crash | — |
| Endpoint trust | Endpoint allowlist; a release build can never resolve the debug authority (`AnkiDroidEndpoints.forBuild(includeDebugEndpoints = BuildConfig.DEBUG)`, `AnkiDroidApiContract.kt:790,814`) | Endpoint trust policy must be defined (TLS/pinning assumptions, allowed hosts) before a PC backend is selectable |
| Authentication | Not applicable (device-local provider permission is the trust boundary) | **Required and not implemented.** `Ready` may only be produced after successful authentication of the agent *and* of the Anki backend beneath it |
| Authorization | Permission-scoped operations; write serialization via `AnkiDroidWritePermit` | Must define per-operation authorization; "connected" is not "authorized" |
| Protocol negotiation | Provider API pinned to the audited contract (v2.24.1 notes) | Protocol/version negotiation is mandatory before any capability is advertised |
| Transport security | Local provider (no network) | Must define transport security assumptions and forbid unauthenticated remote mutation |
| Backend readiness | `AnkiAvailability.Ready` requires provider + collection initialized | `AgentDisconnected` / `AgentAnkiUnavailable` / `Ready` must be produced only after the corresponding checks |
| Collection identity validation | Not available (always null) — recorded, never faked | Must report a real key where obtainable |

**LOCKED.** The readiness ladder of `BACKEND_SELECTION_POLICY.md` §2 is the enforcement point: R2
(`BACKEND_READY`) already requires the permission/initialization facts on AnkiDroid, and must require
authentication + authorization + backend readiness on PC. A transport that is up but unauthenticated is
at R0/R1 and is therefore ineligible for every operation.

**LOCKED.** No backend may be selected for a privileged operation on the strength of a device-local
assumption (for example "the user is on their own phone") — authorization is per-backend and explicit.

**LOCKED.** Diagnostics must not weaken this: resolving and rendering a backend's status must not require
or expose credentials, and a status refresh must never silently perform an authenticated privileged
operation.

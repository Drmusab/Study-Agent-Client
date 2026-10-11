# Backend Selection Policy

**Gate:** GATE 21B — Backend Contract Resolution & Architecture Lock (Sub-Gate 21B of 21A–21N)
**Repository:** `Drmusab/Study-Agent-Client`
**Branch:** `arena/e0f0b8dd-study-agent-client`
**HEAD:** `236cff8f2733203ccacbc479a1463b283fcf2ecf`
**Date:** 2026-10-11
**Status:** LOCKED — normative contract. **No production code was changed to produce this document.**
**Prerequisite:** GATE 21A PASS/LOCKED; zero source change since (`docs/GATE_21B_CONTRACT_LOCK.md` §1).
**Companion documents:** `GATE_21B_CONTRACT_LOCK.md`, `BACKEND_IDENTITY_CONTRACT.md`,
`BACKEND_OWNERSHIP_INVARIANTS.md`, `BACKEND_FAILURE_MATRIX.md`, `GATE_21B_DECISION_EVIDENCE.md`.

This document owns **DECISION-21B-05** (selection vocabulary), **DECISION-21B-06** (deterministic AUTO),
**DECISION-21B-07** (manual selection), **DECISION-21B-08** (typed resolution semantics) and
**DECISION-21B-09** (capability contract) **as they concern selection**. Ownership *after* selection is in
`BACKEND_OWNERSHIP_INVARIANTS.md`.

Labels used below: **IMPLEMENTED** (source-verified at this HEAD), **LOCKED** (normative for 21C–21N),
**FUTURE CONTRACT** (specification for a component that does not exist yet). None of these is a claim
about a live backend: no device, no Anki collection and no PC agent were exercised.

---

## 1. The four selection roles (never interchangeable)

**LOCKED (INV-21B-19).** These four are distinct concepts with four distinct owners. No component may
substitute one for another, and no API may collapse two of them into one field.

| Concept | Owner / representation | Meaning | Never used for |
|---|---|---|---|
| `PreferredBackend` | `AnkiBackendPreferenceStore.mode` (DataStore `anki_backend_mode`), IMPLEMENTED | The user's default for **future, unbound** work | deciding an active session, a bound read, an editor, a draft, or a transaction |
| `ResolvedBackend` | `BackendResolutionResult.Resolved.backendId`, IMPLEMENTED | One concrete instance chosen for **one not-yet-bound** context | anything after that context binds |
| `LockedSessionBackend` | `AnkiSessionContext.backendId` projected by `AnkiStudyBackendLockTracker`, IMPLEMENTED (process memory only) | The immutable Anki scheduler owner of **one** StudySession | any other session; any future session |
| `TransactionBackend` | `record.backendId` in each durable ledger, IMPLEMENTED | The backend a durable mutation was **admitted against** | new mutations, AUTO selection, recovery shortcuts |

`AnkiBackendRoutingDiagnostics` already carries all four as separate fields
(`core/anki/AnkiBackendPreference.kt:41-57`) and `DiagnosticsRepository` renders them as separate rows
(`DiagnosticsRepository.kt:594-605`) — re-verified at this HEAD. 21M extends presentation, not semantics.

### 1.1 Explicit selection must name an instance, not a kind (LOCKED)

`AnkiBackendMode` (`AUTO`, `ANKIDROID_LOCAL`, `PC_AGENT`) is a **kind** selector. That is sufficient only
while a kind has exactly one configured instance.

```
Manual kind selection with N instances of that kind:
    N == 0  →  ExplicitBackendUnavailable (no instance of the requested kind is configured)
    N == 1  →  that instance
    N >= 2  →  Ambiguous — the user must choose an instance; policy must not choose for them
```

**FUTURE CONTRACT (owner 21E).** `PreferredBackend` may carry an optional **instance pin** (a specific
`AnkiBackendId`) in addition to the mode. Resolution rules for a pin:

- pinned instance registered and eligible → resolve to it;
- pinned instance not registered → `ExplicitBackendUnavailable` (never a sibling instance of the same kind);
- pinned instance registered but unusable → `ExplicitBackendUnavailable` with the instance's typed reason;
- a pin is **not** a lock: it constrains *future unbound* selections only, exactly like the mode it refines.

---

## 2. Readiness and capability ladder (LOCKED)

Selection is a function of **four** independent things, evaluated in order. They are not equivalent.

| Level | Name | Meaning | AnkiDroid today | PC today |
|---|---|---|---|---|
| R0 | `TRANSPORT_REACHABLE` | The transport can reach something. | provider resolvable | agent socket up |
| R1 | `AUTHENTICATED` | The peer accepted us. | integration permission granted | agent authenticated (protocol handshake) |
| R2 | `BACKEND_READY` | The **Anki backend below** is initialized and usable. | provider + collection ready (`AnkiAvailability.Ready`) | agent reachable **and** desktop Anki initialized (`Ready`, not `AgentDisconnected`/`AgentAnkiUnavailable`) |
| R3 | `CAPABILITY_READY(required)` | The required capability set is satisfied by **both** capability projections. | `descriptor.supports(required)` for each required | not implemented |
| R4 | `CONTINUITY_VERIFIED` | Collection continuity for an **existing** binding. | never (no key) | not implemented |

**LOCKED.**

```
AUTO and manual resolution filter on  R2 ∧ R3 .
Continuation of an existing binding requires the assurance rules of
BACKEND_IDENTITY_CONTRACT.md §3 — R4 where a key exists, the recorded category otherwise.
```

Consequences that must not be blurred:

- `TRANSPORT_RECONNECTED` ≠ `AUTHENTICATED` ≠ `BACKEND_READY` ≠ `CAPABILITY_READY` ≠ `CONTINUITY_VERIFIED`.
- "PC transport ready" does **not** imply "desktop Anki review ready" (the AI/voice socket and the Anki
  backend are different concerns; the states `AgentDisconnected` / `AgentAnkiUnavailable` / `Ready` exist
  in `AnkiAvailability` with **no producer** today).
- "PC AI evaluator available" does **not** establish "PC Anki scheduler available".

### 2.1 Capability contract (three-way distinction, LOCKED)

| Question | Contract | Evidence today |
|---|---|---|
| Does the backend **implement** the capability? | A *static claim* carried by the implementation, versioned with the audited contract (AnkiDroid pinned v2.24.1). Default is the safe `false`/`NONE`. | `AnkiCapabilities` (26 flags), `AnkiDroidCompatibilityPolicy` |
| Is the backend **ready** for it *now*? | A runtime fact: `AnkiAvailability.Ready` **and** the live `capabilities` projection **and** the availability-embedded snapshot all agree. Disagreement fails closed. | `AnkiBackendDescriptor.supports` requires both projections (`AnkiBackendDescriptor.kt:95-103`) |
| Is the backend **permitted** to execute it? | Policy: the operation is admitted only inside the binding that owns it (session lock / record identity) and only after pre-binding checks pass. | `AnkiStudyEffectExecutor.begin` `:544-549`, capability freeze `AnkiStudyLocalStarter:159` |

**LOCKED.** A capability must never be advertised as operational merely because the backend *could*
implement it, and never because a transport is reachable. Requirements are expressed once through
`AnkiRequiredCapability` and mapped once through `AnkiCapabilities.supports(...)`
(`AnkiBackendCapabilityRequirement.kt:93`) — no UI and no router branches on a backend name
(test-enforced: `Gate21BackendResolutionTest.capability resolution never inspects a backend name`).

**Versioning (LOCKED).** A capability claim is bound to the implementation contract version that was
audited. A version change, a gateway re-wiring, or a capability-probe contract change invalidates the
claim until re-probed; `withWriteSupport` (`AnkiDroidBackend.kt:211`) already drops claims when the
writer is not wired — the contract generalizes this: *a capability is a statement about the wired object
graph, not about a backend name or a vendor*.

**Capability loss after binding (LOCKED).** Loss must not redirect anything.

- A locked session keeps its frozen `AnkiSessionContext.capabilities` (`AnkiStudyEffectExecutor.kt:578`) and its session-start `ReviewerActionCapabilities` freeze.
- Loss may **refuse** the next operation with a typed error. It may never select a different backend, and it may never re-enable a previously unsupported path.
- Irreversible operations are revalidated before admission (§4.4) but the *revalidation result* can only be "proceed on the bound backend" or "fail with a reason".

**Unsupported-feature behavior (LOCKED).** Honest refusal with the missing capability named
(`UnsupportedAction(<capability>)` / `RequiredCapabilityUnavailable`). No silent degradation, no
substitute operation, no fallback backend.

**Product decision taken here (21A blocker #3).** `CARD_BROWSER`, `MEDIA_READ`, `FLAGS` and `DECK_COUNTS`
are **unsatisfiable on the only real backend** today. GATE 21B decides: **refuse honestly; do not
implement in this sub-gate series, and never paper over the refusal by keeping a hard-coded DI pin.**
Sub-Gate 21F must route those surfaces through capability-constrained resolution so the refusal is
explainable in the UI. Implementing any of them is a separate, future product decision.

---

## 3. AUTO resolution

### 3.1 Scope (LOCKED — INV-21B-04)

AUTO may resolve a backend **only before a context binds**:

```text
before starting a StudySession
before beginning a standalone unbound read
before opening an unbound browsing/editor/creation context
```

AUTO must **never**:

1. resolve after a context is bound (session, editor, creation draft, open read context);
2. replace a locked StudySession backend;
3. change the destination of an existing durable transaction;
4. merge two backends' collections or results;
5. be invoked during recovery of a persisted transaction (`BACKEND_OWNERSHIP_INVARIANTS.md` §3).

IMPLEMENTED evidence for the current state of that rule: `core/study/**` contains exactly one
resolution, inside `AnkiStudyEffectExecutor.begin()` `:544-549`; all post-binding operations use
`registry.find(<identity from the binding>)` (`:508, :513, :628, :664-666, :804`); the recovery planner
references no resolver or preference (`AnkiTransactionRecoveryRouting.kt`); `Gate21ArchitectureAuditTest`
asserts the absence of resolver/preference symbols from the study runtime and
`Gate21TransactionRecoveryRoutingTest` asserts the same for the recovery planner (re-verified).

### 3.2 Explicit inputs (LOCKED)

AUTO is a pure function of exactly these inputs. There are no others — no latency, no "first to answer",
no wall-clock, no registry list order.

```text
1. preference          : AUTO (this algorithm only runs for AUTO)
2. registered          : the configured backend instances and their current snapshots
                         (identity, availability, both capability projections, kind, locality)
3. requiredCapabilities: the operation's AnkiRequiredCapability set
4. readinessClass      : the operation's minimum readiness (R2 for every current operation)
5. purpose             : a content-free label for diagnostics only ("study_start", "library", …)
```

### 3.3 Exact algorithm (LOCKED)

Precedence, in this order. Each step is deterministic and total.

```text
resolveAuto(request, registered):

    # ---- Step 0 — eligibility: the mode filter. Fake can never pass. -------------------
    candidates = registered.filter { AUTO.accepts(candidate.id) }        # excludes Fake by construction
    candidates = candidates.sortedBy { candidate.orderingKey }            # deterministic order,
                                                                         # never registry order

    if candidates is empty:
        return NO_ELIGIBLE_BACKEND(considered = {}, reason = nothing_registered)
        # "nothing of an AUTO-acceptable kind is configured"

    # ---- Step 1 — readiness gate (R2). AUTO never probes, never refreshes. ------------
    ready = candidates.filter { readiness(candidate) >= BACKEND_READY }
    if ready is empty:
        return NO_ELIGIBLE_BACKEND(
                 considered = candidates.ids,
                 reason = (single candidate) ? thatCandidate.typedAvailabilityReason
                                             : operationUnsupported(requiredCapabilities))
        # Typed reason of the one candidate is surfaced verbatim (permission, provider,
        # collection-not-initialized, agent-disconnected, anki-unavailable, fault…).

    # ---- Step 2 — capability gate (R3), fail-closed on disagreeing projections --------
    capable = ready.filter { candidate.supportsAll(request.requiredCapabilities) }
    if capable is empty:
        return REQUIRED_CAPABILITY_UNAVAILABLE(
                 required = request.requiredCapabilities,
                 missing  = { each ready candidate → its missing set },
                 considered = candidates.ids)

    # ---- Step 3 — locality preference, by class rank, deterministic ------------------
    local = capable.filter { locality(candidate) == LOCAL_DEVICE }
    if local is not empty:
        chosen = local.minBy { candidate.orderingKey }                    # deterministic tie-break
        return RESOLVED(chosen.id, reason = AUTO_LOCAL_PREFERRED)

    # ---- Step 4 — remote remotes: exactly one is a decision, several are the user's ---
    remote = capable.filter { locality(candidate) == REMOTE_AGENT }
    if remote.size == 1:
        return RESOLVED(remote.single.id, reason = AUTO_SINGLE_REMOTE)
    if remote.size == 0:
        return NO_ELIGIBLE_BACKEND(considered = capable.ids, reason = no_real_locality)
        # Only TEST_DOUBLE localities remain. AUTO never selects a test double (LOCKED).
    return AMBIGUOUS(candidates = remote.ids, considered = capable.ids)
        # Two PC profiles are two collections. Choosing between them is a user decision.
```

### 3.4 Exact precedence and tie-breakers (LOCKED)

| Rank | Criterion | Direction | Notes |
|---|---|---|---|
| 1 | Mode eligibility | filter | `AUTO` accepts `AnkiDroidLocal` and `PcAgent`; never `Fake` |
| 2 | Readiness | filter | `availability` is `Ready` (R2) |
| 3 | Required capabilities | filter | All required flags true in **both** projections (R3) |
| 4 | Locality class | prefer | `LOCAL_DEVICE` over `REMOTE_AGENT`; `TEST_DOUBLE` is never selected |
| 5 | Ambiguity | refuse, do not guess | ≥2 equally eligible remotes ⇒ `Ambiguous` |
| 6 | Final tie-break **inside** a class | `orderingKey` = `kind.ordinal + ":" + stableId`, lexicographic ascending | total, stable, independent of registry order and of timing |

**LOCKED.** Registry iteration order, insertion order, object identity, hash order, thread timing and
"which backend answered first" are **never** inputs. `AnkiBackendRegistry.registered` documents that order
is never a routing input (`AnkiBackendSelector.kt:13`), and determinism is pinned by
`Gate21BackendResolutionTest.AUTO is deterministic for identical inputs` and
`resolution is pure - it never probes, refreshes or rewrites the preference` (re-verified as present).

**LOCKED.** AUTO returns exactly **one** instance, or a typed failure. It never returns a set for the
caller to "try in order", and it never iterates to find a backend that answers.

### 3.5 Relationship to the current implementation

The algorithm above is the contract; the existing `AnkiBackendResolver.resolve`
(`AnkiBackendResolver.kt:104-215`) implements steps 0–4 and the ambiguity refusal for every input a real
`AnkiBackendMode` can produce. Two deliberate clarifications for 21E:

1. The current implementation's "no remote remained" branch can resolve to a `TEST_DOUBLE` candidate
   (`AnkiBackendResolver.kt:~185`). That branch is **unreachable from every production mode** because
   `AnkiBackendMode.accepts` rejects `Fake`. The contract states the rule positively: *AUTO never selects
   a `TEST_DOUBLE`.* 21E must keep the branch unreachable or make it an explicit refusal — either is
   compliant; a test double reaching production routing is not.
2. Step 6's final tie-break is currently only reachable within the local class (one local kind exists).
   It is nevertheless locked, because it is what makes AUTO deterministic if a second local kind is ever
   added.

---

## 4. Manual selection

### 4.1 The rule (LOCKED — INV-21B-05)

```text
Selected: PC_AGENT          Selected: ANKIDROID_LOCAL
PC unavailable              AnkiDroid unavailable
AnkiDroid ready             PC ready
        ↓                            ↓
BLOCKED — never silently switch to AnkiDroid
```

A manual selection is **not** AUTO. It never falls back, never substitutes, never retries through another
instance, and never reinterprets itself as "the user probably meant the one that works".

### 4.2 Required behaviors (LOCKED)

| Situation | Outcome | User-visible behavior | Retry / recovery policy |
|---|---|---|---|
| Requested instance not configured at all | `ExplicitBackendUnavailable` (reason: not implemented / not configured) | "PC Anki is not configured", with the preference left untouched | Re-evaluated on the next explicit attempt; never automatically |
| Requested instance configured, not ready | `ExplicitBackendUnavailable` with the instance's **typed** reason (`PermissionRequired`, `ProviderUnavailable`, `CollectionNotInitialized`, `AgentDisconnected`, `AgentAnkiUnavailable`, `Fault`) | Reason verbatim; a "Check again" refresh action is allowed (**readiness refresh only**) | A refresh may update readiness display; it must not auto-start the blocked operation or change the preference |
| Requested instance ready but lacking a required capability | `RequiredCapabilityUnavailable`, naming the missing capability | "This backend cannot browse cards" style message; capability token shown, backend name never branched on | Not retryable by waiting (a capability does not appear by reconnecting); requires an implementation or a different user choice |
| Authentication failure (PC) | `ExplicitBackendUnavailable` / `AuthenticationRequired` semantic outcome | "Sign in to the PC agent" style action | Retryable **after** the user authenticates; never silently retried |
| Permission failure (AnkiDroid integration permission) | `ExplicitBackendUnavailable` (`PermissionRequired`) | Explain the missing AnkiDroid integration permission; the app cannot grant it itself | Retryable after the user grants it in AnkiDroid; the app must not loop |
| User changes the selection explicitly | Write the new preference; it applies to **future unbound** work only | Statement of effect shown with the selector: active session, open editor, open draft and unfinished saves keep their own backend | — |
| The requested backend becomes ready later | Nothing happens by itself | Readiness display may update | The user retries, or AUTO (if selected) picks it up at the next unbound operation |

**LOCKED.** A failed manual selection must not: change the stored preference, mark the backend as
preferred-but-unusable, start a session on another backend, or leave a half-bound context behind. A
refused start publishes **no** lock (IMPLEMENTED: the lock is published only after `beginReview`
succeeded, `AnkiStudyEffectExecutor.kt:585`).

**LOCKED.** Manual selection is evaluated **per unbound operation**. It is never re-evaluated mid-binding,
so a manual selection cannot move a running session, an open editor, a draft or a pending transaction.

### 4.3 Ambiguity under manual selection (LOCKED)

With two PC instances configured and `PC_AGENT` selected, the request is `Ambiguous`. The app must ask
the user which instance (or the user pre-pins one, §1.1). Choosing the "first" or "most recently used"
instance is prohibited: it would silently decide which collection gets mutated.

---

## 5. Typed resolution semantics

### 5.1 Semantic outcomes (LOCKED — encoding is 21E's choice)

| Semantic outcome | Produced when | Terminal? | Retryable? | Requires user action? | Blocks context binding? | UI exposure |
|---|---|---|---|---|---|---|
| `RESOLVED` | Exactly one eligible instance passed every gate | — | — | — | No (it *is* the binding decision) | Show resolved backend + reason, where material |
| `NO_ELIGIBLE_BACKEND` | Nothing eligible at all: nothing registered of an acceptable kind, or nothing reached R2, or no real locality remained | Not terminal (state can change) | Retryable only by an explicit new attempt | Usually yes (configure a backend / fix readiness) | Yes | Message + typed reason; per-instance status list |
| `EXPLICIT_BACKEND_UNAVAILABLE` | A manual selection named a kind/instance that is not configured or not ready | Not terminal | Explicit retry only | Usually yes | Yes | The instance's own typed reason |
| `REQUIRED_CAPABILITY_UNAVAILABLE` | Candidates exist and are ready, none satisfies the requirement | Terminal for that operation **on those candidates** | No (waiting does not add a capability) | Yes — different backend or different expectation | Yes | Missing capability named per candidate |
| `AUTHENTICATION_REQUIRED` | Readiness is blocked by an unauthenticated/unpermitted peer | Not terminal | After the user authenticates | Yes | Yes | Sign-in / permission action |
| `CONFIGURATION_INVALID` | The request or the configuration set is malformed for resolution (unknown mode token, duplicate identity, a descriptor contradicting its identity) | **Terminal** until configuration is fixed | No | Yes | Yes | Explicit configuration error, never a silent default |
| `AMBIGUOUS` | ≥2 equally eligible instances of the same kind (two PC profiles) | Not terminal | After the user picks | Yes | Yes | Candidate list; user chooses one |
| `IDENTITY_UNAVAILABLE` | **Not a resolution outcome.** See §5.2 | — | — | — | — | — |

### 5.2 `IDENTITY_UNAVAILABLE` is a continuation verdict, not a resolution result (LOCKED)

Resolution happens **before** a context exists, so it cannot observe a collection identity for that
context. Collection identity becomes checkable only once something is bound. The contract therefore
places identity outcomes on the **binding/continuation** side:

```text
BackendResolutionResult   →  which instance will own a NEW context        (pre-binding, 8 outcomes above)
BindingVerdict            →  may this EXISTING context continue here?      (post-binding)
                             AUTHORITATIVE_MATCH | SESSION_CONTINUITY_VERIFIED
                             | IDENTITY_UNVERIFIABLE(nominal|degraded) | AUTHORITATIVE_MISMATCH
```

Sub-Gate 21E may model `BindingVerdict` as it sees fit, but it must not fold identity into
`BackendResolutionResult`, because that would invite callers to "re-resolve" a bound context — the exact
failure mode this gate forbids.

### 5.3 Current implementation mapping (IMPLEMENTED)

| Semantic outcome | Implemented as |
|---|---|
| `RESOLVED` | `BackendResolutionResult.Resolved(backendId, reason)` |
| `NO_ELIGIBLE_BACKEND` | `NoBackendAvailable(considered, error)` |
| `EXPLICIT_BACKEND_UNAVAILABLE` | `PreferredBackendUnavailable(preference, considered, error)` |
| `REQUIRED_CAPABILITY_UNAVAILABLE` | `RequiredCapabilityUnavailable(required, missing, considered, error)` |
| `AMBIGUOUS` | `Ambiguous(candidates, considered, error)` |
| `CONFIGURATION_INVALID` | `ConfigurationInvalid(detail, considered, error)` |
| `AUTHENTICATION_REQUIRED` | carried as the typed `error` of the relevant failure (`PermissionRequired`), i.e. a *reason*, not a separate variant. Whether 21E promotes it to its own variant is an encoding decision; the semantic outcome must remain distinguishable and must be user-actionable. |
| `IDENTITY_UNAVAILABLE` | absent from the result type by design (§5.2) — the nearest existing mechanism is `AnkiTransactionRecoveryPlanner`'s `Blocked(COLLECTION_MISMATCH)` and the assurance categories of `BACKEND_IDENTITY_CONTRACT.md` §3 |

**LOCKED.** Every failure variant carries the identities it considered and a typed error. `backend = null`
is never the answer, and no failure may be indistinguishable from another in the UI
(pinned today by `Gate21BackendResolutionTest.every failure explains itself with a typed error and the
identities it considered`).

---

## 6. Scope of fallback (LOCKED)

| Fallback | Permitted? | Where |
|---|---|---|
| AUTO choosing a different eligible instance for a **new** context | **Yes** | Pre-binding only |
| Manual selection substituting another instance | **No** | Never |
| Any selection changing a bound read context | **No** | Never; a new context must be created explicitly |
| Any selection changing an active StudySession | **No** | Never |
| Any selection changing a bound editor / creation draft | **No** | Never |
| Any selection changing a pending transaction's target | **No** | Never |
| Recovery re-routing a durable transaction | **No** | Never |
| "Try the next backend because the first one failed" | **No** | Never |

**LOCKED.** The only fallback in this architecture is AUTO choosing an eligible instance for a context
that does not exist yet. That single permission is what makes "hybrid" usable without ever making a
mutation's destination ambiguous.

### 6.1 Selection never inspects a collection (LOCKED)

Resolution gates on kind, readiness, capability and locality. It does **not** compare collections, deck
names, card counts or sync state — none of which exist as inputs, and none of which may be added. Two
AnkiDroid-unavailable-but-PC-ready situations therefore never produce "the same library, elsewhere"; they
produce a refusal with a reason.

---

## 7. Determinism obligations for 21E (summary)

1. Every branch of §3.3 returns within one call; no I/O, no probing, no state mutation, no preference write.
2. Ordering is total: `(locality class rank, kind ordinal, stableId)` plus the ambiguity refusal.
3. Identical `(request, snapshots)` ⇒ identical result, in every registry order.
4. Failures are typed, carry `considered`, and are never collapsed into a null/empty success.
5. AUTO never returns a set of candidates except as the explicit `AMBIGUOUS` refusal.
6. A manual selection is honoured exactly or refused exactly — never approximated.

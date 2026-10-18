# GATE 21M — Independent Architecture Audit (Revision 2)

**Date:** 2026-10-18 (UTC)
**Original audit source HEAD:** `19c8a0d76169b18e4f2bf1787d107fbc96c41069`
**Auditor:** this gate run, after implementation inspection and executed tests.
**Scope:** `AUDIT-21M-R2-01` … `AUDIT-21M-R2-26` (gate audit matrix).

> This is the original audit record at `19c8a0d…`. Current-checkout source/test revalidation is in
> §5; the original source HEAD is unavailable in the local parentless snapshot.

```text
ARCHITECTURE AUDIT: PASS (26/26 at source level)
DEPENDENT ON ANDROID EXECUTION: AUDIT-21M-R2-20 (runtime accessibility), AUDIT-21M-R2-21 (runtime RTL/text)
ANDROID RUN: BLOCKED — therefore those two rows are PASS for the source contract and their runtime
             verification is separately BLOCKED in GATE_21M_VERIFICATION_REPORT.md.
```

No mandatory audit failed. The GATE 21M **lock** is nonetheless blocked by the executed-evidence
condition in `GATE_21M_FINAL_STATUS.md`, not by an audit failure.

---

## 1. Audit matrix

| ID | Result | Source evidence | Executed evidence | Defect / remediation | Residual risk |
|---|---|---|---|---|---|
| **AUDIT-21M-R2-01** Correct entry contract | **PASS** | R2 entry contract documented; repository documents `NEXT AUTHORIZED GATE: 21M` (`GATE_21_FINAL_LOCK.md:538`, `GATE_21K…:310`); 21L remains blocked (`GATE_21L_REPORT.md:3`) | Source inspection; no test can prove an authorization | none | The older 21L report says 21M must not start; R2 supersedes it. Historical documents left unedited. |
| **AUDIT-21M-R2-02** R-20-2 compliance | **PASS** | `AppContainer.kt:601` registers only AnkiDroid; no PC adapter exists; PC row disabled | `Gate21mR20_2BoundaryTest` 12/12 PASS; stress counters zero | none | PC integration remains unavailable by design. |
| **AUDIT-21M-R2-03** Capability authority | **PASS** | `AnkiBackendSectionUi` maps `AnkiRequiredCapability` → `AnkiCapabilityStatus`; `AnkiBackendState.capability` is the one authority; no parallel capability enum | `capability truth is rendered on both dimensions for every requirement`; `capability evidence distinguishes a static claim from a runtime negotiation and from nothing` | none | Capability truth is only as good as each adapter's static claim. |
| **AUDIT-21M-R2-04** Registry authority | **PASS** | Rows built from `states`; no fabricated instance; informational rows derived from `configuredKinds` | `one row per configured instance, keyed by the exact backend identity`; `a user-selectable kind with no wired adapter is informational and never operational`; `registering a real instance of that kind removes the informational row`; stress `inventedInstances=0` | none | A future dynamic registry consumer must follow membership (R-21D-10). |
| **AUDIT-21M-R2-05** AUTO correctness | **PASS** | Preview injected from `AppContainer.ankiBackendAutoPreview`; no resolver in UI; no PC fallback; no failover | Controller `the AUTO preview is supplied by the composition root over the very states being rendered`; `the AUTO row never promises a merged collection or a session failover`; `every AUTO preview failure produces an honest sentence and its own token`; `no GATE 21M file becomes a second resolution site` | none | Preview is only as deterministic as the existing GATE 21E policy; that policy is not re-tested here beyond its own suite. |
| **AUDIT-21M-R2-06** Exact backend identity | **PASS** | Row key = `stableId`; label never identity; duplicate-key detector | `one row per configured instance, keyed by the exact backend identity`; `a row carries the verbatim identity token and never uses a label as identity`; `a legacy kind preference with two configured instances selects nothing and says why`; stress `duplicateKeys=0`; `the UI layer keeps the locked identity rule - no per-backend type checks` | none | PC profile discriminator quality is future allocation-authority work (GATE 21C/21D residual). |
| **AUDIT-21M-R2-07** Persistence truth | **PASS** | `select()` publishes Saved only after the authoritative flow matches; `Unconfirmed` otherwise; generation guard | `a write the authoritative store echoes back is reported as saved`; `a refused write is reported as failed and the selection does not move`; `an accepted write the store never echoes is unconfirmed, never saved`; `a superseded write never publishes its late result`; `AUTO to AnkiDroid to AUTO converges on the authoritative store` | none | DataStore behavior itself is not exercised in this JVM run; repository write path is existing and separately tested. |
| **AUDIT-21M-R2-08** Availability accuracy | **PASS** | UI reads only `AnkiAvailability`; no connectivity/package/agent inference | `every availability state keeps its own status token and a non-blank text label`; `an implemented capability on a disconnected backend is supported and not available now`; `an unchecked backend claims neither support nor refusal`; `availability free text never reaches a diagnostics row` | none | Availability freshness remains last-observation (contract). |
| **AUDIT-21M-R2-09** Capability/readiness separation | **PASS** | Two fields per row (`implemented`, `operational`); `usableNow` requires READY | `a ready backend still reports the capabilities it does not implement`; `an implemented capability on a disconnected backend is supported and not available now`; stress `capabilityOverPromise=0` | none | None observed. |
| **AUDIT-21M-R2-10** StudySession ownership | **PASS** | Active owner read from `AnkiStudyBackendLockTracker`; controller cannot call `locked()`/`released()` | `a preference change never rewrites an active session's owner`; `the controller reads the lock tracker and can never write it`; `the active session owner is its own fact and is never the preference`; `several active sessions are counted and no single owner is named` | none | Lock persistence across process death is a GATE 21G contract, not changed here. |
| **AUDIT-21M-R2-11** Read-context accuracy | **PASS** | Diagnostics does not derive read ownership from preference; absent projection renders `NOT_PROJECTED`; GATE 21F read paths untouched | `read-context owners are rendered as identity plus presence, or as not projected`; `the four selection roles are read from the existing projection, never re-derived`; GATE 21F suite in full harness | none | No active read-context projection exists at HEAD, so the UI cannot show live owners. |
| **AUDIT-21M-R2-12** Custom Study boundary | **PASS** | `buildCustomStudyStatus` only; no lifecycle control; `FILTERED_SESSION` marked `NOT_APPLICABLE` | `a backend with no custom-study claim says so and offers no lifecycle control`; `a backend that claims part of the vocabulary still reports the missing lifecycle`; `the filtered-session family states its omission instead of being filled in`; `no Custom Study lifecycle control exists anywhere in the gate`; R20_2 `the only production backend claims no custom study, so none may be offered` | none | GATE 20 artifacts are scope-split: limited contract-enforcement subset reported locked; lifecycle unavailable; 21L remains blocked. |
| **AUDIT-21M-R2-13** Diagnostics read-only | **PASS** | Reader interface has one read method; constructed from read lambdas; no ledger exposed to UI | Diagnostics 19 tests; `the read-only surfaces hold no mutation authority`; `no rendered row offers a mutation, retry-all or reassignment` | none | Existing unrelated clear-logs actions remain; they do not touch Anki state. |
| **AUDIT-21M-R2-14** AMBIGUOUS safety | **PASS** | Family status token preserved verbatim; no retry/force control; owner preserved | `unresolved transactions keep their own owner and their own status vocabulary`; `no rendered row offers a mutation, retry-all or reassignment`; `transaction truth keeps owner and status vocabulary, and distinguishes absence` | none | No UI recovery action exists by design. |
| **AUDIT-21M-R2-15** Collection identity | **PASS** | Category rendered verbatim; key value never rendered; presence only | `every identity assurance category is rendered verbatim`; `a collection key is never measured into a value, only into presence`; `the active session owner line reports assurance without inventing continuity`; `the assurance category is rendered verbatim and never reworded` | none | AnkiDroid still publishes no authoritative collection key (GATE 21B R1); assurance remains nominal/unverifiable in production. |
| **AUDIT-21M-R2-16** Stale callback safety | **PASS** | Monotonic write/refresh generations; UI has no session callbacks; lock map read-only | `a superseded write never publishes its late result`; `a backend removed while its probe ran is reported as absent, never as a sibling's state`; `stale asynchronous results are rejected by a monotonic generation guard` | none | Cancellation alone is not used as the guard; generations are. |
| **AUDIT-21M-R2-17** Health refresh safety | **PASS** | Only `registry.refresh(id)`; user-initiated; no polling; read-only fixture | `refresh probes each configured instance once, read-only, and reports completion`; `refresh without a registry says the mechanism is unavailable instead of pretending`; `a failing probe is reported as a failure and never as a success`; `nothing polls and nothing runs because of composition` | none | Registry probe implementation and Android provider behavior are outside this JVM run. |
| **AUDIT-21M-R2-18** Security | **PASS** | No free-text diagnostics fields; stable ids/tokens/counts only; class-name-only logging | `availability free text never reaches a diagnostics row`; `a write that throws is a refusal and only the exception class name is logged`; `diagnostics render tokens, identities and counts - never backend free text`; `only the exception class name is ever reported or logged` | none | Existing diagnostics export remains outside 21M and is not widened. |
| **AUDIT-21M-R2-19** Lifecycle | **PASS** | `stateIn(WhileSubscribed(5000))`; one controller per ViewModel; no service construction in composition | `nothing is probed or written while the section is merely projected`; `nothing polls and nothing runs because of composition` | none | Compose screen lifetime not executed on device. |
| **AUDIT-21M-R2-20** Accessibility | **PASS** (source contract) | `ChoiceRow` `selectable` + RadioButton role; text status; disabled explanation; 52/48 dp minima | `disabled and informational rows carry a reason, and no row is icon-only`; source inspection | none | TalkBack, keyboard focus and large text **NOT executed** — BLOCKED (no device). |
| **AUDIT-21M-R2-21** Localization | **PASS** (existing convention) | No LTR/RTL overrides; logical padding; stable ids untranslated; copy follows existing hard-coded-English convention | `layout stays direction-agnostic for RTL and adds no string resources`; source inspection | none | No Arabic resource set exists repository-wide; runtime RTL/text scaling **NOT executed** — BLOCKED. |
| **AUDIT-21M-R2-22** No duplicate infrastructure | **PASS** | One registry, one resolver, one session machine, one ledger per family; gate adds presentation + read-only projection | `the gate adds presentation models and one controller, and no new authority`; `no GATE 21M file becomes a second resolution site`; `GATE 20, 21L and 21N behaviour is not implemented by this gate`; `AppContainer` grep | none | The read-only transaction reader is new infrastructure, but has no write authority and is required to expose existing ledgers safely. |
| **AUDIT-21M-R2-23** Baseline preservation | **PASS** | Existing suites unchanged; one test added, three assertions strengthened, two production wiring fixes | Full harness 237/2924 PASS; capability pins 13/0/0; GATE 21M 115 PASS | none | Historical 229/2799 revision not reproducible from this squashed checkout. |
| **AUDIT-21M-R2-24** Android evidence honesty | **PASS** | Reports mark Gradle/lint/build/instrumentation UNVERIFIED with exact failures | `GATE_21M_ANDROID_VALIDATION_STATUS.md` | none | Android compilation remains unproven in this environment. |
| **AUDIT-21M-R2-25** Scope discipline | **PASS** | No GATE 20/21L/21N implementation; no new backend/scheduler; no prior document edited | `GATE 20, 21L and 21N behaviour is not implemented by this gate`; `the export path is unchanged and the new rows are not silently exported`; `git diff` shows docs, tests and two wiring fixes; no GATE 20/21L/21N code | none | 21L remains blocked; 21N not executed here. |
| **AUDIT-21M-R2-26** Final lock separation | **PASS** | Final status explicitly withholds 21M lock and GATE 21 lock | `GATE_21M_FINAL_STATUS.md`, `GATE_21N_HANDOFF.md` | none | Final GATE 21 remains blocked by 21L and Android evidence. |

---

## 1.1 Two ID namespaces (numbering reconciliation)

The repository contains **two distinct 26-item enumerations that both use the `AUDIT-21M-R2-NN`
prefix**. They are not the same list, and this document does not merge them:

| Namespace | Where | What it is | May it evidence a matrix row? |
|---|---|---|---|
| **Gate audit matrix** | this document, rows 01–26 | the 26 mandatory audit topics the gate is reviewed against | — |
| **Executable source audit** | `Gate21mArchitectureAuditTest`, 26 `@Test` methods whose names begin `AUDIT-21M-R2-NN` | a separate structural checklist (e.g. its 01 is “no new authority”, its 19 is “stale results”, its 25 is “export unchanged”) | **Only by test name**, never by its numeric suffix |

Consequently:

- A matrix row is evidenced by the **descriptive test names** quoted in §1, not by `AUDIT-…-NN`
  appearing in a test title.
- The test suite's numbering was authored as an implementation-local checklist. It does **not**
  correspond row-for-row to this matrix, and the coincidence of both being 26 items is misleading.
- No test was renamed by this run, because renaming executed tests to fit a report would obscure
  which check actually ran. The mismatch is recorded here instead.
- Where another GATE 21M document cites an `AUDIT-21M-R2-NN` in a context that is clearly
  test-local (e.g. “no permission grant control”), it is a defect in that document; the affected
  citations have been replaced with test names.

This is an evidence-labelling risk, not an implementation defect: every matrix topic was checked
against source independently, and every quoted test name above is an executed test in the 115-test
GATE 21M suite.

---

## 2. Audit result summary

```text
AUDIT-21M-R2-01  PASS
AUDIT-21M-R2-02  PASS
AUDIT-21M-R2-03  PASS
AUDIT-21M-R2-04  PASS
AUDIT-21M-R2-05  PASS
AUDIT-21M-R2-06  PASS
AUDIT-21M-R2-07  PASS
AUDIT-21M-R2-08  PASS
AUDIT-21M-R2-09  PASS
AUDIT-21M-R2-10  PASS
AUDIT-21M-R2-11  PASS
AUDIT-21M-R2-12  PASS
AUDIT-21M-R2-13  PASS
AUDIT-21M-R2-14  PASS
AUDIT-21M-R2-15  PASS
AUDIT-21M-R2-16  PASS
AUDIT-21M-R2-17  PASS
AUDIT-21M-R2-18  PASS
AUDIT-21M-R2-19  PASS
AUDIT-21M-R2-20  PASS (source; runtime BLOCKED)
AUDIT-21M-R2-21  PASS (source; runtime BLOCKED)
AUDIT-21M-R2-22  PASS
AUDIT-21M-R2-23  PASS
AUDIT-21M-R2-24  PASS
AUDIT-21M-R2-25  PASS
AUDIT-21M-R2-26  PASS

FAIL: 0
BLOCKED: 2 runtime rows (accessibility, RTL/text), separately reported
```

---

## 3. Defects and remediation

Two **production wiring defects** and one **verification defect** were found and closed; one
**evidence-labelling defect** was found and disclosed:

| Defect | Remediation |
|---|---|
| The required persistence sequence AUTO → AnkiDroid → AUTO had no direct executable case; only individual write outcomes were covered | Added `AUTO to AnkiDroid to AUTO converges on the authoritative store` to `Gate21mBackendSelectionControllerTest`; suite now 115 tests |
| `DiagnosticsScreen.kt` built the backend rows without observing `ankiPendingTransactions`; the asynchronous ledger read could leave the card on `NOT_PROJECTED` for the screen's lifetime | The item now collects the state and recomputes with `remember(pendingTransactions)`; source audit asserts the observation. Compose execution remains BLOCKED |
| `AnkiBackendSectionUi.kt` offered `open_ankidroid` whenever an AnkiDroid backend was registered, including when the app was known to be `NotInstalled`; the button could only log a failure | The action is withheld for `NotInstalled`; `Gate21mBackendSelectionUiTest` asserts the absence. The main harness compiles this file and the suite passes |
| Two different 26-item checklists share the `AUDIT-21M-R2-NN` prefix (see §1.1) | Documented the two namespaces; replaced test-local numeric citations in GATE 21M documents with test names; no test renamed |

No other remediation was required. The pre-existing implementation's architecture matched the R2
contract in every audited row.

---

## 4. Audit method

1. Read every file in the GATE 21M production set, not only the tests.
2. Cross-checked each claim against the locked GATE 21B/21C/21D/21E/21F/21G contracts.
3. Ran the GATE 21M source-audit suite (`Gate21mArchitectureAuditTest`, 26 tests) and the boundary
   suite (`Gate21mR20_2BoundaryTest`, 12 tests).
4. Ran the behavioral suites (controller, selection UI, capability/availability, diagnostics, stress).
5. Ran the full harness to catch cross-gate regressions.
6. Re-ran the capability pins against the pinned upstream repositories.
7. Attempted Gradle/lint/build/instrumentation; recorded environmental failures rather than
   substituting harness results.
8. Reviewed the diff: documentation, tests and two production wiring fixes (`DiagnosticsScreen.kt`, `AnkiBackendSectionUi.kt`).

Source-text audit tests are not a substitute for runtime verification. Their limitations are stated in
the residual-risk and Android-status documents.

---

## 5. Current checkout revalidation — Arena session (2026-10-18 UTC)

```text
HEAD:   e22a32798259c3abd88001963afe511fd950cb2e
BRANCH: arena/95bebe16-study-agent-client
```

No production Kotlin or JVM-test source changed in this revalidation. The focused current-source
GATE 21M harness rerun passed **7 classes / 115 tests / 0 failures**, including all 26 executable
source-audit checks. The full current-source harness passed **237 classes / 2924 tests / 0 failures**.
The 26/26 architecture result remains **source-level only**. Runtime screen composition, TalkBack,
keyboard navigation, RTL, text scaling and theme rendering remain BLOCKED/unverified because the
Gradle wrapper could not download Gradle 8.7 and there is no Android SDK/device. The new two-method
Compose test source is not part of these JVM counts and was not compiled or executed.

**GATE 20 scope note:** current repository artifacts describe a limited contract-enforcing refusal,
disclosure, identity and capability-truth subset as locked (`GATE_20_FINAL_REPORT.md`,
`GATE_20B_CONTRACT_LOCK.md`), while `GATE_20_CUSTOM_STUDY_CONTRACT.md` and `GATE_21L_REPORT.md`
retain a broader blocked lifecycle prerequisite. Current source confirms no production PC Anki
adapter, no filtered-session lifecycle, and AnkiDroid's audited custom-study claim is `NONE`. The
user-directed GATE 21L status remains **BLOCKED**; no prior Gate status document was changed and no
GATE 20/21L implementation was added.

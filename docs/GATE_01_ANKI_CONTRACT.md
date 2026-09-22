# GATE 01 — Anki Fusion Architecture & Source-of-Truth Contract

Date: 2026-09-22
Branch: `arena/01a0c93c-study-agent-client` (session-pinned, branched from `master` @ `d10f455`)
Scope: architecture and ownership contract for all future AnkiDroid integration
work + minimal compile-safe domain contract types. **No AnkiDroid dependency, no
ContentProvider queries, no permissions, no study-behavior changes (§89/§90).**

---

## 1. Gate verdict

**GATE 01: PASS (architecture contract complete; Android build confirmation
remains BLOCKED_BY_ENVIRONMENT exactly as in GATE 00)**

The contract is ratified across four artifacts:

| Artifact | Path |
|---|---|
| Architecture contract (normative; invariants INV-ANKI-01…14, ownership tables, flows, hotspot audit, gate map) | `docs/ANKI_INTEGRATION_ARCHITECTURE.md` |
| ADRs 0001–0007 (+ index) | `docs/adr/` |
| Domain contract types (pure Kotlin, backend-neutral) | `app/src/main/java/com/studyagent/client/core/anki/` (9 files) |
| Contract unit tests (JVM-only) | `app/src/test/java/com/studyagent/client/anki/AnkiArchitectureContractTest.kt` |

Doc updates: `docs/ARCHITECTURE.md` §11, `docs/SESSION_STATE_MACHINE.md` §16,
`docs/IMPLEMENTATION_PLAN.md` §3.

## 2. Environment validation matrix (honest status, per GATE 00 conventions)

| Check | Result | Evidence / notes |
|---|---|---|
| `python3 server/test_contract.py` | **PASS** | Re-run after all gate edits: contract suite green (see §4). |
| `pytest server/test_tts_contract.py` `server/test_tts_mock_provider.py` | **PASS** | 50/50 in sandbox (websockets installed in GATE 00 venv if present, stdlib otherwise). |
| `py_compile` all `server/*.py` | **PASS** | Unchanged files; re-verified. |
| `./gradlew testDebugUnitTest` | **BLOCKED_BY_ENVIRONMENT** | Sandbox has no JDK/Android SDK and no network route to toolchain hosts (identical to `docs/GATE_00_BASELINE.md` §10-G2). |
| `./gradlew lint` | **BLOCKED_BY_ENVIRONMENT** | Same. |
| `./gradlew assembleDebug` / `assembleRelease` | **BLOCKED_BY_ENVIRONMENT** | Same. Android compile/test confirmation is owed by the first green CI run after the account billing lock is lifted (GATE 00 G1). |
| New Kotlin static self-review | **PASS** | `core/anki/*` + tests are pure Kotlin/JVM: no Android imports, no AnkiDroid types, no protocol types (INV-ANKI-06 by construction); no experimental language features beyond Kotlin 1.9 `data object`/sealed interfaces already used repo-wide; `suspend` keyword avoided in identifiers; JUnit4 conventions copied from existing tests. |
| AnkiDroid API dependency added | **NONE (by design)** | §89 compliance verified: no build-file change, no manifest change. |
| Study behavior change | **NONE** | Verified by diff: no file under `core/study/`, `data/repository/`, `ui/`, `core/models/` modified; only additions in `core/anki/` (unused-by-runtime contract types) + docs/tests. |

## 3. Contract essentials (what later gates must obey)

- **Ownership:** Anki owns learning-state truth (scheduling, FSRS, due state,
  history, collection, sync, media, templates/cloze); Study-Agent owns
  interaction-state truth (session machine, voice, AI evaluation, analytics,
  mistake notebook). Full matrix: contract §3.
- **One writable backend per session**, resolved once into
  `AnkiSessionContext` (INV-01/07); **no silent mid-session failover** (ADR 0002).
- **Exactly-once scheduling mutation per review turn** via
  `ReviewCommitId(backend, session, turn)`; commit outcomes COMMITTED /
  REJECTED / FAILED_SAFE_TO_RETRY / AMBIGUOUS; **AMBIGUOUS blocks progression**
  until reconciled (INV-02/08/11, ADR 0007).
- **Suggested ≠ selected ≠ committed rating**; human authority by default
  (INV-05/13).
- **Boundary hygiene:** no AnkiDroid types above the gateway, no PC protocol
  types above the remote gateway; domain models backend-neutral (INV-06).
- **Provider independence:** Anki backend ≠ AI/TTS/STT providers; offline
  AnkiDroid review and hybrid configurations are first-class (INV-10, ADR 0006).
- **AUTO policy:** AnkiDroid-local-first when implemented+review-ready, else
  PC path; never resolves unimplemented backends (today: PC) —
  `core/anki/AnkiBackendSelector.kt`, contract §6.2.

## 4. Test evidence

```
$ python3 server/test_contract.py        → "All unit contract tests passed!"
$ pytest server/test_tts_contract.py     → 22/22 passed
$ pytest server/test_tts_mock_provider.py → 28/28 passed
```

(Commands re-executed at gate lock; see §2 for the Android-side status.)

## 5. Code added (complete list; nothing else touched)

**Production (new package `com.studyagent.client.core.anki`):** `AnkiBackendId.kt`
(+ `AnkiBackendMode`), `AnkiRefs.kt` (`AnkiCollectionIdentity`, `AnkiDeckRef`,
`AnkiNoteRef`, `AnkiCardRef`), `AnkiCapabilities.kt`, `AnkiAvailability.kt`,
`AnkiErrors.kt` (+ `CommitFailureClass`, `asCommitFailureClass`),
`AnkiSessionContext.kt` (+ `ReviewTurnId`, `ReviewCommitId`), `AnkiBackend.kt`
(gateway interface + request/result types), `AnkiModels.kt` (`AnkiDeck`,
`AnkiDeckSummary`, `AnkiMediaRef`, `AnkiSchedulingInfo`, `AnkiCardMetadata`,
`AnkiRenderedCard`), `AnkiBackendSelector.kt`.

**Test:** `app/src/test/java/com/studyagent/client/anki/AnkiArchitectureContractTest.kt`.

**Docs:** the artifacts in §1. No other production source file was modified.

## 6. Deferred (explicit)

To GATE 02: AnkiDroid api dependency, `FlashCardsContract`/`AddContentApi`,
`READ_WRITE_DATABASE` permission, installation/permission detection, real
ContentResolver queries. To GATE 03: registry + settings persistence of
`AnkiBackendMode`. To GATE 06: commit ledger persistence, gateway-wired study
effects/events, backend-scoped connection-loss handling (hotspots H1–H13 in
contract §21). To release gate: licensing review (contract §14).

## 7. Gate lock

Logical commit on this branch:

```
gate01: define Anki fusion architecture and ownership contract
```

GATE 02 does NOT start from this commit automatically.

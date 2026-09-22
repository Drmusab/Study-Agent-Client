# GATE 03 — Anki Domain Model & Backend Abstraction

Date: 2026-09-22  
Branch: `arena/01a0c9b9-study-agent-client`  
Baseline: `d028af45c457d6afd08b79f531a6c58f9817a89e`

## Gate result

**GATE 03: BLOCKED — implementation and test sources are present; required Kotlin/Android
compilation, unit tests, lint and builds could not run. No green gate or gate-lock commit is claimed.**

The environment has neither a JDK nor an Android SDK. All required Gradle commands exit 1
before Gradle starts: `JAVA_HOME is not set and no 'java' command could be found in your PATH`.
A Gradle toolchain download probe failed TLS (`curl` exit 35 / HTTP 000); no substitute toolchain
was obtained. This report does not treat source review as a compiler or an executed unit test.

## Domain architecture

```text
Study / future session coordinator
    ↓
AnkiBackend + backend-neutral Anki domain models (core/anki)
    ↓ implemented by
Fake (test-only) | future AnkiDroid gateway | future PC adapter
```

The existing `StudySessionRepository → PC Agent` flow remains unchanged. DI now exposes a
lazy empty registry and selector, not an unfinished production backend. No settings or UI was added.

The normative audit, model relationships, identity rules, outcome semantics, selection policy,
persistence boundary, migration roadmap and INV-ANKI-DOM-01…12 are documented in
[ANKI_INTEGRATION_ARCHITECTURE.md §26](ANKI_INTEGRATION_ARCHITECTURE.md#26-anki-domain-model--gate-03).

## Models implemented / consolidated

GATE 01 types were amended in place, not shadowed:

- `AnkiBackendId`: `AnkiDroidLocal`, `PcAgent(profileId)`, `Fake(id)`; serializable logical identity.
- `AnkiBackendMode`: AUTO, ANKIDROID_LOCAL, PC_AGENT; preference, not effective identity.
- `AnkiCollectionIdentity`, `AnkiDeckRef`, `AnkiNoteRef`, `AnkiCardRef`.
- `AnkiDeck`, `AnkiDeckCounts`, `AnkiCardMetadata`, `AnkiRenderedCard`.
- `AnkiMediaRef`, `AnkiSchedulingInfo`, `AnkiFsrsInfo`.
- `AnkiSessionContext`, `ReviewTurnId`, `ReviewCommitId`.
- `BeginReviewRequest`, `AnkiReviewSession`, `AnkiReviewTurn`, `CommitRatingRequest`, `CardActionRequest`.
- `AnkiAvailability`, `AnkiCapabilities`, `AnkiError` (includes session/stale-turn/note errors).
- `AnkiResult`, `NextCardResult`, sealed `CommitRatingResult`.
- `AnkiBackendRegistry`, `AnkiBackendSelector` and `Resolution` (Resolved / Unavailable / Ambiguous).
- Test-only `FakeAnkiBackend`, `CommitStep`, `RecordedCommit`.

Existing `core.models.Rating` is reused. There is **no** duplicate `AnkiRating`, `AnkiSessionId`,
`StudySessionId`, `AnkiCollectionRef`, second availability hierarchy or generalized flashcard API.

### Identity semantics

All refs include the logical backend ID; PC profile IDs distinguish separate agents/profiles.
Known collection keys participate in equality; unknown keys remain null and are not wildcards.
Deck identity is independent of display name/path. Note/card are distinct types. Card identity
requires card ID or note + ordinal; all optional identity strings are either null or nonblank.
Structural equality and length-prefixed stable keys include all ref fields. A turn is a distinct
presentation bound to the existing study-session string ID; a retry retains that turn, and a later
appearance of the same card does not. Context qualifier validation prevents mixed-backend binding.

## Final backend interface

```kotlin
interface AnkiBackend {
    val id: AnkiBackendId
    val availability: StateFlow<AnkiAvailability>
    val capabilities: StateFlow<AnkiCapabilities>

    suspend fun refreshAvailability()
    suspend fun getDecks(): AnkiResult<List<AnkiDeck>>
    suspend fun beginReview(request: BeginReviewRequest): AnkiResult<AnkiReviewSession>
    suspend fun nextCard(session: AnkiReviewSession): NextCardResult
    suspend fun commitRating(request: CommitRatingRequest): CommitRatingResult
}
```

No AI, TTS/STT, Study UI state, real provider querying, card rendering, sync or note editing.
Early summary/bury/suspend method stubs were removed from the interface until behavior is needed.
`CardActionRequest` preserves typed session/card/optional-turn identity for later typed actions.

## Backend selection

| Preference | Resolution |
|---|---|
| AUTO | Ready + review-capable local first; else one ready PC profile; multiple ready PC profiles → Ambiguous; none → Unavailable |
| ANKIDROID_LOCAL | Local only; not registered/not ready → typed Unavailable, never PC fallback |
| PC_AGENT | PC profiles only; one ready → resolved; multiple ready → Ambiguous; none → Unavailable, never local fallback |

Fake identities are excluded from product selection. Registry rejects duplicate logical IDs.
Selection is before session binding; neither selection nor availability mutates a bound context.
The selector does not probe platforms or write user preference. Optional missing capabilities do
not disqualify review; both Ready and current capability projections must permit review.

## Fake and exactly-once foundation

The fake lives in `src/test`, supports supplied decks/cards, deterministic queue order, runtime
availability/capability changes, read/open/next errors, scripted commit outcomes and coroutine
latency. Mutex-serialized operations revalidate after latency. Queued content snapshots detach
caller-owned collections. IDs have an injectable instance prefix and monotonic counter.

The commit ledger stores request, result, attempt count and simulated mutation count. Exact
retries return the cached terminal outcome; changed payloads conflict. Safe failures may retry
with the identical request. Ambiguous-applied and ambiguous-unapplied cases are injectable;
both prevent blind retries/advancement. Invalid sessions/cards/turns are rejected before mutation.
A full ledger fails closed without evicting keys. Reset clears histories/scripts/handles and
invalidates old turns without reusing presentation IDs.

`ReviewCommitId = backend + study session + turn`. Together with `ReviewTurnId` and the sealed
Committed / Rejected / RetryableFailure / Ambiguous outcomes, this prepares a stable correlation
boundary. It is **not** a completed distributed exactly-once implementation. Durable ledgers,
post-dispatch cancellation handling, scheduler mutation proof and reconciliation remain future work.
Error-category-only retry classification was removed because it cannot prove non-application.

## Validation evidence

| Command/check | Count / result |
|---|---|
| `./gradlew testDebugUnitTest` | BLOCKED, exit 1 before compilation; 0 Kotlin tests executed |
| `./gradlew lint` | BLOCKED, exit 1 before lint starts |
| `./gradlew assembleDebug` | BLOCKED, exit 1; no APK built |
| `./gradlew assembleRelease` | BLOCKED, exit 1; no APK built |
| Targeted study/session simulation/network/protocol regression command below | BLOCKED, exit 1; no Kotlin regressions executed |
| `python3 server/test_contract.py` | PASS: 14 stdlib protocol contract checks |
| `python3 -m py_compile server/mock_pc_agent.py server/test_contract.py server/test_client.py` | PASS: 3 unchanged Python sources |
| `python3 -m pytest server/test_tts_contract.py server/test_tts_mock_provider.py -q` | BLOCKED: `No module named pytest` |
| `git diff --check` | PASS: no whitespace errors |
| Executed Python equivalents of the four new source-boundary tests | PASS: import isolation, typed errors/no Kotlin Result, ownership exclusions, test-only fake/empty registry |
| Changed-file scope audit | PASS: legacy study, network/protocol, voice, Gate 02 platform code and server untouched |

Targeted regression command attempted:

```bash
./gradlew testDebugUnitTest \
  --tests 'com.studyagent.client.study.*' \
  --tests 'com.studyagent.client.network.*' \
  --tests 'com.studyagent.client.ProtocolJsonTest' \
  --tests 'com.studyagent.client.FakeAgentConnectionTest'
```

### Kotlin test sources (written, not executed)

| Suite | Test methods |
|---|---:|
| Existing `AnkiArchitectureContractTest` (adapted, including stronger commit outcomes) | 14 |
| Reusable `AnkiBackendContract`, inherited by `FakeAnkiBackendContractTest` | 9 |
| `AnkiBackendSelectorTest` | 11 |
| `AnkiDomainModelTest` | 15 |
| `FakeAnkiBackendTest` | 22 |
| `AnkiDomainIsolationTest` | 4 |
| **Total domain tests intended for execution** | **75 (61 new + 14 adapted)** |

Required matrix coverage is present in these sources; **none is reported as a Kotlin test PASS**:
backend-qualified card/deck/note and collection equality; same card/different turn; immutable
binding; AUTO matrix and explicit fail-closed behavior; capability gating; fake decks/sequence;
commit recording and duplicate/concurrent mutation control; safe failure vs ambiguity; backend
loss; identity serialization and invalid IDs; Android-free core; cancellation/virtual latency;
bounded history, reset, foreign handles/cards and stale turns. The generic contract has no
ContentResolver/MockWebServer knowledge.

## Files added

Paths under `app/src/`:

1. `main/java/com/studyagent/client/core/anki/AnkiResults.kt`
2. `test/java/com/studyagent/client/anki/AnkiBackendContract.kt`
3. `test/java/com/studyagent/client/anki/AnkiBackendSelectorTest.kt`
4. `test/java/com/studyagent/client/anki/AnkiDomainIsolationTest.kt`
5. `test/java/com/studyagent/client/anki/AnkiDomainModelTest.kt`
6. `test/java/com/studyagent/client/anki/AnkiTestFixtures.kt`
7. `test/java/com/studyagent/client/anki/FakeAnkiBackendTest.kt`
8. `test/java/com/studyagent/client/anki/fake/FakeAnkiBackend.kt`

Documentation:

9. `docs/GATE_03_ANKI_DOMAIN.md` — this validation/report artifact; normative domain policy remains in the architecture document.

## Files modified

Paths under `app/src/main/java/com/studyagent/client/`:

| File | Reason |
|---|---|
| `core/anki/AnkiAvailability.kt` | Clarify that Ready does not imply review capability |
| `core/anki/AnkiBackend.kt` | Small typed interface, session-bound turn, validated requests/handle; remove speculative methods |
| `core/anki/AnkiBackendId.kt` | Profile-qualified sealed identity, Fake identity, serializable preference, collision-safe key helper |
| `core/anki/AnkiBackendSelector.kt` | Immutable registry, existing pure policy extended for profiles/ambiguity/actionable errors |
| `core/anki/AnkiCapabilities.kt` | Add truthful optional review-interval capability |
| `core/anki/AnkiErrors.kt` | Add NoteNotFound/SessionInvalid/StaleTurn; remove unsafe category-only commit classification |
| `core/anki/AnkiModels.kt` | Unknown counts stay unknown, deck hierarchy, neutral content refs, rating-keyed scheduling hints and informational FSRS |
| `core/anki/AnkiRefs.kt` | Strong ID/ordinal validation, collection-qualified deck identity, selective serialization and consistent equality keys |
| `core/anki/AnkiSessionContext.kt` | Required existing study-session identity, optional deck, qualifier validation and persistable turn/commit IDs |
| `di/AppContainer.kt` | Lazy empty registry and selector only; no production flow migration |

Other paths:

| File | Reason |
|---|---|
| `app/src/test/java/com/studyagent/client/anki/AnkiArchitectureContractTest.kt` | Adapt prior tests to consolidated identity/results and reject invalid mixed-backend context copies |
| `docs/ANKI_INTEGRATION_ARCHITECTURE.md` | Update old signatures/policy and add normative Anki Domain Model §26 with all 12 invariants |

## Deferred work and gate lock

- Real AnkiDroid deck queries and provider gateway.
- Real ReviewInfo and scheduler queues.
- Real rendered-card mapping/rendering and media resolution.
- Real scheduler commits, durable deduplication, reconciliation and process-death recovery.
- PC adapter/protocol mapping and production session migration.
- Library UI, Card Browser, deck picker and backend settings UI.
- Flags/bury/suspend implementations, editing and custom study.
- Debug fixture packaging if development UI later needs the test-only fake.

**Is the Anki domain stable enough for GATE 04 to implement the real AnkiDroid gateway? NO —
not yet gate-cleared.** The architectural seam is implemented, but its Kotlin sources and
serialization/contract tests have not compiled or executed, and lint/debug/release builds are
unverified. Required next action: provision JDK 17 + Android SDK 34 and dependency access (or
run existing Android CI), run all four mandatory commands and regression suites, fix any failures,
then create `gate03: establish Anki domain and backend abstraction` only after PASS.
No GATE 04 work or gate-lock commit was started.

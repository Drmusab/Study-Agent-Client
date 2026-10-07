# GATE 11C — AnkiDroid rating commit audit

**Audit date:** 2026-10-07
**Pinned AnkiDroid release:** `v2.24.1`
**Resolved AnkiDroid source commit:** `9f579c10bb151146728220729c510acbbd8faba7`
**Pinned Anki backend:** `ankitects/anki` `25.09.2` (`rslib` scheduler source)
**Scope:** public `CardContentProvider` contract only; no AnkiDroid artifact, private database, or
AnkiDroid in-process class is linked by Study-Agent.

This note records the source audit used by the production adapter. It is intentionally separate
from the device evidence report: source inspection proves the contract we implement, but it does
not prove behaviour on a particular installed profile.

## 1. Sources inspected

| Source | Symbol/region | Why it matters |
|---|---|---|
| [`CardContentProvider.kt` @ v2.24.1](https://github.com/ankidroid/Anki-Android/blob/v2.24.1/AnkiDroid/src/main/java/com/ichi2/anki/provider/CardContentProvider.kt) | `update`, `SCHEDULE` branch, `answerCard`, `getCard` | Real mutation call, input fields, return value, exception swallowing, card address |
| [`FlashCardsContract.kt` @ v2.24.1](https://github.com/ankidroid/Anki-Android/blob/v2.24.1/api/src/main/java/com/ichi2/anki/FlashCardsContract.kt) | `ReviewInfo.CONTENT_URI`, `NOTE_ID`, `CARD_ORD`, `EASE`, `TIME_TAKEN` | Public provider URI and field contract |
| [`Ease.kt` @ v2.24.1](https://github.com/ankidroid/Anki-Android/blob/v2.24.1/api/src/main/java/com/ichi2/anki/api/Ease.kt) | `Ease.EASE_1` … `EASE_4` | Public numeric ease values |
| [`AddContentApi.kt` @ v2.24.1](https://github.com/ankidroid/Anki-Android/blob/v2.24.1/api/src/main/java/com/ichi2/anki/api/AddContentApi.kt) | `apiHostSpecVersion`, `PROVIDER_SPEC_META_DATA_KEY` | Provider-spec and API-host facts |
| [`api/build.gradle.kts` @ v2.24.1](https://github.com/ankidroid/Anki-Android/blob/v2.24.1/api/build.gradle.kts) | `AUTHORITY`, `READ_WRITE_PERMISSION` | Authority and permission values |
| [`AndroidManifest.xml` @ v2.24.1](https://github.com/ankidroid/Anki-Android/blob/v2.24.1/AnkiDroid/src/main/AndroidManifest.xml) | exported provider, permission declaration | Provider discovery and permission protection |
| [`rslib/.../answering/mod.rs` @ 25.09.2](https://github.com/ankitects/anki/blob/25.09.2/rslib/src/scheduler/answering/mod.rs) | `answer_card_inner`, normal state application | Scheduler state transition and queue/current-state precondition |
| [`rslib/.../answering/preview.rs` @ 25.09.2](https://github.com/ankitects/anki/blob/25.09.2/rslib/src/scheduler/answering/preview.rs) | `apply_preview_state` | Filtered-deck preview limitation |

The repository's pinned constants and projections are in
`app/src/main/java/com/studyagent/client/data/anki/ankidroid/AnkiDroidApiContract.kt`.

## 2. Audited public contract

### 2.1 Mutation API

* **API symbol:** `ContentResolver.update(Uri, ContentValues, String?, Array<String>?)`
* **Provider URI:** `content://<authority>/schedule`, where the release authority is
  `com.ichi2.anki.flashcards`.
* **Study-Agent call chain:**

  ```text
  ReviewCommitCoordinator / AnkiStudyEffectExecutor
    -> AnkiBackend.commitRating(request, mutationEntry)
    -> AnkiDroidBackend.commitLocked
    -> AnkiDroidRatingCommitter.commit
    -> AnkiDroidRatingGateway.submitAnswer       (one answer entry point)
    -> AnkiDroidProviderClient.safeUpdate
    -> ContentResolver.update(content://<authority>/schedule, values, null, null)
  ```

* **Required card identity:** `note_id` (`long`) plus `ord` (`int`). The provider resolves these
  with `getCard(noteId, cardOrd, collection)`. A Study-Agent card id alone is not sufficient for
  this endpoint, so the adapter refuses a card without a positive note id and ordinal before any
  provider update.
* **Required answer fields:**
  * `answer_ease` (`int`), one of the public values 1 through 4;
  * optional `time_taken` (`long`) in milliseconds. When absent, AnkiDroid uses its own elapsed-time
    behaviour/cap. Study-Agent sends the field only when answer duration is known.
* **Fields deliberately not sent:** `buried` and `suspended`. They are separate provider actions,
  mutually exclusive with answering, and are outside Gate 11C.

### 2.2 Rating/ease mapping

`api/Ease.kt` defines the public button values as `EASE_1(1)`, `EASE_2(2)`, `EASE_3(3)`, and
`EASE_4(4)`. `CardContentProvider.update` converts the value with `Ease.fromValue` and then
passes `ease.value - 1` to Anki's zero-based `CardAnswer.Rating.forNumber`.

The single Study-Agent mapper is
`Rating.toAnkiDroidEase()` in
`app/src/main/java/com/studyagent/client/data/anki/ankidroid/AnkiDroidCommitEvidence.kt`.
The scheduler-provided turn options are checked first in `AnkiDroidBackend.validateCommit`; an
unmapped button set or a rating not present in `AnkiRatingOptions.Known.ratings` is rejected before
any provider call.

| Study-Agent rating | Public provider value | Scheduler meaning |
|---|---:|---|
| `AGAIN` | `1` | `CardAnswer.Rating` number `0` |
| `HARD` | `2` | `CardAnswer.Rating` number `1` |
| `GOOD` | `3` | `CardAnswer.Rating` number `2` |
| `EASY` | `4` | `CardAnswer.Rating` number `3` |

### 2.3 Permission and prerequisites

* The release provider requires
  `com.ichi2.anki.permission.READ_WRITE_DATABASE`.
* The provider checks permission at the beginning of `update` and throws
  `SecurityException` when it is not granted. The permission is declared by AnkiDroid and is
  dynamically enforced by the provider; Study-Agent does not request it from the commit gateway.
* The adapter checks the latest health metadata for permission, collection readiness, supported
  rating capability, and scheduled-review capability before entering the mutation callback.
* A provider-level permission or collection change after that read is not retroactively knowable.
  If the answer update may have been entered, the result is `OutcomeUnknown`, not a retryable
  refusal. Permission UX remains outside the gateway.

### 2.4 Return and exception behaviour

`CardContentProvider.update` maintains an `updated` count:

* `1` after it reaches the answer branch, even though the private `answerCard` helper catches
  `RuntimeException` from the scheduler and logs/reports it;
* `0` when the required note/ordinal keys are absent or no row is updated;
* `-1` may be returned by the platform when the provider process dies during the call;
* `SecurityException` can occur before collection access for a missing permission;
* `IllegalArgumentException` can occur for an unresolvable provider URI or an unknown note/ordinal;
* binder/provider/process failures can surface as an exception or a lost response.

Therefore `rowCount == 1` and “no exception” are not, alone, a success receipt. The adapter only
uses the synchronous success path when the returned row count is `1` **and** the immediate public
card-state read is consistent with one normal, attributable scheduler transition. A returned
`0`, `-1`, exception, timeout, or an inconclusive state observation is not converted to success.

The source also shows that the answer operation uses AnkiDroid's selected deck queue/current
scheduler state. The adapter performs a read-only queue-front check, temporarily selects the
session deck when needed, issues one answer update, and restores the prior selected deck in a
non-cancellable cleanup. Selection restoration is configuration hygiene; it is not a second rating
mutation and is never used as evidence that the answer committed.

### 2.5 Observable scheduler effect and limits

For a normal (non-preview) review, the audited backend applies the card/review operation
atomically and the public card surface exposes counters/time such as `reps` and
`last_review_time`. Those fields are used only for the immediate, same-attempt consistency check.
They are not a transaction receipt: the provider exposes no Study-Agent `ReviewCommitId`, no
answer transaction token, and no authoritative lookup by that id. A later counter change cannot
be attributed to this caller. Filtered-deck preview answers can change scheduling state without
normal `reps` evidence, so they remain conservative/unknown.

The collection identity is also not exposed as a stable public provider key. Study-Agent retains
known backend/deck/card identity and rejects inconsistent supplied collection keys, but documents
collection identity as **not independently verifiable** when the public provider does not expose
one. It does not hash a deck name, timestamp, card id, or review id into a fake collection proof.

## 3. Mutation boundary and classification

The durable sequence is:

```text
PREPARED
  -> coordinator persists SUBMITTING / MUTATION_BOUNDARY_ENTERED
  -> AnkiDroidRatingCommitter calls mutationEntry()
  -> AnkiDroidRatingGateway.submitAnswer()
  -> ProviderClient safeUpdate()
  -> REAL ContentResolver.update(.../schedule...)
```

The explicit adapter boundary is marked in
`AnkiDroidRatingCommitter.kt` immediately before `submitAnswer`. The exact Android call is marked
in `AnkiDroidProviderClient.kt`.

All adapter classification is centralized in
`AnkiDroidCommitResultClassifier.kt`:

| Adapter fact | Backend result | Coordinator/ledger status |
|---|---|---|
| validation, permission, capability, missing card, queue mismatch, or provider refusal before answer update | `ConfirmedNotCommitted` | `RETRY_ALLOWED` |
| synchronous `1` + immediate normal scheduler state consistent with one answer | `ConfirmedCommitted(receipt = null)` | `COMMITTED` |
| timeout, `-1`, binder/process failure, swallowed/inconclusive scheduler answer, or exception after entry | `OutcomeUnknown` | `AMBIGUOUS` |

The mapper is total at the coordinator boundary (`BackendCommitResult.toReviewCommitStatus`). The
adapter contains no rating retry loop, exponential backoff, or generic ContentProvider retry.

## 4. Commit semantics declaration

```text
guaranteeLevel = AT_MOST_ONCE_FAIL_CLOSED
supportsIdempotentReplay = false
supportsAuthoritativeReconciliation = false
commitReceiptKind = NONE
```

Evidence:

* AnkiDroid's public provider has no Study-Agent idempotency key or commit receipt.
* Card identity and `ReviewTurnId` are not idempotency keys; the same card can be reviewed again.
* Card counters/time are observable scheduler state, not attribution evidence.
* Reconciliation is read-only and remains `StillAmbiguous` unless a future public,
  transaction-correlated API is independently audited.

This is **not** an exactly-once claim. The local ledger suppresses duplicate inputs and the adapter
fails closed after uncertain provider entry; it cannot prove a crash-window answer was delivered
exactly once.

## 5. Evidence status

* **Source audit:** complete against the pinned source above.
* **JVM adapter/failure/mapper tests:** implemented; see
  `AnkiDroidRatingCommitTest`, `AnkiDroidCommitVerifierTest`,
  `AnkiDroidCommitClassificationTest`, and the backend contract tests.
* **Disposable AnkiDroid profile/collection test harness:** implemented in
  `AnkiDroidDisposableCommitInstrumentedTest`. It is opt-in and skips unless the operator supplies
  an explicit disposable-deck id and mutation confirmation.
* **Real device run in this checkout:** not available. No real mutation is claimed until that
  opt-in test runs against a disposable AnkiDroid profile/collection. Post-boundary response-loss
  is intentionally not manufactured against a real collection; Fake-backend tests cover it.

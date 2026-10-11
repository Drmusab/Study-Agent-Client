# Backend Routing Inventory

**Gate:** GATE 21A — Backend Architecture & Contract Discovery
**Branch:** `arena/5cef8d58-study-agent-client` (base commit `28eec915cc198dd1a1b24f29dc2790fcd3938616`)
**Companion to:** `docs/GATE_21A_DISCOVERY.md`

Every place in `app/src` where a backend is chosen, looked up or pinned, with the mechanism, the identity
it uses, and whether the choice is made **before** binding (routing) or **from** a binding (ownership).

## Legend

| Mechanism | Meaning |
|---|---|
| `RESOLVED` | Pre-binding policy decision (`AnkiBackendResolver` / `AnkiBackendSelector`) using the preference + capabilities. |
| `OWNED` | Derived from an identity that already exists (session context, route ref, persisted record). |
| `PINNED` | A single backend instance injected at construction; no second backend can reach this site. |
| `HARD-CODED` | A literal/constant backend choice in source. |

Ownership lookups are the desired end state. `PINNED`/`HARD-CODED` sites are the multi-backend gaps.

---

## 1. Session start (pre-binding)

| # | Site | File:line | Mechanism | Notes |
|---|---|---|---|---|
| 1 | `AnkiLocalStudyStarter.start()` | `data/repository/AnkiLocalStudyStarter.kt:132` | `RESOLVED` | `val preference = options.preference ?: preferenceProvider()` — sampled **once** (`:134`), then `selector.resolve(preference)` (`:135`). |
| 2 | freshness re-check | `AnkiLocalStudyStarter.kt:143-150` | `RESOLVED` | `backend.refreshAvailability()` then **re-resolve**; a changed answer refuses (`REASON_BACKEND_NOT_READY`) rather than remembering the first result. |
| 3 | capability freeze | `AnkiLocalStudyStarter.kt:159` | `OWNED` | `backend.reviewerActionCapabilities()` — taken from the resolved backend, frozen into the request. |
| 4 | `AnkiStudyEffectExecutor.begin()` | `core/study/AnkiStudyEffectExecutor.kt:544-549` | `RESOLVED` | The **only** resolution in study runtime (pinned by `Gate21ArchitectureAuditTest`). One probe pass over mode-accepted ids first (`:544`). |
| 5 | deck validation against the live collection | `AnkiStudyEffectExecutor.kt:571-576` | `OWNED` | `request.deck.backendId != id ⇒ InvalidRequest("foreign_deck")`, then `getDecks()` must contain the exact ref. |
| 6 | session context creation | `AnkiStudyEffectExecutor.kt:578` | `OWNED` | `AnkiSessionContext(id, collection, deck, startedAt, capability snapshot, studySessionId)`. |
| 7 | lock publication | `AnkiStudyEffectExecutor.kt:585` | `OWNED` | `if (result is AnkiResult.Success) sessionLocks?.locked(context)` — a refused start never advertises a backend. |
| 8 | dashboard pre-flight | `AnkiLocalStudyStarter.kt:220` | **HARD-CODED** | `resolveReadyBackendId()` uses `LOCAL_PREFERENCE` (`ANKIDROID_LOCAL`), **not** the user preference. |

---

## 2. Scheduler and mutation routing inside a live session (post-binding)

| # | Operation | File:line | Identity used |
|---|---|---|---|
| 9 | `nextCard` | `AnkiStudyEffectExecutor.kt:508` | `registry.find(effect.session.context.backendId)` |
| 10 | `hydrateCardContent` | `AnkiStudyEffectExecutor.kt:513` | `registry.find(effect.turn.backendId)` |
| 11 | commit dispatch route | `AnkiStudyEffectExecutor.kt:664-666` | `transactionRecovery.plan(listOf(commitId.backendId))`, fallback `registry.find(commitId.backendId)` |
| 12 | commit semantics freeze | `AnkiStudyEffectExecutor.kt:628` | `registry.find(commitId.backendId)` |
| 13 | `endReview` | `AnkiStudyEffectExecutor.kt:804` | `registry.find(effect.session.context.backendId)` |
| 14 | lock release | `AnkiStudyEffectExecutor.kt:802` | `sessionLocks?.released(effect.session.context.studySessionId)` |
| 15 | commit-truth snapshot | `AnkiStudyEffectExecutor.kt:340` | `registry.find(backendId)` |

**No scheduler or mutation call reads the preference.** `Gate21ArchitectureAuditTest` asserts the absence of
`AnkiBackendResolver`, `preferenceStore` and `AnkiBackendPreferenceStore` from `AnkiStudyEffectExecutor.kt`
source text, and asserts exactly one `AnkiBackendSelector(registry).resolve(` occurrence, inside `begin()`.

---

## 3. Durable transaction recovery

| # | Family | File:line | Mechanism | Behaviour when the owner backend is gone |
|---|---|---|---|---|
| 16 | GATE 11 rating | `core/anki/ReviewCommitReconciliation.kt:82` (`AnkiReviewCommitReconciler(registry)`) | `OWNED` (`commitId.backendId`) | stays unresolved; never re-routed |
| 17 | GATE 13 reviewer action | `core/anki/ReviewerActionRecovery.kt:286-320` | `OWNED` (`record.backendId`) | `registry.find(record.backendId) ?: Unresolved(BackendUnavailable)`; refuses a foreign `cardRef.backendId` or `collectionRef.backendId` |
| 18 | GATE 13 live dispatch | `core/anki/ReviewerActionCoordinator.kt:130` | `OWNED` | registry-routed |
| 19 | GATE 17 note edit | `core/anki/edit/NoteMutationCoordinator.kt:151` | **PINNED** | `private val backend: AnkiBackend`, wired to `ankiDroidBackend` (`AppContainer.kt:439`); a foreign record is refused as `BackendMismatch` — fail-closed, but a record owned by another backend could never be recovered |
| 20 | GATE 18 note creation | `core/anki/create/NoteCreationCoordinator.kt:162` | **PINNED** | `private val backend: AnkiBackend`, wired to `ankiDroidBackend` (`AppContainer.kt:470`); same `BackendMismatch` refusal |
| 21 | Cross-family routing | `core/anki/AnkiTransactionRecoveryRouting.kt` | `OWNED` | `AnkiTransactionRecoveryPlanner.groupByOwnerBackend(records) { it.backendId }`; `plan()` returns `Routed` / `Blocked(BACKEND_NOT_REGISTERED \| BACKEND_UNREACHABLE \| COLLECTION_MISMATCH)` per owner id. Contains **no** reference to `AnkiBackendResolver`, `AnkiBackendPreferenceStore` or `AnkiBackendMode` (test-enforced). |
| 22 | Blocking scope | `AnkiStudyEffectExecutor.kt:556-567` | `OWNED` | `ledger.recoveryBlocker(id, ...)` and `actionLedger.recoveryBlocker(id, ...)` run **before** any scheduler traffic; a blocked backend refuses the start. |

---

## 4. Read / browse / editor contexts

| # | Surface | File:line | Mechanism | Reachable through a second backend? |
|---|---|---|---|---|
| 23 | Library | `ui/navigation/AppNavHost.kt:81` → `LibraryViewModel(backend = container.ankiDroidBackend)` | **PINNED** | no |
| 24 | Library data | `di/AppContainer.kt:492` → `AnkiLibraryRepository(backend = ankiDroidBackend)` | **PINNED** | no |
| 25 | Deck details | `AppNavHost.kt:110` → `DeckDetailsViewModel(backend = container.ankiDroidBackend)` | **PINNED** | no |
| 26 | Card browser | `AppNavHost.kt:144` → `CardBrowserViewModel(initialBackend = container.ankiDroidBackend, deckId)` | **PINNED** | no (a `switchBackend` method exists at `CardBrowserViewModel.kt:163` but has **no production caller** — only tests) |
| 27 | Card browser VM cache key | `AppNavHost.kt:142` | hard-coded `container.ankiDroidBackend.id.stableId` in the key | n/a |
| 28 | Add-Note **route seed** | `AppNavHost.kt:94` → `Screen.AddNote.createRoute(container.ankiDroidBackend.id)` | **HARD-CODED** | no |
| 29 | Card details | `AppNavHost.kt:167-176` | `OWNED` — `container.ankiBackendRegistry.find(cardRef.backendId)`; unregistered ⇒ `CardDetailsRouteErrorScreen` | **yes** |
| 30 | Edit note | `AppNavHost.kt:248-258` | `OWNED` — `find(editCardRef.backendId)`; unregistered ⇒ error screen | **yes** |
| 31 | Add note | `AppNavHost.kt:204-222` | `OWNED` — `find(Screen.AddNote.decodeBackendId(...))`; unregistered ⇒ error screen | **yes** (but the id itself is seeded by #28) |
| 32 | Browser request correlation | `ui/screens/cardbrowser/CardBrowserViewModel.kt:62` | `OWNED` — `RequestToken(backend.id, generation, query, cursor)` | n/a |
| 33 | Library cache invalidation | `data/anki/AnkiLibraryRepository.kt:193`, `:210` | `OWNED` — a cached snapshot is reused only when `cached.backendId == backend.id`, and a refresh response whose `backendId != expected` clears the cache | n/a |
| 34 | Diagnostics AnkiDroid section | `AppContainer.kt:796` | **PINNED** — `ankiDroidBackend as? AnkiDroidBackend` (capability-matrix rows only; the routing rows come from #35) | no |
| 35 | Diagnostics routing rows | `AppContainer.kt:748-767` → `DiagnosticsRepository.kt:594-605` | `OWNED` — preferred / resolved / locked / transaction as four distinct rows | yes |

---

## 5. Preference, status and UI

| # | Site | File:line | Role |
|---|---|---|---|
| 36 | `AnkiBackendPreferenceStore` | `core/anki/AnkiBackendPreference.kt` | The user's default mode. Default for new unbound operations only. |
| 37 | `DataStoreAnkiBackendPreferenceStore` | `data/preferences/DataStoreAnkiBackendPreferenceStore.kt` | Persists through the app's settings DataStore; optimistic mirror; a failed write surfaces `backend_preference_not_saved`. |
| 38 | `AnkiBackendStatusCenter` | `core/anki/AnkiBackendStatus.kt` | Observation point: one `AnkiBackendDescriptor` per registered backend, combined with the preference. Exposes `resolveFor(...)` for **pre-binding** use only (documented in the class KDoc). |
| 39 | `AnkiBackendResolver` | `core/anki/AnkiBackendResolver.kt` | The single routing policy: mode filter → reachable → capability-satisfying → `LOCAL_DEVICE` before `REMOTE_AGENT` → a single remote (several ⇒ `Ambiguous`), ties broken on `orderingKey`. Pure; never probes, refreshes or writes. |
| 40 | `AnkiBackendSelector` | `core/anki/AnkiBackendSelector.kt:40` | Thin session-start adapter over the resolver; folds the result back into the GATE 01 shape. |
| 41 | Settings selection | `ui/screens/settings/SettingsViewModel.kt:82` `selectAnkiBackend(mode)` | Writes the preference. Its KDoc states what the switch does **not** touch. |
| 42 | Study indicator | `ui/screens/study/StudyViewModel.kt:88` `lockedAnkiBackendLabel` | Reads the **lock tracker**, never the preference. |
| 43 | Routing diagnostics | `AppContainer.kt:748`, `core/anki/AnkiBackendPreference.kt` `AnkiBackendRoutingDiagnostics` | Names no backend when several sessions hold locks; reports `lockedSessionCount` instead. |
| 44 | Diagnostics resolution sampling | `AppContainer.kt:754` | `resolveFor(STUDY_SESSION_REQUIREMENTS, "diagnostics")` — a pure read; opening Diagnostics cannot change a routing decision. |

---

## 6. Hidden fallback / implicit re-routing (A06)

Searched for, and **not found**:

| Candidate | Finding |
|---|---|
| Availability-driven implicit switch | None. A lost backend produces a typed failure on the *same* identity (`nextCard ⇒ NextCardResult.BackendUnavailable`). |
| Exception-driven fallback | One `catch` in `AnkiStudyEffectExecutor` (`:520-530`) converts an unexpected throw into a typed `AnkiError.QueryFailure("unexpected")` for the same effect; it never re-resolves. |
| Reconnect-driven switch | There is **no Anki-backend reconnect path.** Every `reconnect`/`Reconnect` symbol in `app/src/main` belongs to the PC **agent** transport (`core/network/ReconnectController.kt`, `WebSocketAgentConnection.kt`, `AppSettings.autoReconnect`), which carries AI/TTS/STT traffic, not Anki traffic. |
| "Try the next backend" loop | None. `AnkiBackendResolver` returns the first eligible candidate by `orderingKey` or `Ambiguous`; it never iterates to find one that answers. |
| Cache reuse across backends | Guarded. `AnkiLibraryRepository` reuses a cached snapshot only for the identical backend id (`:193`). |
| `switchBackend` in production | Three ViewModels expose it (`CardBrowserViewModel.kt:163`, `CardDetailsViewModel.kt:97`, `EditNoteViewModel.kt:180`); **no production caller exists** — only tests. |

---

## 7. Cross-context hazard: a pinned read context feeding an unpinned start

The two session-start call sites are both reached from AnkiDroid-pinned screens, and neither passes an
explicit preference:

| Start site | File:line | Deck input | Preference used |
|---|---|---|---|
| Home / dashboard | `ui/screens/home/HomeViewModel.kt:214` | `deckName` (from the AnkiDroid-pinned Library data) | `preferenceProvider()` = `ankiBackendPreferenceStore.mode.value` (`AppContainer.kt:667`) |
| Deck Details | `ui/screens/library/DeckDetailsViewModel.kt:144` | `deckId` (a raw route string, resolved against whatever backend `start()` picks) | same |

The chain, once a second backend is registered and the preference is `PC_AGENT`:

```
Library renders an AnkiDroid deck   (pinned, site #23)
  ↓ user taps "study this deck"
  ↓ AnkiLocalStudyStarter.start(preference = PC_AGENT)      (site #1)
  ↓ selector resolves the PC backend
  ↓ resolveDeck(backend = PC, deckId = "<AnkiDroid deck id>")
        matches on  deck.ref.backendId == PC  &&  deck.ref.deckId == deckId
  ↓ either  Refused("deck_not_found")
     or     a *different* deck with the same numeric id on a different collection is studied
```

The second outcome is exactly the resource-id reinterpretation GATE 21 forbids. It is latent today
(only one backend is registered, so `PC_AGENT` resolves to nothing and the start is refused as
`no_ready_backend`), but it becomes reachable the moment a second backend is registered.

A related, currently-reachable inconsistency: the Deck Details "can study this deck" gate and the
dashboard pre-flight both read `AnkiLocalStudyStarter.localStudyBackendId`, which is computed with the
hard-coded `LOCAL_PREFERENCE` (site #8) — while `start()` uses the persisted preference. The button's
enabled state and the session that actually starts can therefore disagree about which backend is meant.

## 8. Gap summary against the GATE 21 sub-gates

| Sub-Gate | Gap found by 21A |
|---|---|
| 21F (read contexts) | Sites **23, 24, 25, 26, 27, 28** are `PINNED`/`HARD-CODED` to `container.ankiDroidBackend`. Library, Deck Details and Card Browser have **no** backend context switching at the composition root, and the Add-Note route seed (#28) hard-codes AnkiDroid, which then determines #31. |
| 21F (capability) | `AnkiRequiredCapability.CARD_BROWSER` is unsatisfiable on the only real backend (see `BACKEND_CAPABILITY_MATRIX.md` §2.2), so capability-constrained browser resolution would refuse an operation the UI currently offers. |
| 21J / 21K | Sites **19, 20** (`NoteMutationCoordinator`, `NoteCreationCoordinator`) are pinned. Fail-closed today, but a record owned by a second backend could not be recovered — a latent defect, not a current one. |
| 21G (persistence) | `AnkiSessionContext` is not `@Serializable`, and the lock tracker is process memory. The session lock does not survive process death; only the durable ledgers do. |
| 21L (custom study) | Nothing to bind — GATE 20 is BLOCKED and unimplemented. |
| 21M (UI) | Library has **no** resolved/locked backend indicator; only Settings, Study and Diagnostics render backend facts. |
| 21B (policy) | `AnkiLocalStudyStarter.localStudyBackendId` (#8) uses `LOCAL_PREFERENCE`, not the persisted preference — the dashboard "can I study?" signal and the actual start can disagree about which backend is meant. |

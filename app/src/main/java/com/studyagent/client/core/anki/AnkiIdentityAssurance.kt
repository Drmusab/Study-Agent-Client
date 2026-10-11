package com.studyagent.client.core.anki

/**
 * GATE 21C — identity assurance (encoding of DECISION-21B-03).
 *
 * The locked contract separates three things that are easy to conflate:
 *
 * 1. **Resource identity** — which backend + which collection + which backend-local id
 *    (`AnkiRefs.kt`, `AnkiNoteModels.kt`, `AnkiTemplateModels.kt`). Immutable and never rewritten.
 * 2. **Assurance** — what the application can *currently* prove about a binding's collection
 *    continuity. Contextual evidence; it changes when the connection state changes.
 * 3. **Permission** — whether a given operation class may proceed under that assurance
 *    (`AnkiIdentityContinuation.kt`).
 *
 * This file owns (1)'s observation form and (2). Nothing here routes, connects, resolves a backend,
 * mutates Anki or reads a preference: it is a pure identity vocabulary plus the §3.5 computation.
 */

/**
 * §3.1 sub-qualifier of [AnkiIdentityAssurance.IdentityUnverifiable].
 *
 * The two are *not* interchangeable. `NOMINAL` means the backend never had a discriminator to give,
 * so no verification was ever promised and the operation continues under the recorded category.
 * `DEGRADED` means a key was known or expected and is now unavailable — that is mismatch-grade and
 * fails closed, because accepting it would silently convert "we could tell before" into "we cannot
 * tell now", which is exactly how a collection swap becomes invisible.
 */
enum class AnkiUnverifiabilityQualifier {
    NOMINAL,
    DEGRADED
}

/**
 * One side of an identity comparison: the configured backend *instance*, and whatever that side
 * reports about the collection it is working with.
 *
 * It is an **observation**, never a claim: a null [collectionKey] says only "this side presented no
 * discriminator" (§2.1). It is never a wildcard, never a sentinel, and never equal to another null by
 * virtue of being null.
 *
 * No connection object, host, token or capability snapshot appears here: this is identity, not state.
 */
data class AnkiIdentityObservation(
    val backendId: AnkiBackendId,
    val collectionKey: String? = null
) {
    init { requireOptionalId(collectionKey) }

    /** True only when this side actually presented a backend-issued collection discriminator. */
    val hasAuthoritativeKey: Boolean get() = collectionKey != null
}

/**
 * §3.1–§3.3 — the four assurance categories, and the only vocabulary in which collection continuity
 * may be described. Diagnostics, UI and logs must use these tokens verbatim; in particular
 * [SessionContinuityVerified] is never rendered, logged or reasoned about as "same collection".
 */
sealed interface AnkiIdentityAssurance {

    /**
     * Both sides presented a key and the keys are equal. This is the only category that proves
     * collection continuity, and it authorizes `OP-M3` only when the family also exposes an
     * authoritative reconciliation read.
     */
    data class AuthoritativeMatch(val collectionKey: String) : AnkiIdentityAssurance {
        init { require(collectionKey.isNotBlank()) }
    }

    /**
     * No authoritative key exists on either side, and the binding is the live in-process binding of
     * this same configured instance.
     *
     * It says exactly one thing: *this process still holds the binding it created, on one backend
     * identity*. It is **not** collection verification, it cannot authorize `OP-M3`, and a reconnect
     * never upgrades it.
     */
    data object SessionContinuityVerified : AnkiIdentityAssurance

    /**
     * The backend cannot present a collection key, so equality can be neither proven nor disproven.
     * See [AnkiUnverifiabilityQualifier] for why the qualifier decides the operation outcome.
     */
    data class IdentityUnverifiable(val qualifier: AnkiUnverifiabilityQualifier) : AnkiIdentityAssurance

    /**
     * Both keys are known and unequal. Fail-closed: new bindings are refused, bound work is blocked
     * and durable transactions route to `COLLECTION_MISMATCH`. Never migrated, never re-interpreted.
     */
    data class AuthoritativeMismatch(
        val boundCollectionKey: String,
        val liveCollectionKey: String
    ) : AnkiIdentityAssurance {
        init {
            require(boundCollectionKey.isNotBlank() && liveCollectionKey.isNotBlank())
            require(boundCollectionKey != liveCollectionKey) {
                "A mismatch needs two different collection keys"
            }
        }
    }
}

/**
 * CHECKPOINT 21C-15 — the typed result of comparing a bound identity with a live backend.
 *
 * [ForeignBackend] is deliberately not a fifth assurance *category*: the four categories of
 * DECISION-21B-03 are exhaustive for one configured instance. Two different configured instances are
 * never comparable at all — this is the structural defect the §3.5 algorithm's precondition guards
 * against, and it is represented as a typed outcome (never a crash, never `UNVERIFIABLE`) so a caller
 * cannot mistake "a different backend" for "we cannot tell".
 */
sealed interface AnkiIdentityCheck {

    /** Both sides name the same configured backend; [assurance] is the §3.5 result. */
    data class SameBackend(val assurance: AnkiIdentityAssurance) : AnkiIdentityCheck

    /** The two sides name different configured backends: never equal, never a match. */
    data class ForeignBackend(
        val boundBackendId: AnkiBackendId,
        val liveBackendId: AnkiBackendId
    ) : AnkiIdentityCheck {
        init {
            require(boundBackendId != liveBackendId) {
                "A foreign backend is a different configured instance"
            }
        }
    }
}

/**
 * §3.5 — the assurance computation, in one place, as a pure function.
 *
 * It reads nothing and changes nothing: no backend call, no probe, no preference, no registry lookup.
 * Callers supply the two observations; the policy decides what they mean. Re-running it after a
 * reconnect is therefore always honest — the old "verified" answer cannot be cached into a new one.
 */
object AnkiIdentityAssurancePolicy {

    /**
     * Compare a binding against the live backend.
     *
     * [boundIsLiveInThisProcess] is the caller's statement that the bound side is *this process's*
     * own live binding (a session/editor created in this process), not a restored record whose
     * binding died with an earlier process. It is the only input that can produce
     * [AnkiIdentityAssurance.SessionContinuityVerified]; with no key on either side and no live
     * binding, the honest answer is nominal unverifiability.
     */
    fun check(
        bound: AnkiIdentityObservation,
        live: AnkiIdentityObservation,
        boundIsLiveInThisProcess: Boolean
    ): AnkiIdentityCheck =
        if (bound.backendId != live.backendId) {
            AnkiIdentityCheck.ForeignBackend(bound.backendId, live.backendId)
        } else {
            AnkiIdentityCheck.SameBackend(assuranceFor(bound, live, boundIsLiveInThisProcess))
        }

    /**
     * The §3.5 algorithm for one configured instance. Precondition: both sides name the same
     * identity — a caller holding two different instances must use [check], which answers
     * [AnkiIdentityCheck.ForeignBackend] rather than inventing a comparable relation.
     */
    fun assuranceFor(
        bound: AnkiIdentityObservation,
        live: AnkiIdentityObservation,
        boundIsLiveInThisProcess: Boolean
    ): AnkiIdentityAssurance {
        require(bound.backendId == live.backendId) {
            "Assurance compares one configured backend with itself"
        }
        val boundKey = bound.collectionKey
        val liveKey = live.collectionKey
        return when {
            // Both sides have evidence: the only branch that can prove or disprove continuity.
            boundKey != null && liveKey != null ->
                if (boundKey == liveKey) {
                    AnkiIdentityAssurance.AuthoritativeMatch(boundKey)
                } else {
                    AnkiIdentityAssurance.AuthoritativeMismatch(boundKey, liveKey)
                }

            // We could tell before and cannot now: mismatch-grade, never "no key, therefore fine".
            boundKey != null -> AnkiIdentityAssurance.IdentityUnverifiable(
                AnkiUnverifiabilityQualifier.DEGRADED
            )

            // A key appeared for a binding that had none: the backend's reporting contract moved,
            // and nobody validated that change, so the binding is not confirmed by it.
            liveKey != null -> AnkiIdentityAssurance.IdentityUnverifiable(
                AnkiUnverifiabilityQualifier.DEGRADED
            )

            // No key on either side. A live in-process binding is the one honest statement left.
            boundIsLiveInThisProcess -> AnkiIdentityAssurance.SessionContinuityVerified

            // A restored/durable binding with no key: nothing can be said about continuity.
            else -> AnkiIdentityAssurance.IdentityUnverifiable(
                AnkiUnverifiabilityQualifier.NOMINAL
            )
        }
    }
}

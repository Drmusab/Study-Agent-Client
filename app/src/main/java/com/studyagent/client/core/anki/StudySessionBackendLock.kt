package com.studyagent.client.core.anki

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * GATE 21G — the durable, immutable backend ownership of one StudySession
 * (CHECKPOINT 21G-01/21G-06, DECISION-21B-12/21B-14).
 *
 * One StudySession owns exactly one configured [AnkiBackendId] plus the collection context it
 * bound to. The lock is captured once — after pre-binding resolution and validation, when the
 * backend accepts the session — and never changes afterwards: not on preference changes, not on
 * availability changes, not on process restart.
 *
 * This is the *persisted* form of the in-memory [AnkiSessionContext]. It deliberately carries only
 * identity:
 *
 * ```text
 * carried:  studySessionId, backendId, collection, deckRef, startedAt, schemaVersion
 * never:    capabilities / availability (observations with their own lifetime),
 *           preference or AUTO state (policy, not identity),
 *           transport objects, credentials, scheduler state, turn state
 * ```
 *
 * Capabilities are re-read from the live backend on restore
 * ([toSessionContext]); a restored session never inherits a frozen capability snapshot
 * (BACKEND_OWNERSHIP_INVARIANTS §2.5).
 *
 * Equality is structural: same session + same backend + same collection evidence.
 */
@Serializable
data class StudySessionBackendLock(
    val studySessionId: String,
    val backendId: AnkiBackendId,
    val collection: AnkiCollectionIdentity? = null,
    val deckRef: AnkiDeckRef? = null,
    val startedAtEpochMs: Long = 0L,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    init {
        require(studySessionId.isNotBlank()) { "A session lock names its StudySession" }
        require(startedAtEpochMs >= 0L) { "startedAtEpochMs must be non-negative" }
        require(schemaVersion >= 1) { "schemaVersion must be positive" }
        // Same structural rules as AnkiSessionContext: contradictory qualification is refused at
        // construction, never repaired by guessing.
        require(collection == null || collection.backendId == backendId) {
            "A lock's collection must belong to its own backend"
        }
        require(deckRef == null || deckRef.backendId == backendId) {
            "A lock's deck must belong to its own backend"
        }
        require(collection?.collectionKey == null || deckRef?.collectionKey == null ||
            collection.collectionKey == deckRef.collectionKey) {
            "A lock's collection and deck must agree"
        }
    }

    /** The 21C identity context of this lock: same instance + same collection evidence. */
    val backendContext: AnkiBackendContext
        get() = AnkiBackendContext(backendId, collection)

    /**
     * Rebuild the live session context from this lock and the backend's *current* capabilities.
     * Capabilities are an observation, so they are always supplied by the caller from the live
     * backend — never persisted, never reused across a restart.
     */
    fun toSessionContext(capabilities: AnkiCapabilities): AnkiSessionContext =
        AnkiSessionContext(
            backendId = backendId,
            collection = collection,
            deckRef = deckRef,
            startedAtEpochMs = startedAtEpochMs,
            capabilities = capabilities,
            studySessionId = studySessionId
        )

    companion object {
        /** The only schema version this build writes. */
        const val CURRENT_SCHEMA_VERSION: Int = 1

        /** Capture the durable lock from a successfully bound live session context. */
        fun from(context: AnkiSessionContext): StudySessionBackendLock =
            StudySessionBackendLock(
                studySessionId = context.studySessionId,
                backendId = context.backendId,
                collection = context.collection,
                deckRef = context.deckRef,
                startedAtEpochMs = context.startedAtEpochMs,
                schemaVersion = CURRENT_SCHEMA_VERSION
            )
    }
}

/** What decoding persisted lock bytes yields. Absent storage is `null`, never a lock. */
sealed interface DecodedSessionLock {
    /** A structurally valid lock. Validity is not restorability — see [StudySessionRestorePolicy]. */
    data class Valid(val lock: StudySessionBackendLock) : DecodedSessionLock

    /**
     * Bytes were present but unusable. [reason] is a stable, content-free token. An invalid lock
     * is never repaired, never defaulted, and never rebound to another backend.
     */
    data class Invalid(val reason: String) : DecodedSessionLock

    /** Bytes name a schema newer than this build understands. Never downgraded or guessed. */
    data class UnsupportedVersion(val found: Int) : DecodedSessionLock
}

/**
 * Versioned JSON codec for [StudySessionBackendLock]. Unknown fields are ignored so additive
 * future fields do not break old readers; unknown *schemas* are refused rather than decoded.
 */
object StudySessionLockCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    fun encode(lock: StudySessionBackendLock): String = json.encodeToString(lock)

    /**
     * Decode persisted bytes. `null`/blank input means absent storage and decodes to `null`
     * (no session to restore), which is distinct from present-but-unusable ([Invalid]).
     */
    fun decode(raw: String?): DecodedSessionLock? {
        if (raw.isNullOrBlank()) return null
        val lock = try {
            json.decodeFromString<StudySessionBackendLock>(raw)
        } catch (_: IllegalArgumentException) {
            // Covers both malformed JSON and constructor-integrity refusal: either way the bytes
            // do not describe a lock this build may trust.
            return DecodedSessionLock.Invalid(REASON_LOCK_INTEGRITY_FAILURE)
        } catch (_: SerializationException) {
            return DecodedSessionLock.Invalid(REASON_LOCK_INTEGRITY_FAILURE)
        }
        if (lock.schemaVersion > StudySessionBackendLock.CURRENT_SCHEMA_VERSION) {
            return DecodedSessionLock.UnsupportedVersion(lock.schemaVersion)
        }
        if (lock.schemaVersion < 1) {
            return DecodedSessionLock.Invalid(REASON_LOCK_INTEGRITY_FAILURE)
        }
        return DecodedSessionLock.Valid(lock)
    }

    const val REASON_LOCK_INTEGRITY_FAILURE: String = "lock_integrity_failure"
}

/** The admission answer for a new StudySession start (CHECKPOINT 21G-03). */
sealed interface StudySessionAdmission {
    /** The session may bind: this exact lock is its immutable owner. */
    data class Admitted(val lock: StudySessionBackendLock) : StudySessionAdmission

    /**
     * The session must not start. [error] is the typed, content-free refusal; [reason] is its
     * stable token. A refusal publishes no lock and dispatches no scheduler traffic.
     */
    data class Refused(val error: AnkiError, val reason: String) : StudySessionAdmission
}

/**
 * Pure pre-binding admission for a new StudySession (CHECKPOINT 21G-03/21G-04).
 *
 * The caller supplies a *fresh* [BackendResolutionResult2] (resolved by GATE 21E immediately
 * before this call, never cached), the exact registered backend for the resolved id (or `null`
 * when the id is not registered), and the deck the user chose. The policy decides admission; it
 * performs no I/O, publishes nothing, and never re-resolves.
 *
 * Order: resolution → exact registration → operational readiness → required capability →
 * deck ownership → OP-S1 collection coherence. The first failure refuses; nothing after it runs.
 *
 * The OP-S1 verdict is fresh-bind coherence (21F `bindNewContext` parity): a live identity that
 * names another backend, or a deck choice that contradicts the live collection, refuses. Either
 * side silent admits — a new binding promises nothing — and the lock records the live collection
 * only. The continuation matrix governs the bound session (OP-S2), never the bind.
 */
object StudySessionAdmissionPolicy {
    fun admit(
        resolution: BackendResolutionResult2,
        backend: AnkiBackend?,
        deck: AnkiDeckRef,
        studySessionId: String,
        startedAtEpochMs: Long
    ): StudySessionAdmission {
        if (studySessionId.isBlank() || startedAtEpochMs < 0L) {
            return StudySessionAdmission.Refused(
                AnkiError.InvalidRequest(REASON_INVALID_SESSION_IDENTITY),
                REASON_INVALID_SESSION_IDENTITY
            )
        }
        val resolved = resolution as? BackendResolutionResult2.Resolved
            ?: return StudySessionAdmission.Refused(
                (resolution as BackendResolutionResult2.Failed).error,
                (resolution as BackendResolutionResult2.Failed).failure.name.lowercase()
            )
        val id = resolved.backendId
        if (backend == null || backend.id != id) {
            return StudySessionAdmission.Refused(
                AnkiError.BackendUnavailable(REASON_BACKEND_NOT_REGISTERED),
                REASON_BACKEND_NOT_REGISTERED
            )
        }
        val descriptor = AnkiBackendDescriptor.of(backend)
        if (!descriptor.isReachable) {
            return StudySessionAdmission.Refused(
                descriptor.availability.unavailabilityError()
                    ?: AnkiError.BackendUnavailable(REASON_BACKEND_UNAVAILABLE),
                REASON_BACKEND_UNAVAILABLE
            )
        }
        if (!descriptor.supportsAll(STUDY_SESSION_REQUIREMENTS)) {
            return StudySessionAdmission.Refused(
                AnkiError.UnsupportedAction(REASON_REQUIRED_CAPABILITY),
                REASON_REQUIRED_CAPABILITY_UNAVAILABLE
            )
        }
        if (deck.backendId != id) {
            return StudySessionAdmission.Refused(
                AnkiError.InvalidRequest(REASON_FOREIGN_DECK),
                REASON_FOREIGN_DECK
            )
        }
        // OP-S1 — the binding moment. A fresh bind has no prior binding to continue, so — exactly
        // like 21F's `bindNewContext` — admission runs coherence, not the continuation matrix
        // (whose DEGRADED row answers "evidence was lost", which is unaskable before anything was
        // bound): the live identity must cohere with the resolved backend, and the deck choice
        // must not contradict the live collection. The lock records the LIVE collection only — a
        // deck label the backend never confirmed is choice metadata, never bound evidence, so it
        // can neither veto this bind nor poison a later restore.
        val live = backend.currentCollectionIdentity
        if (live != null && live.backendId != id) {
            return StudySessionAdmission.Refused(
                AnkiError.InvalidRequest(REASON_FOREIGN_COLLECTION),
                REASON_FOREIGN_COLLECTION
            )
        }
        val liveKey = live?.collectionKey
        val deckKey = deck.collectionKey
        if (deckKey != null && liveKey != null && deckKey != liveKey) {
            return StudySessionAdmission.Refused(
                AnkiError.InvalidRequest(REASON_COLLECTION_MISMATCH),
                REASON_COLLECTION_MISMATCH
            )
        }
        return StudySessionAdmission.Admitted(
            StudySessionBackendLock(
                studySessionId = studySessionId,
                backendId = id,
                collection = liveKey?.let { AnkiCollectionIdentity(id, it) },
                deckRef = deck,
                startedAtEpochMs = startedAtEpochMs
            )
        )
    }

    const val REASON_INVALID_SESSION_IDENTITY: String = "invalid_session_identity"
    const val REASON_BACKEND_NOT_REGISTERED: String = "backend_not_registered"
    const val REASON_BACKEND_UNAVAILABLE: String = "backend_unavailable"
    const val REASON_REQUIRED_CAPABILITY: String = "required_capability"
    const val REASON_REQUIRED_CAPABILITY_UNAVAILABLE: String = "required_capability_unavailable"
    const val REASON_FOREIGN_DECK: String = "foreign_deck"
    const val REASON_FOREIGN_COLLECTION: String = "foreign_collection_identity"
    const val REASON_COLLECTION_MISMATCH: String = "collection_mismatch"
}

/** The continuation answer for scheduler work inside a bound StudySession (OP-S2). */
sealed interface StudySessionContinuation {
    /** The operation may proceed against the locked backend, under this [assurance]. */
    data class Permitted(val assurance: AnkiIdentityAssurance) : StudySessionContinuation

    /**
     * The operation must not proceed. The session keeps its lock; the caller reports [error] on
     * the locked identity and waits for the original backend. Never a substitution.
     */
    data class Blocked(val reason: String, val error: AnkiError) : StudySessionContinuation
}

/**
 * Pure continuation gate for a bound StudySession (CHECKPOINT 21G-13/21G-14/21G-15/21G-16).
 *
 * The caller supplies the session's immutable [lock] and the exact registered backend for it (or
 * `null` when it is not registered). The policy checks registration → readiness → capability →
 * OP-S2 collection verdict. It performs no I/O and never consults preference, AUTO, or a sibling.
 *
 * [boundIsLiveInThisProcess] must be true only when the caller holds this lock's live in-process
 * binding (mid-session revalidation). A post-restart caller passes false: after process death
 * there is no in-process binding to continue, so a keyless lock restores as nominal-unverified —
 * disclosed, never described as continuity.
 */
object StudySessionContinuationPolicy {
    fun check(
        lock: StudySessionBackendLock,
        backend: AnkiBackend?,
        boundIsLiveInThisProcess: Boolean
    ): StudySessionContinuation {
        if (backend == null || backend.id != lock.backendId) {
            return StudySessionContinuation.Blocked(
                StudySessionAdmissionPolicy.REASON_BACKEND_NOT_REGISTERED,
                AnkiError.BackendUnavailable(StudySessionAdmissionPolicy.REASON_BACKEND_NOT_REGISTERED)
            )
        }
        val descriptor = AnkiBackendDescriptor.of(backend)
        if (!descriptor.isReachable) {
            return StudySessionContinuation.Blocked(
                StudySessionAdmissionPolicy.REASON_BACKEND_UNAVAILABLE,
                descriptor.availability.unavailabilityError()
                    ?: AnkiError.BackendUnavailable(StudySessionAdmissionPolicy.REASON_BACKEND_UNAVAILABLE)
            )
        }
        if (!descriptor.supportsAll(STUDY_SESSION_REQUIREMENTS)) {
            return StudySessionContinuation.Blocked(
                StudySessionAdmissionPolicy.REASON_REQUIRED_CAPABILITY_UNAVAILABLE,
                AnkiError.UnsupportedAction(StudySessionAdmissionPolicy.REASON_REQUIRED_CAPABILITY)
            )
        }
        val check = AnkiIdentityAssurancePolicy.check(
            bound = lock.backendContext.observation,
            live = AnkiIdentityObservation(
                lock.backendId, backend.currentCollectionIdentity?.collectionKey
            ),
            boundIsLiveInThisProcess = boundIsLiveInThisProcess
        )
        return when (val verdict = AnkiIdentityContinuation.continuationVerdict(
            AnkiOperationClass.OP_S2, check, AnkiOperationFacts()
        )) {
            is AnkiContinuationVerdict.Permitted ->
                StudySessionContinuation.Permitted(verdict.assurance)
            is AnkiContinuationVerdict.Blocked -> StudySessionContinuation.Blocked(
                blockReasonToken(verdict.reason), blockError(verdict.reason)
            )
        }
    }
}

/** The restore answer for a persisted StudySession lock (CHECKPOINT 21G-07). */
sealed interface StudySessionRestore {
    /**
     * The persisted lock is valid and its backend satisfies the continuation contract. The caller
     * rebuilds the live session context from [lock] with the backend's *current* capabilities and
     * must run the ledger recovery scans before any scheduler traffic; when
     * [hasUnresolvedTransactions] is true the next card must not be fetched until the durable
     * transaction barrier permits it.
     */
    data class Restored(
        val lock: StudySessionBackendLock,
        val assurance: AnkiIdentityAssurance,
        val hasUnresolvedTransactions: Boolean
    ) : StudySessionRestore

    /**
     * The lock is valid but the session cannot continue now (backend missing/unavailable,
     * capability lost, collection mismatch/degraded). The lock is preserved, not rewritten.
     */
    data class Blocked(val reason: String, val error: AnkiError) : StudySessionRestore

    /**
     * There is no usable lock (absent, undecodable, or naming an unknown schema). The session is
     * not restored, and no backend — AUTO or otherwise — is substituted for it.
     */
    data class Invalid(val reason: String) : StudySessionRestore
}

/**
 * Pure restoration for a persisted StudySession lock (CHECKPOINT 21G-07, DECISION-21B-14).
 *
 * Restoration is continuation ([AnkiOperationClass.OP_S2]), not a new binding: the persisted
 * [AnkiBackendId] is required and non-defaulted, an undecodable identity invalidates the session,
 * and AUTO is never invoked for an existing session. Transaction records restore under OP-P
 * through their own ledgers (GATE 21H); this policy only reports whether unresolved transactions
 * were observed so the caller can hold the next-card barrier.
 *
 * [boundIsLiveInThisProcess] is false for the design case (restore after process death) and true
 * only when the caller rehydrates from the store without an intervening death.
 */
object StudySessionRestorePolicy {
    fun restore(
        decoded: DecodedSessionLock?,
        lookup: (AnkiBackendId) -> AnkiBackend?,
        hasUnresolvedTransactions: Boolean,
        boundIsLiveInThisProcess: Boolean
    ): StudySessionRestore {
        if (decoded == null) return StudySessionRestore.Invalid(REASON_NO_PERSISTED_LOCK)
        when (decoded) {
            is DecodedSessionLock.Invalid ->
                return StudySessionRestore.Invalid(decoded.reason)
            is DecodedSessionLock.UnsupportedVersion ->
                return StudySessionRestore.Invalid(REASON_UNSUPPORTED_LOCK_SCHEMA)
            is DecodedSessionLock.Valid -> Unit
        }
        val lock = (decoded as DecodedSessionLock.Valid).lock
        val backend = lookup(lock.backendId)
        return when (val continuation = StudySessionContinuationPolicy.check(
            lock, backend, boundIsLiveInThisProcess
        )) {
            is StudySessionContinuation.Permitted -> StudySessionRestore.Restored(
                lock = lock,
                assurance = continuation.assurance,
                hasUnresolvedTransactions = hasUnresolvedTransactions
            )
            is StudySessionContinuation.Blocked -> StudySessionRestore.Blocked(
                continuation.reason, continuation.error
            )
        }
    }

    const val REASON_NO_PERSISTED_LOCK: String = "no_persisted_lock"
    const val REASON_UNSUPPORTED_LOCK_SCHEMA: String = "unsupported_lock_schema"
}

/** Stable, content-free token for a continuation block reason. */
internal fun blockReasonToken(reason: AnkiContinuationBlockReason): String = when (reason) {
    AnkiContinuationBlockReason.COLLECTION_MISMATCH -> "collection_mismatch"
    AnkiContinuationBlockReason.IDENTITY_UNVERIFIABLE_DEGRADED -> "collection_identity_degraded"
    AnkiContinuationBlockReason.FOREIGN_BACKEND -> "foreign_backend"
    AnkiContinuationBlockReason.NEW_BINDING_BLOCKED_BY_APPEARED_KEY -> "appeared_collection_key"
    AnkiContinuationBlockReason.NEW_BINDING_HAS_NO_PRIOR_IDENTITY -> "new_binding_has_no_prior_identity"
    AnkiContinuationBlockReason.CONTINUITY_IS_NOT_COLLECTION_EVIDENCE ->
        "continuity_is_not_collection_evidence"
    AnkiContinuationBlockReason.NON_APPLICATION_PROOF_NEEDS_COLLECTION_IDENTITY ->
        "non_application_proof_needs_collection_identity"
    AnkiContinuationBlockReason.RECONCILIATION_REQUIRES_AUTHORITATIVE_MATCH ->
        "reconciliation_requires_authoritative_match"
    AnkiContinuationBlockReason.NO_IN_PROCESS_BINDING -> "no_in_process_binding"
    AnkiContinuationBlockReason.FAMILY_REQUIRES_COLLECTION_PROOF -> "family_requires_collection_proof"
}

/**
 * Typed refusal for a continuation block. Identity blocks are request/context refusals with the
 * stable token as the detail, so diagnostics keep the exact reason
 * (`invalid_request:collection_mismatch`, never a bare "unavailable").
 */
internal fun blockError(reason: AnkiContinuationBlockReason): AnkiError =
    AnkiError.InvalidRequest(blockReasonToken(reason))

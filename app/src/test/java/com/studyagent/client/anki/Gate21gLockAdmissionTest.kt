package com.studyagent.client.anki

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCollectionIdentity
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiIdentityAssurance
import com.studyagent.client.core.anki.AnkiSessionContext
import com.studyagent.client.core.anki.AnkiUnverifiabilityQualifier
import com.studyagent.client.core.anki.BackendResolutionFailure
import com.studyagent.client.core.anki.BackendResolutionReason
import com.studyagent.client.core.anki.BackendResolutionResult2
import com.studyagent.client.core.anki.DecodedSessionLock
import com.studyagent.client.core.anki.StudySessionAdmission
import com.studyagent.client.core.anki.StudySessionAdmissionPolicy
import com.studyagent.client.core.anki.StudySessionBackendLock
import com.studyagent.client.core.anki.StudySessionContinuation
import com.studyagent.client.core.anki.StudySessionContinuationPolicy
import com.studyagent.client.core.anki.StudySessionLockCodec
import com.studyagent.client.core.anki.StudySessionRestore
import com.studyagent.client.core.anki.StudySessionRestorePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * GATE 21G — the session lock codec and its three pure policies (VER-21G-01…VER-21G-16).
 *
 * Admission (OP-S1 fresh bind), continuation (OP-S2 bound session) and restore (OP-S2 persisted
 * lock) are total functions over explicit inputs: no I/O, no preference, no AUTO, no registry.
 * Every refusal carries a typed error plus a stable content-free token.
 */
class Gate21gLockAdmissionTest {

    private val local = AnkiBackendId.AnkiDroidLocal
    private val pc = AnkiBackendId.PcAgent("p1")

    private fun resolved(id: AnkiBackendId = local): BackendResolutionResult2.Resolved =
        BackendResolutionResult2.Resolved(id, BackendResolutionReason.ONLY_ELIGIBLE_CANDIDATE)

    private fun fake(
        id: AnkiBackendId = local,
        liveKey: String? = null
    ): FakeAnkiBackend = FakeAnkiBackend(id = id).also {
        it.currentCollectionIdentity = liveKey?.let { key -> AnkiCollectionIdentity(id, key) }
    }

    private fun deck(key: String? = null): AnkiDeckRef = AnkiDeckRef(local, "1", key)

    private fun lock(key: String? = null): StudySessionBackendLock = StudySessionBackendLock(
        studySessionId = "s1",
        backendId = local,
        collection = key?.let { AnkiCollectionIdentity(local, it) },
        deckRef = null,
        startedAtEpochMs = 100L
    )

    // --------------------------------------------------------------- admission: the happy cells

    @Test fun `admission binds the resolved backend and records the live collection`() {
        val admitted = StudySessionAdmissionPolicy.admit(
            resolved(), fake(liveKey = "live-1"), deck("live-1"), "s1", 100L
        ) as StudySessionAdmission.Admitted
        assertEquals("s1", admitted.lock.studySessionId)
        assertEquals(local, admitted.lock.backendId)
        assertEquals("live-1", admitted.lock.collection?.collectionKey)
        assertEquals(local, admitted.lock.collection?.backendId)
        assertEquals(deck("live-1"), admitted.lock.deckRef)
        assertEquals(100L, admitted.lock.startedAtEpochMs)
    }

    @Test fun `admission records unknown collection when the backend reports nothing`() {
        // The deck label is choice metadata, never bound evidence: a mute backend neither vetoes
        // the bind nor promotes the label into the lock.
        val admitted = StudySessionAdmissionPolicy.admit(
            resolved(), fake(), deck("deck-label"), "s1", 100L
        ) as StudySessionAdmission.Admitted
        assertNull(admitted.lock.collection)
        assertEquals("deck-label", admitted.lock.deckRef?.collectionKey)
    }

    @Test fun `admission records the live collection when the deck is unkeyed`() {
        val admitted = StudySessionAdmissionPolicy.admit(
            resolved(), fake(liveKey = "live-2"), deck(), "s1", 100L
        ) as StudySessionAdmission.Admitted
        assertEquals("live-2", admitted.lock.collection?.collectionKey)
    }

    // ------------------------------------------------------------ admission: the refusal cells

    @Test fun `admission refuses a deck that contradicts the live collection`() {
        val refused = StudySessionAdmissionPolicy.admit(
            resolved(), fake(liveKey = "B"), deck("A"), "s1", 100L
        ) as StudySessionAdmission.Refused
        assertEquals("collection_mismatch", refused.reason)
        assertEquals("collection_mismatch", (refused.error as AnkiError.InvalidRequest).detail)
    }

    @Test fun `admission refuses a live identity naming another backend`() {
        val backend = fake().also {
            it.currentCollectionIdentity = AnkiCollectionIdentity(pc, "x")
        }
        val refused = StudySessionAdmissionPolicy.admit(
            resolved(), backend, deck("x"), "s1", 100L
        ) as StudySessionAdmission.Refused
        assertEquals("foreign_collection_identity", refused.reason)
        assertTrue(refused.error is AnkiError.InvalidRequest)
    }

    @Test fun `admission maps a failed resolution to its typed error`() {
        val ambiguous = BackendResolutionResult2.Ambiguous(
            candidates = setOf(local, pc), considered = setOf(local, pc)
        )
        val refused = StudySessionAdmissionPolicy.admit(
            ambiguous, fake(), deck(), "s1", 100L
        ) as StudySessionAdmission.Refused
        assertEquals(BackendResolutionFailure.AMBIGUOUS_CONFIGURATION.name.lowercase(), refused.reason)
        assertEquals("ambiguous_backend", (refused.error as AnkiError.InvalidRequest).detail)

        val none = BackendResolutionResult2.NoBackendConfigured(considered = emptySet())
        val refusedNone = StudySessionAdmissionPolicy.admit(
            none, null, deck(), "s1", 100L
        ) as StudySessionAdmission.Refused
        assertEquals("no_backend_configured", refusedNone.reason)
        assertTrue(refusedNone.error is AnkiError.BackendUnavailable)
    }

    @Test fun `admission refuses when the resolved backend is not the registered one`() {
        val missing = StudySessionAdmissionPolicy.admit(
            resolved(), null, deck(), "s1", 100L
        ) as StudySessionAdmission.Refused
        assertEquals("backend_not_registered", missing.reason)

        val swapped = StudySessionAdmissionPolicy.admit(
            resolved(), fake(id = pc), deck(), "s1", 100L
        ) as StudySessionAdmission.Refused
        assertEquals("backend_not_registered", swapped.reason)
    }

    @Test fun `admission refuses an unreachable backend`() {
        val down = FakeAnkiBackend(
            id = local, initialAvailability = AnkiAvailability.NotInstalled
        )
        val refused = StudySessionAdmissionPolicy.admit(
            resolved(), down, deck(), "s1", 100L
        ) as StudySessionAdmission.Refused
        assertEquals("backend_unavailable", refused.reason)
    }

    @Test fun `admission refuses a backend without the review capability`() {
        val incapable = FakeAnkiBackend(
            id = local,
            initialCapabilities = FakeAnkiBackend.REVIEW_CAPABILITIES.copy(review = false)
        )
        val refused = StudySessionAdmissionPolicy.admit(
            resolved(), incapable, deck(), "s1", 100L
        ) as StudySessionAdmission.Refused
        assertEquals("required_capability_unavailable", refused.reason)
        assertEquals("required_capability", (refused.error as AnkiError.UnsupportedAction).action)
    }

    @Test fun `admission refuses a foreign deck`() {
        val refused = StudySessionAdmissionPolicy.admit(
            resolved(), fake(), AnkiDeckRef(pc, "1"), "s1", 100L
        ) as StudySessionAdmission.Refused
        assertEquals("foreign_deck", refused.reason)
        assertTrue(refused.error is AnkiError.InvalidRequest)
    }

    @Test fun `admission refuses a blank session identity`() {
        val blank = StudySessionAdmissionPolicy.admit(
            resolved(), fake(), deck(), "  ", 100L
        ) as StudySessionAdmission.Refused
        assertEquals("invalid_session_identity", blank.reason)
        val negative = StudySessionAdmissionPolicy.admit(
            resolved(), fake(), deck(), "s1", -1L
        ) as StudySessionAdmission.Refused
        assertEquals("invalid_session_identity", negative.reason)
    }

    // ------------------------------------------------------------------ continuation (OP-S2)

    @Test fun `continuation permits the live session under continuity`() {
        val permitted = StudySessionContinuationPolicy.check(
            lock(), fake(), boundIsLiveInThisProcess = true
        ) as StudySessionContinuation.Permitted
        assertEquals(AnkiIdentityAssurance.SessionContinuityVerified, permitted.assurance)
    }

    @Test fun `continuation permits an authoritative collection match`() {
        val permitted = StudySessionContinuationPolicy.check(
            lock("k"), fake(liveKey = "k"), boundIsLiveInThisProcess = true
        ) as StudySessionContinuation.Permitted
        assertEquals(AnkiIdentityAssurance.AuthoritativeMatch("k"), permitted.assurance)
    }

    @Test fun `continuation blocks a collection mismatch`() {
        val blocked = StudySessionContinuationPolicy.check(
            lock("A"), fake(liveKey = "B"), boundIsLiveInThisProcess = true
        ) as StudySessionContinuation.Blocked
        assertEquals("collection_mismatch", blocked.reason)
        assertEquals("collection_mismatch", (blocked.error as AnkiError.InvalidRequest).detail)
    }

    @Test fun `continuation blocks degraded live evidence`() {
        val blocked = StudySessionContinuationPolicy.check(
            lock("k"), fake(), boundIsLiveInThisProcess = true
        ) as StudySessionContinuation.Blocked
        assertEquals("collection_identity_degraded", blocked.reason)
    }

    @Test fun `continuation blocks a key that appeared after a keyless bind`() {
        val blocked = StudySessionContinuationPolicy.check(
            lock(), fake(liveKey = "new"), boundIsLiveInThisProcess = true
        ) as StudySessionContinuation.Blocked
        assertEquals("collection_identity_degraded", blocked.reason)
    }

    @Test fun `post-death keyless continuation is nominal, never continuity`() {
        val permitted = StudySessionContinuationPolicy.check(
            lock(), fake(), boundIsLiveInThisProcess = false
        ) as StudySessionContinuation.Permitted
        val assurance = permitted.assurance as AnkiIdentityAssurance.IdentityUnverifiable
        assertEquals(AnkiUnverifiabilityQualifier.NOMINAL, assurance.qualifier)
    }

    @Test fun `continuation blocks when the locked backend is gone`() {
        val missing = StudySessionContinuationPolicy.check(
            lock(), null, boundIsLiveInThisProcess = true
        ) as StudySessionContinuation.Blocked
        assertEquals("backend_not_registered", missing.reason)
        val swapped = StudySessionContinuationPolicy.check(
            lock(), fake(id = pc), boundIsLiveInThisProcess = true
        ) as StudySessionContinuation.Blocked
        assertEquals("backend_not_registered", swapped.reason)
    }

    @Test fun `continuation blocks when the locked backend loses readiness or capability`() {
        val down = FakeAnkiBackend(
            id = local, initialAvailability = AnkiAvailability.NotInstalled
        )
        val unavailable = StudySessionContinuationPolicy.check(
            lock(), down, boundIsLiveInThisProcess = true
        ) as StudySessionContinuation.Blocked
        assertEquals("backend_unavailable", unavailable.reason)

        val incapable = FakeAnkiBackend(
            id = local,
            initialCapabilities = FakeAnkiBackend.REVIEW_CAPABILITIES.copy(review = false)
        )
        val unsupported = StudySessionContinuationPolicy.check(
            lock(), incapable, boundIsLiveInThisProcess = true
        ) as StudySessionContinuation.Blocked
        assertEquals("required_capability_unavailable", unsupported.reason)
    }

    // ----------------------------------------------------------------------- restore (OP-S2)

    @Test fun `absent bytes are not a session`() {
        var lookedUp = false
        val invalid = StudySessionRestorePolicy.restore(
            decoded = null,
            lookup = { lookedUp = true; null },
            hasUnresolvedTransactions = false,
            boundIsLiveInThisProcess = false
        ) as StudySessionRestore.Invalid
        assertEquals("no_persisted_lock", invalid.reason)
        assertFalse("absent storage must not consult any backend", lookedUp)
    }

    @Test fun `invalid bytes invalidate without substitution`() {
        var lookedUp = false
        val invalid = StudySessionRestorePolicy.restore(
            decoded = DecodedSessionLock.Invalid("lock_integrity_failure"),
            lookup = { lookedUp = true; fake() },
            hasUnresolvedTransactions = false,
            boundIsLiveInThisProcess = false
        ) as StudySessionRestore.Invalid
        assertEquals("lock_integrity_failure", invalid.reason)
        assertFalse("an invalid lock must not consult any backend", lookedUp)
    }

    @Test fun `a future schema is reported, never downgraded`() {
        val invalid = StudySessionRestorePolicy.restore(
            decoded = DecodedSessionLock.UnsupportedVersion(99),
            lookup = { fake() },
            hasUnresolvedTransactions = false,
            boundIsLiveInThisProcess = false
        ) as StudySessionRestore.Invalid
        assertEquals("unsupported_lock_schema", invalid.reason)
    }

    @Test fun `restore rebuilds a healthy lock and reports the transaction barrier`() {
        val backend = fake()
        val open = StudySessionRestorePolicy.restore(
            decoded = DecodedSessionLock.Valid(lock()),
            lookup = { backend },
            hasUnresolvedTransactions = true,
            boundIsLiveInThisProcess = false
        ) as StudySessionRestore.Restored
        assertEquals(lock(), open.lock)
        assertTrue(open.hasUnresolvedTransactions)

        val clear = StudySessionRestorePolicy.restore(
            decoded = DecodedSessionLock.Valid(lock()),
            lookup = { backend },
            hasUnresolvedTransactions = false,
            boundIsLiveInThisProcess = false
        ) as StudySessionRestore.Restored
        assertFalse(clear.hasUnresolvedTransactions)
    }

    @Test fun `restore preserves the lock but blocks when the backend is gone`() {
        val blocked = StudySessionRestorePolicy.restore(
            decoded = DecodedSessionLock.Valid(lock()),
            lookup = { null },
            hasUnresolvedTransactions = false,
            boundIsLiveInThisProcess = false
        ) as StudySessionRestore.Blocked
        assertEquals("backend_not_registered", blocked.reason)
        assertTrue(blocked.error is AnkiError.BackendUnavailable)
    }

    @Test fun `restore blocks on collection mismatch`() {
        val blocked = StudySessionRestorePolicy.restore(
            decoded = DecodedSessionLock.Valid(lock("A")),
            lookup = { fake(liveKey = "B") },
            hasUnresolvedTransactions = false,
            boundIsLiveInThisProcess = false
        ) as StudySessionRestore.Blocked
        assertEquals("collection_mismatch", blocked.reason)
    }

    // ------------------------------------------------------------------ codec and model law

    @Test fun `codec round-trips a full lock`() {
        val full = StudySessionBackendLock(
            studySessionId = "s9",
            backendId = pc,
            collection = AnkiCollectionIdentity(pc, "c9"),
            deckRef = AnkiDeckRef(pc, "7", "c9"),
            startedAtEpochMs = 4242L
        )
        val decoded = StudySessionLockCodec.decode(StudySessionLockCodec.encode(full))
        assertEquals(DecodedSessionLock.Valid(full), decoded)
    }

    @Test fun `codec round-trips a minimal lock`() {
        val decoded = StudySessionLockCodec.decode(StudySessionLockCodec.encode(lock()))
        assertEquals(DecodedSessionLock.Valid(lock()), decoded)
    }

    @Test fun `absent raw decodes to null`() {
        assertNull(StudySessionLockCodec.decode(null))
        assertNull(StudySessionLockCodec.decode(""))
        assertNull(StudySessionLockCodec.decode("   "))
    }

    @Test fun `malformed bytes are integrity failures`() {
        listOf("{oops", "[1,2]", "42", "\"s1\"").forEach { raw ->
            val decoded = StudySessionLockCodec.decode(raw) as DecodedSessionLock.Invalid
            assertEquals("lock_integrity_failure", decoded.reason)
        }
        // Valid JSON but not a lock: constructor integrity refuses the blank session.
        val raw = StudySessionLockCodec.encode(lock()).replaceFirst("\"s1\"", "\"\"")
        val decoded = StudySessionLockCodec.decode(raw) as DecodedSessionLock.Invalid
        assertEquals("lock_integrity_failure", decoded.reason)
    }

    @Test fun `a future schema is reported, never decoded`() {
        val future = lock().copy(schemaVersion = 999)
        val decoded = StudySessionLockCodec.decode(StudySessionLockCodec.encode(future))
        assertEquals(DecodedSessionLock.UnsupportedVersion(999), decoded)
    }

    @Test fun `unknown future fields do not break the reader`() {
        val raw = StudySessionLockCodec.encode(lock())
        val extended = raw.dropLast(1) + ",\"zzz\":1}"
        assertEquals(DecodedSessionLock.Valid(lock()), StudySessionLockCodec.decode(extended))
    }

    @Test fun `an unknown backend discriminator is an integrity failure`() {
        val raw = StudySessionLockCodec.encode(lock()).replace("ankidroid_local", "wat")
        val decoded = StudySessionLockCodec.decode(raw) as DecodedSessionLock.Invalid
        assertEquals("lock_integrity_failure", decoded.reason)
    }

    @Test fun `the constructor refuses contradictory locks`() {
        assertRefused("collection of another backend") {
            lock("k").copy(collection = AnkiCollectionIdentity(pc, "k"))
        }
        assertRefused("deck of another backend") {
            lock().copy(deckRef = AnkiDeckRef(pc, "1"))
        }
        assertRefused("collection and deck disagree") {
            StudySessionBackendLock("s1", local, AnkiCollectionIdentity(local, "A"),
                AnkiDeckRef(local, "1", "B"), 100L)
        }
        assertRefused("blank session") { lock().copy(studySessionId = " ") }
        assertRefused("negative start") { lock().copy(startedAtEpochMs = -1L) }
        assertRefused("non-positive schema") { lock().copy(schemaVersion = 0) }
    }

    @Test fun `live capabilities always win over the lock`() {
        val context = AnkiSessionContext(
            backendId = local,
            collection = AnkiCollectionIdentity(local, "c"),
            deckRef = AnkiDeckRef(local, "1", "c"),
            startedAtEpochMs = 100L,
            capabilities = FakeAnkiBackend.REVIEW_CAPABILITIES,
            studySessionId = "s1"
        )
        val lock = StudySessionBackendLock.from(context)
        assertEquals("c", lock.collection?.collectionKey)
        assertEquals(AnkiDeckRef(local, "1", "c"), lock.deckRef)
        val live = FakeAnkiBackend.REVIEW_CAPABILITIES.copy(review = false)
        val rebuilt = lock.toSessionContext(live)
        assertEquals(false, rebuilt.capabilities.review)
        assertEquals("s1", rebuilt.studySessionId)
        assertEquals(local, rebuilt.backendId)
    }

    private fun assertRefused(what: String, build: () -> StudySessionBackendLock) {
        try {
            build()
        } catch (_: IllegalArgumentException) {
            return
        }
        fail("a lock with $what must be refused at construction")
    }

}

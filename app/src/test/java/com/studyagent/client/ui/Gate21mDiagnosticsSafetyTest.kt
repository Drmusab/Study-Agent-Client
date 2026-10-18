package com.studyagent.client.ui

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendDescriptor
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendRoutingDiagnostics
import com.studyagent.client.core.anki.AnkiBackendState
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiCollectionIdentity
import com.studyagent.client.core.anki.AnkiIdentityObservation
import com.studyagent.client.core.anki.AnkiRequiredCapability
import com.studyagent.client.core.anki.AnkiSessionContext
import com.studyagent.client.core.anki.AnkiTransactionFamily
import com.studyagent.client.core.anki.AnkiUnresolvedTransactionFact
import com.studyagent.client.core.anki.AnkiUnresolvedTransactionRead
import com.studyagent.client.core.anki.AnkiBackendMode
import com.studyagent.client.core.anki.BackendPreference
import com.studyagent.client.core.anki.BackendSelectionReason
import com.studyagent.client.core.anki.STUDY_SESSION_REQUIREMENTS
import com.studyagent.client.core.anki.customstudy.AnkiCustomStudyCapabilities
import com.studyagent.client.ui.components.anki.AnkiBackendDiagnosticsInput
import com.studyagent.client.ui.components.anki.AnkiReadContextOwnerUi
import com.studyagent.client.ui.components.anki.AnkiTransactionFamilyUi
import com.studyagent.client.ui.components.anki.assuranceToken
import com.studyagent.client.ui.components.anki.buildAnkiBackendDiagnosticsLines
import com.studyagent.client.ui.components.anki.groupPendingTransactions
import com.studyagent.client.ui.components.anki.notProjectedTransactionGroup
import com.studyagent.client.ui.components.anki.pendingTransactionGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 21M Revision 2 — the **safety** of the read-only diagnostics projection.
 *
 * Two properties are pinned here, and they are the ones a diagnostics surface silently loses:
 *
 * 1. *nothing leaks*: rendered values are enum names, stable identities, counts and fixed sentinel
 *    phrases. Availability free text, collection key values, endpoints, card and note content never
 *    reach a row, because a diagnostics report is copyable and shareable (CHECKPOINT 21M-18/22,
 *    DECISION-21B-27, INV-21D-19);
 * 2. *nothing is fabricated*: a projection that was not supplied, a family that could not be read and
 *    a count of zero are three different statements, and an assurance category is rendered verbatim
 *    rather than reworded as "same collection" (CHECKPOINT 21M-17/19).
 *
 * There is also no mutation vocabulary anywhere in the output: the surface has no authority to retry,
 * force, clear or reassign anything (AUDIT-21M-R2-13/14).
 */
class Gate21mDiagnosticsSafetyTest {

    private val capabilities = AnkiCapabilities(review = true, deckListing = true)
    private val ankiDroid = AnkiBackendId.AnkiDroidLocal
    private val pcHome = AnkiBackendId.PcAgent("home")

    /** Free text a backend may publish and that must never be rendered. */
    private val forbiddenFragments = listOf(
        "content://com.ichi2.anki.flashcards",
        "SecurityException: provider not found",
        "binder proxy died",
        "192.168.1.50:8765",
        "https://agent.example.com/anki",
        "Bearer ",
        "col-secret-key-9f3a",
        "What is the capital of France?",
        "Front field text",
        "deck:Graduate/Exams"
    )

    private fun state(
        id: AnkiBackendId,
        availability: AnkiAvailability = AnkiAvailability.Ready(capabilities),
        implemented: AnkiCapabilities? = capabilities
    ) = AnkiBackendState(AnkiBackendDescriptor(id, availability, capabilities), implemented)

    private fun lock(id: AnkiBackendId, session: String, key: String?) = session to AnkiSessionContext(
        backendId = id,
        collection = key?.let { AnkiCollectionIdentity(id, it) },
        deckRef = null,
        startedAtEpochMs = 1L,
        capabilities = capabilities,
        studySessionId = session
    )

    private fun lines(input: AnkiBackendDiagnosticsInput) = buildAnkiBackendDiagnosticsLines(input)

    private fun assertNothingLeaks(lines: List<Pair<String, String>>) {
        lines.forEach { (label, value) ->
            forbiddenFragments.forEach { fragment ->
                assertFalse("label leaked `$fragment`: $label", label.contains(fragment, ignoreCase = true))
                assertFalse("value leaked `$fragment`: $value", value.contains(fragment, ignoreCase = true))
            }
        }
    }

    // ------------------------------------------------------------------ CHECKPOINT 21M-18 / 22

    @Test
    fun `availability free text never reaches a diagnostics row`() {
        val input = AnkiBackendDiagnosticsInput(
            states = listOf(
                state(
                    ankiDroid,
                    AnkiAvailability.PermissionRequired("SecurityException: provider not found")
                ),
                state(
                    pcHome,
                    AnkiAvailability.TemporarilyUnavailable("binder proxy died")
                ),
                state(
                    AnkiBackendId.PcAgent("office"),
                    AnkiAvailability.ProviderUnavailable("content://com.ichi2.anki.flashcards")
                )
            ),
            preference = BackendPreference.Explicit(ankiDroid),
            liveCollectionKeys = mapOf(ankiDroid to "col-secret-key-9f3a"),
            sessionLocks = mapOf(lock(ankiDroid, "study-1", "col-secret-key-9f3a"))
        )

        val rendered = lines(input).map { it.toPair() }
        assertNothingLeaks(rendered)

        // What *is* rendered: the locked token vocabulary and the identity, nothing else.
        val values = rendered.map { it.second }
        assertTrue(values.contains("PERMISSION_REQUIRED"))
        assertTrue(values.contains("TEMPORARILY_UNAVAILABLE"))
        assertTrue(values.contains("PROVIDER_UNAVAILABLE"))
        assertTrue(values.contains("ankidroid_local"))
        assertTrue(values.contains("pc_agent:home"))
        assertTrue(values.contains("pc_agent:office"))
        assertTrue(
            "collection identity is reported as presence, never as the key",
            values.contains("published (value withheld)")
        )
        assertFalse(rendered.any { it.second.contains("col-secret") })
    }

    @Test
    fun `a collection key is never measured into a value, only into presence`() {
        val measured = lines(
            AnkiBackendDiagnosticsInput(
                states = listOf(state(ankiDroid)),
                preference = BackendPreference.Auto,
                liveCollectionKeys = mapOf(ankiDroid to "col-secret-key-9f3a")
            )
        ).map { it.toPair() }
        assertTrue(measured.any { it.second == "published (value withheld)" })

        val nonePublished = lines(
            AnkiBackendDiagnosticsInput(
                states = listOf(state(ankiDroid)),
                preference = BackendPreference.Auto,
                liveCollectionKeys = mapOf(ankiDroid to null)
            )
        ).map { it.toPair() }
        assertTrue(nonePublished.any { it.second == "none published (identity unverifiable)" })

        val notMeasured = lines(
            AnkiBackendDiagnosticsInput(
                states = listOf(state(ankiDroid)),
                preference = BackendPreference.Auto
            )
        ).map { it.toPair() }
        assertTrue(notMeasured.any { it.second == "NOT_MEASURED" })
        assertNothingLeaks(measured + nonePublished + notMeasured)
    }

    @Test
    fun `no rendered row offers a mutation, retry-all or reassignment`() {
        val rendered = lines(
            AnkiBackendDiagnosticsInput(
                states = listOf(state(ankiDroid), state(pcHome)),
                preference = BackendPreference.Auto,
                sessionLocks = mapOf(lock(ankiDroid, "study-1", "k")),
                pendingTransactions = pendingTransactionGroups(
                    AnkiUnresolvedTransactionRead(
                        facts = listOf(
                            AnkiUnresolvedTransactionFact(
                                AnkiTransactionFamily.NOTE_MUTATION, ankiDroid, "CONFLICT"
                            )
                        ),
                        loaded = true
                    )
                ),
                pendingTransactionsLoaded = true
            )
        ).map { it.toPair() }

        listOf("retry all", "force commit", "force-commit", "clear ", "delete", "reassign", "reset status")
            .forEach { phrase ->
                rendered.forEach { (label, value) ->
                    assertFalse("no `$phrase` control may be offered", label.contains(phrase, ignoreCase = true))
                    assertFalse("no `$phrase` control may be offered", value.contains(phrase, ignoreCase = true))
                }
            }
    }

    // ------------------------------------------------------------------ CHECKPOINT 21M-19

    @Test
    fun `every identity assurance category is rendered verbatim`() {
        fun token(boundKey: String?, liveKey: String?, live: Boolean, sameBackend: Boolean = true) =
            assuranceToken(
                bound = AnkiIdentityObservation(ankiDroid, boundKey),
                live = AnkiIdentityObservation(if (sameBackend) ankiDroid else pcHome, liveKey),
                boundIsLiveInThisProcess = live
            )

        assertEquals("AUTHORITATIVE_MATCH", token("k", "k", true))
        assertEquals("AUTHORITATIVE_MISMATCH", token("k", "other", true))
        assertEquals("IDENTITY_UNVERIFIABLE(degraded)", token("k", null, true))
        assertEquals("IDENTITY_UNVERIFIABLE(degraded)", token(null, "k", true))
        assertEquals("SESSION_CONTINUITY_VERIFIED", token(null, null, true))
        assertEquals("IDENTITY_UNVERIFIABLE(nominal)", token(null, null, false))
        assertEquals("ForeignBackend", token("k", "k", true, sameBackend = false))

        // Never reworded as a positive continuity claim.
        listOf("AUTHORITATIVE_MATCH", "IDENTITY_UNVERIFIABLE(nominal)").forEach { rendered ->
            assertFalse(rendered.contains("same collection", ignoreCase = true))
        }
    }

    @Test
    fun `the active session owner line reports assurance without inventing continuity`() {
        val verified = lines(
            AnkiBackendDiagnosticsInput(
                states = listOf(state(ankiDroid)),
                preference = BackendPreference.Auto,
                sessionLocks = mapOf(lock(ankiDroid, "study-1", null)),
                liveCollectionKeys = mapOf(ankiDroid to null)
            )
        ).map { it.toPair() }
        assertTrue(verified.contains("  collection assurance" to "SESSION_CONTINUITY_VERIFIED"))
        assertTrue(verified.contains("Active study session" to "ankidroid_local"))

        val degraded = lines(
            AnkiBackendDiagnosticsInput(
                states = listOf(state(ankiDroid)),
                preference = BackendPreference.Auto,
                sessionLocks = mapOf(lock(ankiDroid, "study-1", "col-secret-key-9f3a")),
                liveCollectionKeys = mapOf(ankiDroid to null)
            )
        ).map { it.toPair() }
        assertTrue(degraded.contains("  collection assurance" to "IDENTITY_UNVERIFIABLE(degraded)"))
        assertNothingLeaks(verified + degraded)
    }

    @Test
    fun `several active sessions are counted and no single owner is named`() {
        val rendered = lines(
            AnkiBackendDiagnosticsInput(
                states = listOf(state(ankiDroid), state(pcHome)),
                preference = BackendPreference.Auto,
                sessionLocks = mapOf(lock(ankiDroid, "s1", null), lock(pcHome, "s2", null))
            )
        ).map { it.toPair() }

        assertTrue(rendered.contains("Active study sessions" to "2 (no single owner named)"))
        assertFalse(rendered.any { it.first == "Active study session" })
    }

    // ------------------------------------------------------------------ CHECKPOINT 21M-16 / 21-35

    @Test
    fun `the four selection roles are separate lines and are never invented`() {
        val notProjected = lines(
            AnkiBackendDiagnosticsInput(states = listOf(state(ankiDroid)), preference = null)
        ).map { it.toPair() }

        assertTrue(notProjected.contains("Resolved backend" to "NOT_PROJECTED"))
        assertTrue(notProjected.contains("Locked session backend" to "NOT_PROJECTED"))
        assertTrue(notProjected.contains("Transaction backend" to "NOT_PROJECTED"))
        assertTrue(notProjected.contains("Preferred backend (token)" to "NOT_PROJECTED"))

        val projected = lines(
            AnkiBackendDiagnosticsInput(
                states = listOf(state(ankiDroid)),
                preference = BackendPreference.Explicit(ankiDroid),
                routing = AnkiBackendRoutingDiagnostics(
                    preferred = AnkiBackendMode.ANKIDROID_LOCAL,
                    resolved = ankiDroid,
                    resolvedReason = BackendSelectionReason.EXPLICIT_PREFERENCE,
                    lockedSessionBackend = pcHome,
                    lockedSessionCount = 1,
                    transactionBackend = pcHome,
                    availability = mapOf(ankiDroid to "READY", pcHome to "AGENT_DISCONNECTED")
                )
            )
        ).map { it.toPair() }

        assertTrue(projected.contains("Resolved backend" to "ankidroid_local"))
        assertTrue(projected.contains("Resolution reason" to "EXPLICIT_PREFERENCE"))
        // The locked session's backend differs from the resolved one, and both are shown as they are.
        assertTrue(projected.contains("Locked session backend" to "pc_agent:home"))
        assertTrue(projected.contains("Locked session count" to "1"))
        assertTrue(projected.contains("Transaction backend" to "pc_agent:home"))
        assertTrue(projected.contains("Preferred backend (token)" to "explicit:ankidroid_local"))
    }

    @Test
    fun `an unconfigured user-selectable kind is stated as not configured, not as down`() {
        val rendered = lines(
            AnkiBackendDiagnosticsInput(states = listOf(state(ankiDroid)), preference = BackendPreference.Auto)
        ).map { it.toPair() }

        assertTrue(rendered.contains("Configured ANKIDROID_LOCAL" to "1"))
        assertTrue(rendered.contains("Configured PC_AGENT" to "0 (no adapter wired in this build)"))
        assertTrue(rendered.contains("Configured backends" to "1"))
    }

    @Test
    fun `capability truth is rendered on both dimensions for every requirement`() {
        val rendered = lines(
            AnkiBackendDiagnosticsInput(
                states = listOf(
                    state(
                        ankiDroid,
                        AnkiAvailability.AgentDisconnected,
                        implemented = capabilities.copy(editNoteFields = true)
                    )
                ),
                preference = BackendPreference.Auto
            )
        ).map { it.toPair() }

        AnkiRequiredCapability.entries.forEach { required ->
            val line = rendered.single { it.first == "  ${required.name}" }
            assertTrue(
                "$required must be rendered as implemented/readiness",
                line.second.matches(Regex("(SUPPORTED|UNSUPPORTED|UNKNOWN)/(READY|NOT_READY|UNKNOWN)"))
            )
        }
        assertTrue(rendered.contains("  EDIT_NOTE_FIELDS" to "SUPPORTED/NOT_READY"))
        assertTrue(rendered.contains("  availability" to "AGENT_DISCONNECTED"))
        assertTrue(rendered.contains("  capability evidence" to "STATIC_CLAIM"))
    }

    @Test
    fun `custom-study truth is a token, and an absent claim is not projected`() {
        val rendered = lines(
            AnkiBackendDiagnosticsInput(
                states = listOf(state(ankiDroid)),
                preference = BackendPreference.Auto,
                customStudyClaims = mapOf(ankiDroid to AnkiCustomStudyCapabilities.NONE)
            )
        ).map { it.toPair() }
        assertTrue(rendered.contains("  CUSTOM_STUDY" to "CUSTOM_STUDY_NOT_IMPLEMENTED"))

        val unclaimed = lines(
            AnkiBackendDiagnosticsInput(states = listOf(state(ankiDroid)), preference = BackendPreference.Auto)
        ).map { it.toPair() }
        assertTrue(unclaimed.contains("  CUSTOM_STUDY" to "NOT_PROJECTED"))
    }

    @Test
    fun `read-context owners are rendered as identity plus presence, or as not projected`() {
        val notProjected = lines(
            AnkiBackendDiagnosticsInput(states = listOf(state(ankiDroid)), preference = BackendPreference.Auto)
        ).map { it.toPair() }
        assertTrue(
            notProjected.any { (label, value) ->
                label == "Read-context owners" && value.startsWith("NOT_PROJECTED")
            }
        )

        val projected = lines(
            AnkiBackendDiagnosticsInput(
                states = listOf(state(ankiDroid)),
                preference = BackendPreference.Auto,
                readContextOwners = listOf(
                    AnkiReadContextOwnerUi(
                        purposeToken = "card_browser",
                        backendId = ankiDroid,
                        collectionKeyPresent = true,
                        liveInThisProcess = true
                    )
                )
            )
        ).map { it.toPair() }
        assertTrue(
            projected.contains(
                "Read context card_browser" to "ankidroid_local collection=present live=true"
            )
        )
    }

    // ------------------------------------------------------------------ CHECKPOINT 21M-17

    @Test
    fun `unresolved transactions keep their own owner and their own status vocabulary`() {
        val facts = listOf(
            AnkiUnresolvedTransactionFact(AnkiTransactionFamily.NOTE_MUTATION, ankiDroid, "CONFLICT"),
            AnkiUnresolvedTransactionFact(AnkiTransactionFamily.NOTE_MUTATION, ankiDroid, "AMBIGUOUS"),
            AnkiUnresolvedTransactionFact(AnkiTransactionFamily.NOTE_MUTATION, pcHome, "CONFLICT"),
            AnkiUnresolvedTransactionFact(AnkiTransactionFamily.REVIEW_COMMIT, pcHome, "SUBMITTING")
        )
        val groups = pendingTransactionGroups(
            AnkiUnresolvedTransactionRead(facts = facts, loaded = true)
        )

        val ankiDroidMutations = groups.single {
            it.family == AnkiTransactionFamilyUi.NOTE_MUTATION && it.backendId == ankiDroid
        }
        assertEquals(
            "CONFLICT and AMBIGUOUS stay separate tokens",
            listOf("AMBIGUOUS" to 1, "CONFLICT" to 1),
            ankiDroidMutations.statusCounts
        )
        assertEquals(2, ankiDroidMutations.total)

        val pcMutations = groups.single {
            it.family == AnkiTransactionFamilyUi.NOTE_MUTATION && it.backendId == pcHome
        }
        assertEquals(listOf("CONFLICT" to 1), pcMutations.statusCounts)

        val rendered = lines(
            AnkiBackendDiagnosticsInput(
                states = listOf(state(ankiDroid), state(pcHome)),
                preference = BackendPreference.Auto,
                pendingTransactions = groups,
                pendingTransactionsLoaded = true
            )
        ).map { it.toPair() }

        assertTrue(rendered.contains("Unresolved NoteMutation @ ankidroid_local" to "AMBIGUOUS=1, CONFLICT=1"))
        assertTrue(rendered.contains("Unresolved NoteMutation @ pc_agent:home" to "CONFLICT=1"))
        assertTrue(rendered.contains("Unresolved ReviewCommit @ pc_agent:home" to "SUBMITTING=1"))
        // A family that was read and holds nothing is a truthful zero, with no owner invented for it.
        assertTrue(rendered.contains("Unresolved ReviewerAction" to "0"))
        assertTrue(rendered.contains("Unresolved NoteCreation" to "0"))
        assertFalse(rendered.any { it.first.contains("@ unknown_owner") })
        assertNothingLeaks(rendered)
    }

    @Test
    fun `a family that could not be read is never rendered as zero`() {
        val groups = pendingTransactionGroups(
            AnkiUnresolvedTransactionRead(
                facts = emptyList(),
                loaded = true,
                unavailableFamilies = setOf(AnkiTransactionFamily.NOTE_MUTATION),
                notProjectedFamilies = setOf(AnkiTransactionFamily.NOTE_CREATION)
            )
        )
        val rendered = lines(
            AnkiBackendDiagnosticsInput(
                states = listOf(state(ankiDroid)),
                preference = BackendPreference.Auto,
                pendingTransactions = groups,
                pendingTransactionsLoaded = true
            )
        ).map { it.toPair() }

        assertTrue(rendered.contains("Unresolved NoteMutation" to "NOT_PROJECTED (read_unavailable)"))
        assertTrue(
            rendered.contains(
                "Unresolved NoteCreation" to "NOT_PROJECTED (no_read_only_projection)"
            )
        )
        // The families that were read still report their real zero.
        assertTrue(rendered.contains("Unresolved ReviewCommit" to "0"))
        assertTrue(rendered.contains("Unresolved ReviewerAction" to "0"))
    }

    @Test
    fun `nothing read at all is reported as not projected, not as an empty ledger`() {
        val groups = pendingTransactionGroups(AnkiUnresolvedTransactionRead.NOT_READ)
        assertTrue("no group may claim a count that was never read", groups.isEmpty())

        val rendered = lines(
            AnkiBackendDiagnosticsInput(
                states = listOf(state(ankiDroid)),
                preference = BackendPreference.Auto,
                pendingTransactions = groups,
                pendingTransactionsLoaded = false
            )
        ).map { it.toPair() }

        listOf("ReviewCommit", "ReviewerAction", "NoteMutation", "NoteCreation").forEach { family ->
            val line = rendered.single { it.first == "Unresolved $family" }
            assertTrue(line.second.startsWith("NOT_PROJECTED"))
            assertFalse("never a zero", line.second.startsWith("0"))
        }
    }

    @Test
    fun `the filtered-session family states its omission instead of being filled in`() {
        val rendered = lines(
            AnkiBackendDiagnosticsInput(states = listOf(state(ankiDroid)), preference = BackendPreference.Auto)
        ).map { it.toPair() }

        val line = rendered.single { it.first == "Unresolved FilteredSession" }
        assertTrue(line.second.startsWith("NOT_APPLICABLE"))
        assertTrue("the reason is stated", line.second.contains("GATE 21L BLOCKED"))
        assertEquals(
            "the presentation vocabulary names the family the durable vocabulary does not have",
            null,
            AnkiTransactionFamilyUi.FILTERED_SESSION.family
        )
        assertEquals(4, AnkiTransactionFamilyUi.entries.count { it.family != null })
    }

    @Test
    fun `grouping by owner is a pure counting helper over any record shape`() {
        data class FakeRecord(val owner: AnkiBackendId?, val status: String)

        val groups = groupPendingTransactions(
            family = AnkiTransactionFamilyUi.REVIEW_COMMIT,
            records = listOf(
                FakeRecord(ankiDroid, "PREPARED"),
                FakeRecord(ankiDroid, "PREPARED"),
                FakeRecord(pcHome, "AMBIGUOUS"),
                FakeRecord(null, "RETRY_ALLOWED")
            ),
            backendOf = { it.owner },
            statusTokenOf = { it.status }
        )

        assertEquals(3, groups.size)
        assertEquals(
            listOf("PREPARED" to 2),
            groups.single { it.backendId == ankiDroid }.statusCounts
        )
        // An ownerless record is reported as ownerless rather than attributed to a ready backend.
        assertEquals(
            listOf("RETRY_ALLOWED" to 1),
            groups.single { it.backendId == null }.statusCounts
        )
        assertEquals(
            "identity order is stable",
            listOf("", "ankidroid_local", "pc_agent:home"),
            groups.map { it.backendId?.stableId ?: "" }.sorted()
        )
    }

    @Test
    fun `a not-projected group carries its reason token`() {
        val group = notProjectedTransactionGroup(
            AnkiTransactionFamilyUi.NOTE_MUTATION,
            noteToken = "read_unavailable"
        )

        assertTrue(group.notProjected)
        assertEquals("read_unavailable", group.noteToken)
        assertEquals(0, group.total)
        assertTrue(group.statusCounts.isEmpty())
    }

    @Test
    fun `an empty inventory is reported rather than rendered as a backend list`() {
        val rendered = lines(
            AnkiBackendDiagnosticsInput(states = emptyList(), preference = BackendPreference.Auto)
        ).map { it.toPair() }

        assertTrue(rendered.contains("Backend inventory" to "no configured backend instance"))
        assertTrue(rendered.contains("Configured backends" to "none"))
        assertFalse(rendered.any { it.first == "Backend" })
    }

    @Test
    fun `the report revision token is rendered when the surface supplies one`() {
        val rendered = lines(
            AnkiBackendDiagnosticsInput(
                states = listOf(state(ankiDroid)),
                preference = BackendPreference.Auto,
                reportRevisionToken = "gate-21m-r2"
            )
        ).map { it.toPair() }

        assertTrue(rendered.contains("Report revision" to "gate-21m-r2"))
        assertTrue(
            "the requirement set the section is rendered against is not a secret",
            STUDY_SESSION_REQUIREMENTS.isNotEmpty()
        )
    }
}

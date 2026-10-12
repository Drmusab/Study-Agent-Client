package com.studyagent.client.core.anki

/**
 * GATE 21D — why the registry will not hold an identity at all (BACKEND_IDENTITY_CONTRACT §1.3).
 *
 * Allocation rules A1–A6 bind the *allocation authority* (whoever turns a configuration record into
 * an [AnkiBackendId]); no such authority exists yet, because no PC configuration exists. What the
 * registry can enforce today is the machine-checkable subset, at the one place every instance must
 * pass through — registration. The rest (A1 "configured instance, not a label", A2 "unique across
 * every configuration the app can hold", A6 "a label change is not an identity change") remain
 * obligations of that future authority and are recorded as residual risk R3 rather than faked here.
 *
 * Tokens are fixed and content-free, so a refusal can be logged without leaking the identity it
 * refused.
 */
enum class AnkiBackendIdentityRefusal(val token: String) {
    /**
     * A3 — the identity does not survive its own `stableId` round trip, so a durable record could
     * never name it again (or would name a *different* configured instance).
     */
    NOT_PERSISTABLE("not_persistable"),

    /**
     * A5 — the discriminator carries surrounding whitespace, which would create a second, look-alike
     * instance that is indistinguishable in a list. Normalization happens once, at allocation; it is
     * never re-applied on read because that would change the owner of every record already written.
     */
    NOT_NORMALIZED("not_normalized"),

    /**
     * A4 — the discriminator *is* a connection fact (a URL or an IP literal). Endpoints change with
     * DHCP and roaming, would silently re-key durable records, and may embed credentials that
     * diagnostics and routes would then print.
     */
    CONNECTION_FACT_AS_DISCRIMINATOR("connection_fact_as_discriminator")
}

private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")

/**
 * The refusal that applies to this identity, or `null` when the registry may hold it.
 *
 * This deliberately does **not** try to recognize every endpoint-shaped string: `host:port` is
 * indistinguishable from a legitimate composed `<agent>:<profile>` discriminator (the 21B-sanctioned
 * option), and refusing it would reject valid identities. Only the unambiguous cases are refused;
 * the remainder is the allocation authority's duty (A4).
 */
fun AnkiBackendId.registrationRefusal(): AnkiBackendIdentityRefusal? {
    if (!roundTripsStableId) return AnkiBackendIdentityRefusal.NOT_PERSISTABLE
    val discriminator = stableId.substringAfter(':', missingDelimiterValue = "")
    // `discriminatorIsNormalized` (GATE 21C) trims the whole token, so it cannot see *leading*
    // whitespace inside a prefixed discriminator ("pc_agent: a"); check the discriminator itself.
    if (!discriminatorIsNormalized || discriminator != discriminator.trim()) {
        return AnkiBackendIdentityRefusal.NOT_NORMALIZED
    }
    if (discriminator.contains("://") || IPV4_LITERAL.matches(discriminator)) {
        return AnkiBackendIdentityRefusal.CONNECTION_FACT_AS_DISCRIMINATOR
    }
    return null
}

/**
 * GATE 21D — the outcome of asking the registry to hold one more configured instance.
 *
 * Typed, because a duplicate is an expected *configuration* outcome, not a crash: and every refusal
 * leaves the registry exactly as it was. In particular an incompatible second registration under an
 * identity that is already held **never replaces** the first (DECISION-21B-01: "never renumbered,
 * never first/last wins, never silently merged").
 */
sealed interface AnkiBackendRegistration {
    val backendId: AnkiBackendId

    /** The instance joined the registry. Registration order is never a routing input. */
    data class Registered(override val backendId: AnkiBackendId) : AnkiBackendRegistration

    /** The very same instance was already held. Idempotent: nothing changed, nothing was published. */
    data class AlreadyRegistered(override val backendId: AnkiBackendId) : AnkiBackendRegistration

    /** A *different* runtime object claims an identity that is already held. Refused; no replacement. */
    data class DuplicateIdentity(override val backendId: AnkiBackendId) : AnkiBackendRegistration

    /** The identity itself is not safe to hold (see [AnkiBackendIdentityRefusal]). */
    data class IdentityRefused(
        override val backendId: AnkiBackendId,
        val refusal: AnkiBackendIdentityRefusal
    ) : AnkiBackendRegistration
}

/**
 * GATE 21D — the outcome of dropping a configured instance from the registry.
 *
 * Removal changes *membership only*. It cannot touch a durable record: the registry holds no ledger,
 * so a record owned by the removed identity keeps its owner and becomes
 * `Blocked(BACKEND_NOT_REGISTERED)` at recovery time — blocked, never reassigned (DECISION-21B-17).
 */
sealed interface AnkiBackendRemoval {
    val backendId: AnkiBackendId

    data class Removed(override val backendId: AnkiBackendId) : AnkiBackendRemoval

    /** Nothing with that identity was held. Never "the closest match". */
    data class NotRegistered(override val backendId: AnkiBackendId) : AnkiBackendRemoval
}

/**
 * GATE 21D — the outcome of asking the registry to re-run the read-only availability check of one
 * exact instance.
 *
 * Deliberately carries **no state**. What a probe found is whatever the backend then publishes, and
 * that publication lags the probe (the backend's flows are derived and ordered, and it may discard
 * this probe in favour of a newer request) — so a snapshot returned here could be mistaken for a
 * verification receipt of *this* probe, which nothing can honestly promise. Observe
 * [AnkiBackendStatusCenter.states] or read [AnkiBackendRegistry.stateOf] instead.
 */
sealed interface AnkiBackendRefresh {
    val backendId: AnkiBackendId

    /** The check was requested of exactly that instance, and of no other. */
    data class Requested(override val backendId: AnkiBackendId) : AnkiBackendRefresh

    /** No such instance. No probe was sent anywhere — certainly not to a sibling. */
    data class NotRegistered(override val backendId: AnkiBackendId) : AnkiBackendRefresh
}

/**
 * GATE 21D — the set of configured instances at one instant. Immutable once built: a change of
 * membership *replaces* the whole snapshot, so no reader can observe a half-applied registration
 * and no caller can mutate a collection it was handed.
 *
 * Enumeration keeps registration order so output is deterministic for a given history, but that
 * order is **display and diff order only** — it is never a priority (AUTO's tie-break is
 * [AnkiBackendDescriptor.orderingKey], in a different component).
 *
 * Not a "current backend": there is no active, selected or default member, and none can be added
 * here without breaking INV-21D-01/17.
 */
internal class AnkiBackendMembership private constructor(
    private val byId: Map<AnkiBackendId, AnkiBackend>
) {
    val backends: List<AnkiBackend> get() = byId.values.toList()

    val ids: Set<AnkiBackendId> get() = byId.keys.toSet()

    val size: Int get() = byId.size

    operator fun get(id: AnkiBackendId): AnkiBackend? = byId[id]

    /** A snapshot that additionally holds [backend]. The caller has already checked the identity is free. */
    fun plus(backend: AnkiBackend): AnkiBackendMembership =
        AnkiBackendMembership(LinkedHashMap(byId).also { it[backend.id] = backend })

    /** A snapshot without [id]. */
    fun minus(id: AnkiBackendId): AnkiBackendMembership =
        AnkiBackendMembership(LinkedHashMap(byId).also { it.remove(id) })

    companion object {
        val EMPTY = AnkiBackendMembership(emptyMap())

        /**
         * The startup path: a configuration that cannot be held is a *configuration error*, and
         * failing closed at construction is the intended behaviour (BACKEND_IDENTITY_CONTRACT §1.5).
         */
        fun of(backends: List<AnkiBackend>): AnkiBackendMembership {
            val byId = LinkedHashMap<AnkiBackendId, AnkiBackend>()
            for (backend in backends) {
                val refusal = backend.id.registrationRefusal()
                require(refusal == null) { "Backend identity refused: ${refusal?.token}" }
                require(byId.put(backend.id, backend) == null) { "Duplicate logical backend identity" }
            }
            return AnkiBackendMembership(byId)
        }
    }
}

package com.studyagent.client.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GATE 21D PART II — repository source audit (AUDIT-21D-01 … AUDIT-21D-16).
 *
 * The structural rules a behavioural test cannot see from outside: *where* the registry lives, *what*
 * it may call, and *which* vocabulary it may speak. Rules are checked on source text with comments
 * removed, so a document may explain a forbidden spelling while live code may not use it (the GATE
 * 12–21C convention). Behavioural evidence for the same audits lives in the other `Gate21d*` classes;
 * each check here names the audit it enforces.
 *
 * The locked contract is `docs/GATE_21B_CONTRACT_LOCK.md` (DECISION-21B-01/09/10/28) and
 * `docs/BACKEND_OWNERSHIP_INVARIANTS.md` §1.1/§8.
 */
class Gate21dArchitectureAuditTest {

    private val appRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .mapNotNull { root ->
                listOf(root, File(root, "app")).firstOrNull {
                    File(it, "src/main/java/com/studyagent/client/core/anki").isDirectory
                }
            }.first()
    }

    private val mainRoot: File get() = File(appRoot, "src/main/java/com/studyagent/client")
    private val testRoot: File get() = File(appRoot, "src/test/java/com/studyagent/client")

    /** Block comments and whole-line `//` comments removed (a `://` inside a string is untouched). */
    private fun File.code(): String = readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("(?m)^\\s*//.*$"), " ")

    private fun main(relative: String): File {
        val file = File(mainRoot, relative)
        check(file.isFile) { "Missing GATE 21D source $relative" }
        return file
    }

    private fun mainSources(): List<File> =
        mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun assertNoTokens(text: String, tokens: List<String>, scope: String) {
        tokens.forEach { token -> assertFalse("$scope must not contain `$token`", text.contains(token)) }
    }

    private fun assertNoPatterns(text: String, patterns: List<String>, scope: String) {
        patterns.forEach { pattern ->
            assertFalse("$scope must not match /$pattern/", Regex(pattern).containsMatchIn(text))
        }
    }

    /** The body of `class AnkiBackendRegistry`, from its header to its closing brace at column 0. */
    private fun registryBody(): String =
        main("core/anki/AnkiBackendSelector.kt").code()
            .substringAfter("class AnkiBackendRegistry(")
            .substringBefore("\n}\n")

    private fun statusCenterBody(): String =
        main("core/anki/AnkiBackendStatus.kt").code()
            .substringAfter("class AnkiBackendStatusCenter(")
            .substringBefore("\n}\n")

    /** Files the gate adds or owns in `core`. */
    private val coreFiles = listOf(
        "core/anki/AnkiBackendState.kt",
        "core/anki/AnkiBackendRegistration.kt",
        "core/anki/AnkiBackendUnavailableReason.kt"
    )

    private fun coreCode(): String = coreFiles.joinToString("\n") { main(it).code() }

    // ----------------------------------------------------------------------------- AUDIT-21D-01

    @Test
    fun `AUDIT-21D-01 the registry contract is the locked one - extended in place, with the locked ownership`() {
        val selector = main("core/anki/AnkiBackendSelector.kt").code()
        assertTrue(selector.contains("class AnkiBackendRegistry(backends: List<AnkiBackend>)"))
        val registry = registryBody()
        // The existing surface survives unchanged (DECISION-21B-10: extend, never replace)…
        listOf(
            "val registered: List<AnkiBackend>",
            "val ids: Set<AnkiBackendId>",
            "fun find(id: AnkiBackendId): AnkiBackend?",
            "fun descriptors(): List<AnkiBackendDescriptor>"
        ).forEach { assertTrue("registry must keep `$it`", registry.contains(it)) }
        // …and the 21D additions are exactly the contract's: identity, lookup, state, lifecycle.
        listOf(
            "fun states(): List<AnkiBackendState>",
            "fun stateOf(id: AnkiBackendId): AnkiBackendStateLookup",
            "fun register(backend: AnkiBackend): AnkiBackendRegistration",
            "fun remove(id: AnkiBackendId): AnkiBackendRemoval",
            "suspend fun refresh(id: AnkiBackendId): AnkiBackendRefresh"
        ).forEach { assertTrue("registry must offer `$it`", registry.contains(it)) }
    }

    // ----------------------------------------------------------------------------- AUDIT-21D-02 / 03

    @Test
    fun `AUDIT-21D-02 registration and lookup key on the exact configured identity`() {
        val membership = main("core/anki/AnkiBackendRegistration.kt").code()
            .substringAfter("class AnkiBackendMembership")
        assertTrue(membership.contains("Map<AnkiBackendId, AnkiBackend>"))
        assertTrue(registryBody().contains("membership.value[id]"))
        // Identity is validated with the 21C predicates, not re-invented.
        val gate = main("core/anki/AnkiBackendRegistration.kt").code()
        assertTrue(gate.contains("roundTripsStableId"))
        assertTrue(gate.contains("discriminatorIsNormalized"))
    }

    @Test
    fun `AUDIT-21D-03 a kind is never used as an instance key or lookup`() {
        val scope = registryBody() + "\n" + coreCode()
        assertNoPatterns(
            scope,
            listOf(
                "associateBy\\s*\\{\\s*it\\.kind",
                "groupBy\\s*\\{\\s*it\\.kind",
                "find\\s*\\{\\s*it\\.kind",
                "first\\s*\\{\\s*it\\.kind",
                "==\\s*AnkiBackendKind\\.",
                "Map<AnkiBackendKind"
            ),
            "the registry and state model"
        )
        // The registry body does not even mention kind or locality: identity is all it keys on.
        assertNoTokens(registryBody(), listOf("AnkiBackendKind", "AnkiBackendLocality", "displayLabel"), "the registry")
    }

    // ----------------------------------------------------------------------------- AUDIT-21D-04

    @Test
    fun `AUDIT-21D-04 there is one registry, built in one place from the one backend that exists`() {
        // Exactly one registry-shaped declaration exists in the Anki packages: no interface twin, no
        // Default implementation, no second class (DECISION-21B-10: a second registry is prohibited).
        val registryTypes = mainSources().filter { it.path.contains("/anki/") }.flatMap { file ->
            Regex("(class|interface|object)\\s+(\\w*Registry)\\b").findAll(file.code())
                .map { it.groupValues[2] to file.name }.toList()
        }
        assertEquals(listOf("AnkiBackendRegistry" to "AnkiBackendSelector.kt"), registryTypes)
        // The composition root builds exactly one registry, from exactly the configured AnkiDroid backend.
        val container = main("di/AppContainer.kt").code()
        assertEquals(1, Regex("AnkiBackendRegistry\\(").findAll(container).count())
        assertTrue(container.contains("AnkiBackendRegistry(listOf(ankiDroidBackend))"))
        assertEquals("one AnkiDroid backend, constructed once", 1, Regex("AnkiDroidBackend\\(").findAll(container).count())
        // Outside the container the only constructions are the pre-existing, deliberately empty ones.
        val others = mainSources().filter { it.name != "AppContainer.kt" && it.name != "AnkiBackendSelector.kt" }
            .filter { it.code().contains("AnkiBackendRegistry(") }
        others.forEach { file ->
            assertTrue("${file.name} may only build an empty registry", file.code().contains("AnkiBackendRegistry(emptyList())"))
        }
    }

    // ----------------------------------------------------------------------------- AUDIT-21D-05 / 16

    @Test
    fun `AUDIT-21D-05 no selection policy, preference, priority or fallback leaks into the registry or its state`() {
        val pc = main("data/anki/remote/PcAnkiAvailabilitySource.kt").code()
        val scope = registryBody() + "\n" + coreCode() + "\n" + pc
        assertNoTokens(
            scope,
            listOf(
                "AnkiBackendMode",
                "AnkiBackendPreferenceStore",
                "BackendResolutionRequest",
                "AnkiBackendResolver",
                "AnkiBackendSelector",
                "resolveFor",
                "alternateBackend",
                "fallbackBackend",
                "switchBackend",
                "tryOtherBackend",
                "AUTO_",
                "isPreferred",
                "priority"
            ),
            "registry / state / PC readiness"
        )
        // A removal or a loss never resolves to another instance: lookups are `[]` on the exact id.
        assertNoPatterns(registryBody(), listOf("firstOrNull\\s*\\{", "\\.first\\(\\)", "minBy", "maxBy", "random"), "the registry")
    }

    @Test
    fun `AUDIT-21D-16 no resolver, preference, failover or synchronization component was added`() {
        assertNoTokens(
            mainSources().joinToString("\n") { it.code() },
            listOf("class BackendResolver", "class AnkiBackendAutoResolver", "fun resolveAuto", "AUTO_RESOLUTION"),
            "main sources"
        )
        // The resolver, the selector adapter and the preference store know nothing of 21D's types.
        listOf("core/anki/AnkiBackendResolver.kt", "core/anki/AnkiBackendPreference.kt").forEach { relative ->
            assertNoTokens(
                main(relative).code(),
                listOf("AnkiBackendState", "AnkiBackendRegistration", "AnkiBackendRemoval", "AnkiBackendRefresh"),
                relative
            )
        }
        val selector = main("core/anki/AnkiBackendSelector.kt").code().substringAfter("class AnkiBackendSelector")
        assertNoTokens(selector, listOf("AnkiBackendState", "register(", "remove("), "the selector adapter")
        // No new file in `core/anki` or `data/anki` is named like a resolver, router or synchronizer.
        val added = listOf("core/anki", "data/anki").flatMap { dir ->
            File(mainRoot, dir).walkTopDown().filter { it.isFile }.map { it.name }.toList()
        }
        assertFalse(added.any { Regex("(?i)(Router|Failover|Synchroni[sz]er|Merger)").containsMatchIn(it) })
    }

    // ----------------------------------------------------------------------------- AUDIT-21D-06

    @Test
    fun `AUDIT-21D-06 availability keeps transport, authentication, Anki and collection apart`() {
        val availability = main("core/anki/AnkiAvailability.kt").code()
        listOf(
            "data object NotInstalled",
            "data class ProviderUnavailable",
            "data class PermissionRequired",
            "data object CollectionNotInitialized",
            "data object AgentDisconnected",
            "data object AgentAuthenticationRequired",
            "data object AgentAnkiUnavailable",
            "data class Unsupported"
        ).forEach { assertTrue("availability must keep `$it`", availability.contains(it)) }
        // The authentication state carries no payload at all.
        assertTrue(availability.contains("data object AgentAuthenticationRequired : AnkiAvailability"))
        // The taxonomy and the PC ladder are total: no `else`, so a new state forces a decision.
        val reasons = main("core/anki/AnkiBackendUnavailableReason.kt").code().substringAfter("fun AnkiAvailability.unavailableReason()")
        assertFalse("the reason mapping must be exhaustive", reasons.contains("else ->"))
        val ladder = main("data/anki/remote/PcAnkiAvailabilitySource.kt").code()
            .substringAfter("object PcAnkiAvailabilityMapper")
            .substringBefore("private fun fromAnkiReport")
        assertFalse("the PC ladder must be exhaustive over ConnectionState", ladder.contains("else ->"))
    }

    @Test
    fun `AUDIT-21D-06 the PC ladder reads only the kind of connection state, never its content`() {
        val ladder = main("data/anki/remote/PcAnkiAvailabilitySource.kt").code()
            .substringAfter("object PcAnkiAvailabilityMapper")
            .substringBefore("private fun fromAnkiReport")
        assertNoPatterns(
            ladder,
            listOf("\\.host\\b", "\\.port\\b", "\\.reason\\b", "\\.message\\b", "\\.cause\\b", "\\.serverName\\b", "\\.capabilities\\b"),
            "the PC mapper"
        )
        // `authenticated` is the one field it may read — the authentication evidence itself.
        assertTrue(ladder.contains("connection.authenticated"))
    }

    // ----------------------------------------------------------------------------- AUDIT-21D-07 / 08

    @Test
    fun `AUDIT-21D-07 capability claims are about the wired graph and the vocabulary is the locked one`() {
        // No parallel capability enum: the one vocabulary is AnkiRequiredCapability.
        assertFalse(mainSources().any { it.code().contains("enum class AnkiBackendCapability") })
        assertFalse(mainSources().any { it.code().contains("enum class AnkiCapability ") })
        // The interface default claims nothing; only an implementation may claim.
        assertTrue(main("core/anki/AnkiBackend.kt").code().contains("val implementedCapabilities: AnkiCapabilities? get() = null"))
        // AnkiDroid derives its claim from the policy and the same wiring gates — never a hand-written set.
        val backend = main("data/anki/ankidroid/AnkiDroidBackend.kt").code()
        val claim = backend.substringAfter("override val implementedCapabilities").substringBefore("private fun withWriteSupport")
        assertTrue(claim.contains("withWriteSupport("))
        assertTrue(claim.contains("implementedCapabilitiesFor("))
        assertNoPatterns(claim, listOf("AnkiCapabilities\\("), "the AnkiDroid claim (no hand-written capability set)")
        // The coherent Ready snapshot uses the same gates as the live projection.
        assertTrue(backend.contains("AnkiAvailability.Ready(withWriteSupport(state.capabilities))"))
    }

    @Test
    fun `AUDIT-21D-08 implemented support, operational readiness and authorization are not conflated`() {
        val state = main("core/anki/AnkiBackendState.kt").code()
        assertTrue(state.contains("enum class AnkiImplementedSupport"))
        assertTrue(state.contains("enum class AnkiCapabilityReadiness"))
        assertTrue(state.contains("val implemented: AnkiImplementedSupport"))
        assertTrue(state.contains("val operational: AnkiCapabilityReadiness"))
        // READY requires SUPPORTED, enforced where the pair is constructed.
        assertTrue(state.contains("operational != AnkiCapabilityReadiness.READY || implemented == AnkiImplementedSupport.SUPPORTED"))
        // Authorization is the owning binding's decision, never a registry field.
        assertNoPatterns(state, listOf("(?i)authori[sz]", "(?i)permitted\\b", "(?i)allowed\\b"), "the state model")
        // The operational answer is the descriptor's own fail-closed rule, not a second one.
        assertTrue(state.contains("descriptor.supports(required)"))
        // The data-layer API-support tri-state answers a different question ("does the provider API at
        // this spec offer it?") and must not be borrowed as "does the application implement it?".
        assertFalse(state.contains("CapabilitySupport"))
    }

    // ----------------------------------------------------------------------------- AUDIT-21D-09 / 10

    @Test
    fun `AUDIT-21D-09 membership changes are atomic swaps and observation cannot leak a collector`() {
        val registry = registryBody()
        assertTrue(registry.contains("compareAndSet(current, current.plus(backend))"))
        assertTrue(registry.contains("compareAndSet(current, current.minus(id))"))
        assertNoTokens(registry, listOf("synchronized", "mutableListOf", "mutableMapOf", "HashMap", "ArrayList"), "the registry")
        // The observation is rebuilt per membership and its per-backend parts are cold.
        val center = statusCenterBody()
        assertTrue(center.contains("flatMapLatest"))
        val observe = center.substringAfter("private fun observe(").substringBefore("private fun AnkiBackendState.toEntry")
        assertFalse("a per-backend eager stateIn would leak one collector per removed backend", observe.contains("stateIn"))
    }

    @Test
    fun `AUDIT-21D-10 published state is immutable and never hands out a backend`() {
        val code = coreCode()
        assertFalse("immutable snapshots have no mutable properties", Regex("\\bvar\\s").containsMatchIn(code))
        val pc = main("data/anki/remote/PcAnkiAvailabilitySource.kt").code()
        assertFalse(Regex("\\bvar\\s").containsMatchIn(pc))
        // The state holds a descriptor and a claim — never the runtime object.
        val header = main("core/anki/AnkiBackendState.kt").code()
            .substringAfter("data class AnkiBackendState(")
            .substringBefore(") {")
        assertFalse("a state must not carry an AnkiBackend", Regex("\\bAnkiBackend\\b").containsMatchIn(header))
        // The raw-instance membership is an internal infrastructure type, not part of the read surface.
        assertTrue(main("core/anki/AnkiBackendRegistration.kt").code().contains("internal class AnkiBackendMembership"))
        assertTrue(registryBody().contains("internal val members: StateFlow<AnkiBackendMembership>"))
    }

    // ----------------------------------------------------------------------------- AUDIT-21D-11

    @Test
    fun `AUDIT-21D-11 the registry state has no vocabulary in which continuity could be asserted`() {
        assertNoTokens(
            coreCode(),
            listOf(
                "AnkiCollectionIdentity",
                "AnkiIdentityAssurance",
                "AnkiIdentityContinuation",
                "AnkiBackendContext",
                "collectionKey",
                "continuationVerdict"
            ),
            "the registry state model"
        )
        assertNoTokens(
            main("data/anki/remote/PcAnkiAvailabilitySource.kt").code(),
            listOf("AnkiCollectionIdentity", "AnkiIdentityAssurance", "collectionKey"),
            "the PC readiness structure"
        )
    }

    // ----------------------------------------------------------------------------- AUDIT-21D-12

    @Test
    fun `AUDIT-21D-12 the new code carries no credential and opens no transport`() {
        val scope = coreCode() + "\n" + main("data/anki/remote/PcAnkiAvailabilitySource.kt").code() + "\n" + registryBody()
        assertNoPatterns(
            scope,
            listOf("(?i)\\bpassword\\b", "(?i)\\bpassphrase\\b", "(?i)\\bsecret\\b", "(?i)apiKey", "(?i)authToken", "(?i)bearer", "(?i)credential"),
            "the registry, state and PC readiness"
        )
        val imports = File(mainRoot, "data/anki/remote").walkTopDown().filter { it.isFile }
            .flatMap { it.readLines().filter { line -> line.startsWith("import ") } }.toList()
        assertTrue(imports.isNotEmpty())
        listOf("okhttp3", "java.net", "com.studyagent.client.core.network", "android.").forEach { forbidden ->
            assertFalse("the PC readiness structure must not import $forbidden", imports.any { it.contains(forbidden) })
        }
        // Authentication adds no mechanism: no new class has "Authenticator"/"Credential" in its name.
        assertFalse(mainSources().any { Regex("class\\s+\\w*(Authenticator|Credential)\\w*").containsMatchIn(it.code()) && it.path.contains("anki") })
    }

    // ----------------------------------------------------------------------------- AUDIT-21D-13

    @Test
    fun `AUDIT-21D-13 nothing the registry or its observer can reach is a mutation`() {
        val mutations = listOf(
            "commitRating", "performReviewerAction", "applyNoteMutation", "createAnkiNote", "storeAnkiMedia",
            "beginReview", "nextCard", "endReview", "prepareCommit", "hydrateCardContent", "getDecks"
        )
        assertNoTokens(registryBody(), mutations, "the registry")
        assertNoTokens(statusCenterBody(), mutations, "the status center")
        assertNoTokens(coreCode(), mutations, "the state model")
        assertNoTokens(main("data/anki/remote/PcAnkiAvailabilitySource.kt").code(), mutations, "the PC readiness structure")
        // The one call the registry sends a backend is the read-only probe.
        val calledOnBackend = Regex("\\bbackend\\.(\\w+)\\(").findAll(registryBody()).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("refreshAvailability"), calledOnBackend)
        // Nothing that is not a flow read is called on a backend by the observer either.
        val observed = Regex("\\bbackend\\.(\\w+)").findAll(statusCenterBody()).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("availability", "capabilities", "id", "implementedCapabilities"), observed)
    }

    // ----------------------------------------------------------------------------- AUDIT-21D-14

    @Test
    fun `AUDIT-21D-14 no service is constructed to register, observe or describe a backend`() {
        val scope = registryBody() + "\n" + statusCenterBody() + "\n" + coreCode() + "\n" +
            main("data/anki/remote/PcAnkiAvailabilitySource.kt").code()
        assertNoPatterns(
            scope,
            listOf(
                "AnkiDroidBackend\\(", "DefaultAnkiDroid\\w*\\(", "WebSocketAgentConnection\\(", "PcAnkiBackend\\(",
                "OkHttpClient\\(", "ReconnectController\\(", "AgentConnection\\("
            ),
            "the registry, observer and PC readiness structure"
        )
        // The PC readiness structure observes a flow it is handed; it has no way to open a connection.
        val source = main("data/anki/remote/PcAnkiAvailabilitySource.kt").code()
        assertTrue(source.contains("connection: StateFlow<ConnectionState>"))
    }

    @Test
    fun `AUDIT-21D-14 nothing in production produces a PC state`() {
        // 21B: "it must not fabricate a PC Ready state". No producer exists — the structure is unused.
        val offenders = mainSources()
            .filterNot { it.path.contains("data/anki/remote") }
            .filter { file ->
                val code = file.code()
                code.contains("PcAnkiAvailabilitySource") || code.contains("PcAnkiAvailabilityMapper") ||
                    code.contains("PcAnkiReadinessReport") || code.contains("PcAnkiBackend(")
            }.map { it.name }
        assertEquals("no production code may construct or call the PC readiness structure", emptyList<String>(), offenders)
        val container = main("di/AppContainer.kt").code()
        assertFalse(container.contains("PcAnkiBackend("))
        assertFalse(container.contains("AnkiBackendId.PcAgent("))
        assertFalse(
            "nothing in production allocates a PC identity",
            mainSources().filterNot { it.name == "AnkiBackendId.kt" }.any { it.code().contains("PcAgent(") }
        )
    }

    // ----------------------------------------------------------------------------- AUDIT-21D-15

    @Test
    fun `AUDIT-21D-15 the study, transaction and scheduler code does not know the new types`() {
        val families = listOf("core/study") + listOf("core/anki/edit", "core/anki/create")
        val newTypes = listOf(
            "AnkiBackendState", "AnkiBackendRegistration", "AnkiBackendRemoval", "AnkiBackendRefresh",
            "AnkiImplementedSupport", "AnkiCapabilityReadiness", "AnkiBackendUnavailableReason",
            "implementedCapabilities", "AgentAuthenticationRequired"
        )
        families.forEach { dir ->
            File(mainRoot, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                assertNoTokens(file.code(), newTypes, "${dir}/${file.name}")
                // A transaction or session never changes or probes membership (`.remove(` alone would
                // also match unrelated collection calls, so the registry receiver is named).
                assertNoTokens(
                    file.code(),
                    listOf("registry.register(", "registry.remove(", "registry.refresh("),
                    "${dir}/${file.name}"
                )
            }
        }
        listOf(
            "ReviewCommit.kt", "ReviewCommitCoordinator.kt", "ReviewCommitLedger.kt", "ReviewCommitReconciliation.kt",
            "ReviewerActionCoordinator.kt", "ReviewerActionLedger.kt", "ReviewerActionRecovery.kt",
            "AnkiTransactionRecoveryRouting.kt", "AnkiSessionContext.kt"
        ).forEach { name ->
            assertNoTokens(main("core/anki/$name").code(), newTypes, name)
        }
    }

    // ----------------------------------------------------------------------------- VER-21D-22 support

    @Test
    fun `a test double lives in test sources only and is never wired into production`() {
        assertTrue(File(testRoot, "anki/fake/FakeAnkiBackend.kt").isFile)
        assertFalse(mainSources().any { it.name == "FakeAnkiBackend.kt" || it.name == "ProbeOnlyBackend.kt" })
        val all = mainSources().joinToString("\n") { it.code() }
        assertFalse(all.contains("FakeAnkiBackend"))
        assertFalse(all.contains("ProbeOnlyBackend"))
        assertEquals(
            "production never constructs a Fake identity",
            0,
            Regex("AnkiBackendId\\.Fake\\(").findAll(all).count()
        )
    }

    // ----------------------------------------------------------------------------- fixture completeness

    @Test
    fun `the recording fixture overrides every member AnkiBackend declares`() {
        val declared = main("core/anki/AnkiBackend.kt").readText()
            .substringAfter("interface AnkiBackend {")
            .substringBefore("\n}\n")
        val suspendMembers = Regex("(?m)^    suspend fun (\\w+)\\(").findAll(declared).map { it.groupValues[1] }.toSet()
        // `getDeckSummary` is derived from `getDecks` by the interface itself, so its call is recorded
        // as `getDecks`; the pure, non-suspend helpers cannot reach a backend.
        val derived = setOf("getDeckSummary")
        val fixture = File(testRoot, "anki/Gate21dFixtures.kt").readText()
        assertTrue(suspendMembers.size >= 20)
        (suspendMembers - derived).forEach { name ->
            assertTrue(
                "ProbeOnlyBackend must override and record `$name` or a mutation could slip past the audit",
                fixture.contains("override suspend fun $name(")
            )
        }
    }
}

package com.studyagent.client.anki.ankidroid

import com.studyagent.client.data.anki.ankidroid.AnkiDroidApiContract
import com.studyagent.client.data.anki.ankidroid.AnkiDroidEndpoint
import com.studyagent.client.data.anki.ankidroid.AnkiDroidEndpoints
import com.studyagent.client.data.anki.ankidroid.AnkiDroidProviderSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 02 §4/§10/§15/§16 — the pinned AnkiDroid contract is a *value* this project owns.
 *
 * Every constant here was verified against AnkiDroid v2.24.1 (see the provenance table in
 * [AnkiDroidApiContract]'s KDoc and `docs/ANKIDROID_INTEGRATION.md` §2), so this test protects the
 * two things that can silently rot:
 *
 *  1. the values themselves (a typo in an authority or permission produces a permanent
 *     "not installed" verdict, the single worst failure mode of a detection layer);
 *  2. the *relationships* between them — authority is derived from the application id, debug is a
 *     rename of release, and a release build must never be able to look at a debug endpoint.
 */
class AnkiDroidContractTest {

    @Test
    fun `release contract strings match the pinned AnkiDroid release`() {
        assertEquals("com.ichi2.anki", AnkiDroidApiContract.RELEASE_PACKAGE)
        assertEquals("com.ichi2.anki.flashcards", AnkiDroidApiContract.RELEASE_AUTHORITY)
        assertEquals(
            "com.ichi2.anki.permission.READ_WRITE_DATABASE",
            AnkiDroidApiContract.RELEASE_PERMISSION
        )
        assertEquals("com.ichi2.anki.provider.spec", AnkiDroidApiContract.PROVIDER_SPEC_METADATA_KEY)
        assertEquals("selected_deck", AnkiDroidApiContract.SELECTED_DECK_PATH)
        assertEquals("deck_id", AnkiDroidApiContract.SELECTED_DECK_COLUMN)
    }

    @Test
    fun `debug contract strings are the release contract with the debug application id`() {
        assertEquals("com.ichi2.anki.debug", AnkiDroidApiContract.DEBUG_PACKAGE)
        assertEquals("com.ichi2.anki.debug.flashcards", AnkiDroidApiContract.DEBUG_AUTHORITY)
        assertEquals(
            "com.ichi2.anki.debug.permission.READ_WRITE_DATABASE",
            AnkiDroidApiContract.DEBUG_PERMISSION
        )
        assertNotEquals(AnkiDroidApiContract.RELEASE_AUTHORITY, AnkiDroidApiContract.DEBUG_AUTHORITY)
        assertNotEquals(AnkiDroidApiContract.RELEASE_PERMISSION, AnkiDroidApiContract.DEBUG_PERMISSION)
    }

    @Test
    fun `both endpoints follow the documented application-id template`() {
        // AnkiDroid's manifest declares authorities="${applicationId}.flashcards" and its api
        // module declares "<applicationId>.permission.READ_WRITE_DATABASE". Deriving both from the
        // package keeps one source of truth per endpoint instead of four independent literals.
        for (endpoint in listOf(AnkiDroidEndpoints.RELEASE, AnkiDroidEndpoints.DEBUG)) {
            assertEquals(
                "${endpoint.expectedPackage}.flashcards",
                endpoint.authority
            )
            assertEquals(
                "${endpoint.expectedPackage}.permission.READ_WRITE_DATABASE",
                endpoint.readWritePermission
            )
        }
    }

    @Test
    fun `release builds never see the debug endpoint`() {
        val releaseOnly = AnkiDroidEndpoints.forBuild(includeDebugEndpoints = false)

        assertEquals(listOf(AnkiDroidEndpoints.RELEASE), releaseOnly)
        assertTrue(
            "release endpoint search must not contain a debug authority",
            releaseOnly.none { it.authority.contains(".debug") }
        )
        assertTrue(
            "release endpoint search must not contain a debug package",
            releaseOnly.none { it.expectedPackage.contains(".debug") }
        )
        assertTrue(releaseOnly.none { it.debugOnly })
    }

    @Test
    fun `debug builds search the release endpoint first`() {
        val releaseFirst = AnkiDroidEndpoints.forBuild(includeDebugEndpoints = true)

        assertEquals(
            listOf(AnkiDroidEndpoints.RELEASE, AnkiDroidEndpoints.DEBUG),
            releaseFirst
        )
        assertTrue(AnkiDroidEndpoints.DEBUG.debugOnly)
        assertFalse(AnkiDroidEndpoints.RELEASE.debugOnly)
    }

    @Test
    fun `provider spec policy keeps the documented fallback inside the supported range`() {
        // AddContentApi.DEFAULT_PROVIDER_SPEC_VALUE = 1: "spec 1" is both what the official API
        // falls back to and the minimum this gate supports, so an old-but-working install is
        // reported as usable rather than rejected (§16).
        assertEquals(1, AnkiDroidProviderSpec.IMPLICIT_WHEN_METADATA_ABSENT)
        assertEquals(1, AnkiDroidProviderSpec.MIN_SUPPORTED_SPEC)
        assertEquals(2, AnkiDroidProviderSpec.MAX_VALIDATED_SPEC)
        assertTrue(
            AnkiDroidProviderSpec.IMPLICIT_WHEN_METADATA_ABSENT >= AnkiDroidProviderSpec.MIN_SUPPORTED_SPEC
        )
        assertTrue(
            AnkiDroidProviderSpec.MAX_VALIDATED_SPEC > AnkiDroidProviderSpec.MIN_SUPPORTED_SPEC ||
                AnkiDroidProviderSpec.MAX_VALIDATED_SPEC == AnkiDroidProviderSpec.MIN_SUPPORTED_SPEC
        )
    }

    @Test
    fun `endpoint identities are unique and labelled`() {
        val endpoints: List<AnkiDroidEndpoint> = AnkiDroidEndpoints.forBuild(includeDebugEndpoints = true)

        assertEquals(endpoints.size, endpoints.map { it.authority }.toSet().size)
        assertEquals(endpoints.size, endpoints.map { it.label }.toSet().size)
        assertTrue(endpoints.all { it.label.isNotBlank() })
    }
}

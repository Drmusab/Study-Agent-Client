package com.studyagent.client.anki.ankidroid

import com.studyagent.client.data.anki.ankidroid.AnkiDroidNoteMutationGateway
import com.studyagent.client.data.anki.ankidroid.AnkiDroidNoteWrite
import com.studyagent.client.data.anki.ankidroid.AnkiDroidNoteWriteDispatch
import com.studyagent.client.data.anki.ankidroid.AnkiDroidWritePermit
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidNoteMutationGateway
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidProviderClient
import com.studyagent.client.data.anki.ankidroid.ProviderUpdateResult
import com.studyagent.client.data.anki.ankidroid.ProviderValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 17 — the note-write gateway: one provider call per submit, refusal before IPC, the shared
 * permit, and a timeout that leaves the permit held until the live call returns.
 */
class AnkiDroidNoteMutationGatewayTest {

    private val authority = "com.ichi2.anki.flashcards"
    private val path = "notes/1700000000123"
    private val write = AnkiDroidNoteWrite(
        path = path,
        values = listOf(ProviderValue.StringValue("flds", "a\u001Fb")),
        expectedRows = 1
    )

    private class Rig(
        val provider: FakeAnkiDroidProviderClient,
        val permit: AnkiDroidWritePermit,
        writeTimeoutMs: Long = 2_000L,
        permitTimeoutMs: Long = 500L
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway: AnkiDroidNoteMutationGateway = DefaultAnkiDroidNoteMutationGateway(
            providerClient = provider,
            scope = scope,
            writePermit = permit,
            writeTimeoutMs = writeTimeoutMs,
            permitTimeoutMs = permitTimeoutMs
        )
    }

    @Test
    fun oneSubmitIssuesExactlyOneProviderUpdateWithTheMappedPath() = runBlocking {
        val provider = FakeAnkiDroidProviderClient()
        provider.updateHandlers[path] = { values ->
            assertEquals(1, values.size)
            ProviderUpdateResult.Returned(1)
        }
        val rig = Rig(provider, AnkiDroidWritePermit())
        val dispatch = rig.gateway.submit(authority, write)
        assertEquals(AnkiDroidNoteWriteDispatch.Returned(1), dispatch)
        assertEquals(1L, rig.gateway.physicalWriteCalls)
        assertFalse(rig.gateway.writeInFlight)
        assertEquals(listOf(path), provider.updateLog.map { it.first })
        rig.scope.cancel()
    }

    @Test
    fun anEmptyWriteIsRefusedWithoutAnyProviderCall() = runBlocking {
        val provider = FakeAnkiDroidProviderClient()
        val rig = Rig(provider, AnkiDroidWritePermit())
        val dispatch = rig.gateway.submit(authority, write.copy(values = emptyList()))
        assertTrue(dispatch is AnkiDroidNoteWriteDispatch.NotDispatched)
        assertEquals(0L, rig.gateway.physicalWriteCalls)
        assertTrue(provider.updateLog.isEmpty())
        rig.scope.cancel()
    }

    @Test
    fun aBusyPermitRefusesBeforeAnyProviderCall() = runBlocking {
        val provider = FakeAnkiDroidProviderClient()
        val permit = AnkiDroidWritePermit()
        assertTrue(permit.acquire(1_000L)) // a rating or reviewer action holds the single write slot
        val rig = Rig(provider, permit, permitTimeoutMs = 10L)
        val dispatch = rig.gateway.submit(authority, write)
        assertTrue(dispatch is AnkiDroidNoteWriteDispatch.NotDispatched)
        assertEquals(0L, rig.gateway.physicalWriteCalls)
        assertTrue(provider.updateLog.isEmpty())
        permit.release()
        rig.scope.cancel()
    }

    @Test
    fun aThrownProviderCallIsReportedWithItsClassOnlyAndReleasesThePermit() = runBlocking {
        val provider = FakeAnkiDroidProviderClient()
        provider.updateHandlers[path] = { throw SecurityException("no permission") }
        val rig = Rig(provider, AnkiDroidWritePermit())
        assertEquals(AnkiDroidNoteWriteDispatch.Threw("SecurityException"), rig.gateway.submit(authority, write))
        assertFalse(rig.gateway.writeInFlight)
        rig.scope.cancel()
    }

    @Test
    fun aTimedOutWriteIsUnknownAndKeepsThePermitUntilTheLiveCallReturns() = runBlocking {
        val provider = FakeAnkiDroidProviderClient()
        val finish = CompletableDeferred<Unit>()
        provider.updateHandlers[path] = {
            finish.await()
            ProviderUpdateResult.Returned(1)
        }
        val rig = Rig(provider, AnkiDroidWritePermit(), writeTimeoutMs = 50L)
        assertEquals(AnkiDroidNoteWriteDispatch.Unknown("write_timeout"), rig.gateway.submit(authority, write))
        // The call is still running: the single write slot must stay occupied.
        assertTrue(rig.gateway.writeInFlight)
        finish.complete(Unit)
        // The permit is released by the issued call itself, never by the caller.
        var waited = 0
        while (rig.gateway.writeInFlight && waited < 200) {
            delay(10)
            waited++
        }
        assertFalse(rig.gateway.writeInFlight)
        rig.scope.cancel()
    }
}

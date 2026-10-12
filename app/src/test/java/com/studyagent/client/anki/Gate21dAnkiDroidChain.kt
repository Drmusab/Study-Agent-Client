package com.studyagent.client.anki

import com.studyagent.client.anki.ankidroid.FakeAnkiDroidPermissionManager
import com.studyagent.client.anki.ankidroid.FakeAnkiDroidProbe
import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.common.SystemAppClock
import com.studyagent.client.data.anki.ankidroid.AnkiDroidBackend
import com.studyagent.client.data.anki.ankidroid.AnkiDroidEndpoint
import com.studyagent.client.data.anki.ankidroid.AnkiDroidEndpoints
import com.studyagent.client.data.anki.ankidroid.AnkiDroidWritePermit
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidCapabilityProbe
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidCardGateway
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidCreationGateway
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidDeckGateway
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidDetector
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidGateway
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidHealthCheck
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidHealthProbe
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidModelGateway
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidNoteGateway
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidNoteMutationGateway
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidRatingGateway
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidReviewGateway
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidReviewerActionGateway
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidProviderClient
import com.studyagent.client.data.anki.ankidroid.UnsupportedAnkiDroidCardBrowserGateway
import kotlinx.coroutines.CoroutineScope

/**
 * GATE 21D test fixture — the **real** AnkiDroid availability chain, wired the way `AppContainer`
 * wires it, with only the Android surface faked:
 *
 * ```text
 * FakeAnkiDroidProbe + FakeAnkiDroidPermissionManager     ← the only fakes (package, provider, permission)
 *   → DefaultAnkiDroidDetector → DefaultAnkiDroidHealthCheck → DefaultAnkiDroidHealthProbe
 *   → DefaultAnkiDroidGateway → AnkiDroidBackend                  ← real adapter code
 * ```
 *
 * [fullyWired] mirrors the production composition (every writer gateway present); `false` omits every
 * writer so the capability claim must shrink accordingly (`withWriteSupport`). Nothing here touches
 * a device, a ContentResolver or AnkiDroid's private storage.
 */
internal class Gate21dAnkiDroidChain(
    scope: CoroutineScope,
    fullyWired: Boolean = true,
    val probe: FakeAnkiDroidProbe = FakeAnkiDroidProbe(),
    val permissions: FakeAnkiDroidPermissionManager = FakeAnkiDroidPermissionManager()
) {
    val endpoints: List<AnkiDroidEndpoint> = AnkiDroidEndpoints.forBuild(includeDebugEndpoints = false)
    private val detector = DefaultAnkiDroidDetector(probe, permissions, endpoints)
    private val healthCheck = DefaultAnkiDroidHealthCheck(detector, SystemAppClock)
    private val capabilityProbe = DefaultAnkiDroidCapabilityProbe()
    val providerClient = FakeAnkiDroidProviderClient()
    private val healthProbe = DefaultAnkiDroidHealthProbe(healthCheck, capabilityProbe, providerClient, endpoints)
    val gateway = DefaultAnkiDroidGateway(healthProbe, capabilityProbe, providerClient, endpoints, scope = scope)
    private val permit = AnkiDroidWritePermit()

    val backend: AnkiBackend = if (fullyWired) {
        AnkiDroidBackend(
            gateway = gateway,
            scope = scope,
            deckGateway = DefaultAnkiDroidDeckGateway(providerClient),
            reviewGateway = DefaultAnkiDroidReviewGateway(providerClient),
            cardGateway = DefaultAnkiDroidCardGateway(providerClient),
            noteGateway = DefaultAnkiDroidNoteGateway(providerClient),
            noteMutationGateway = DefaultAnkiDroidNoteMutationGateway(
                providerClient = providerClient, scope = scope, writePermit = permit
            ),
            creationGateway = DefaultAnkiDroidCreationGateway(
                providerClient = providerClient, scope = scope, writePermit = permit
            ),
            modelGateway = DefaultAnkiDroidModelGateway(providerClient),
            ratingGateway = DefaultAnkiDroidRatingGateway(
                providerClient = providerClient, scope = scope, writePermit = permit
            ),
            reviewerActionGateway = DefaultAnkiDroidReviewerActionGateway(
                providerClient = providerClient, scope = scope, writePermit = permit
            ),
            cardBrowserGateway = UnsupportedAnkiDroidCardBrowserGateway()
        )
    } else {
        AnkiDroidBackend(
            gateway = gateway,
            scope = scope,
            deckGateway = DefaultAnkiDroidDeckGateway(providerClient),
            reviewGateway = DefaultAnkiDroidReviewGateway(providerClient),
            cardGateway = DefaultAnkiDroidCardGateway(providerClient)
        )
    }
}

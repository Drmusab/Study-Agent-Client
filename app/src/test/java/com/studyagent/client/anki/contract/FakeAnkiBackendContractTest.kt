package com.studyagent.client.anki.contract

import com.studyagent.client.anki.fake.FakeAnkiBackend

/**
 * GATE 15 §75 — the reusable contract suite executed against the deterministic test fake with the
 * full capability set.
 *
 * The fake is a *contract exerciser*, not production parity evidence: the suite is the bar any real
 * backend must clear before it may advertise card browsing at all.
 */
class FakeAnkiBackendContractTest : AnkiCardBrowserContractSuite() {
    override fun fixture(): ContractFixture =
        FakeContractFixtures.create(FakeAnkiBackend.CARD_BROWSER_CAPABILITIES)
}

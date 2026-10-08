package com.studyagent.client.anki.contract

import com.studyagent.client.anki.fake.FakeAnkiBackend

/**
 * GATE 15 §76 — the same contract suite against a backend that advertises *less*: browsing and deck
 * scope are supported, text search is not, only tag filtering is available, only `Reps` sorting is
 * available, there is no exact total, and the maximum page is 25.
 *
 * Every unsupported component must come back as a typed `UnsupportedQueryFeature` refusal — never as
 * a silently ignored filter, a silently approximated sort, a locally filtered page or a clamped
 * page size.
 */
class LimitedFakeAnkiBackendContractTest : AnkiCardBrowserContractSuite() {
    override fun fixture(): ContractFixture =
        FakeContractFixtures.create(FakeAnkiBackend.LIMITED_CARD_BROWSER_CAPABILITIES)
}

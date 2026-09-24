package com.studyagent.client.anki

import com.studyagent.client.core.anki.AnkiMediaReferencePolicy
import com.studyagent.client.core.anki.AnkiMediaType
import com.studyagent.client.core.render.AnkiCardResourcePolicy
import com.studyagent.client.core.render.AnkiResourceDecision
import org.junit.Assert.*
import org.junit.Test

class AnkiMediaResolverPolicyTest {
    @Test fun logical_names_preserve_unicode_and_safe_punctuation() {
        listOf("my image (2) + عربی#.png", "100% ready.webp", "font.woff2").forEach {
            assertTrue(it, AnkiMediaReferencePolicy.validateLogicalName(it))
        }
    }

    @Test fun traversal_is_rejected_even_when_encoded() {
        listOf("../secret", "..%2Fsecret", "%2E%2E%2Fsecret", "%252E%252E%252Fsecret", "/data/data/x", "C:\\secret").forEach {
            assertFalse(it, AnkiMediaReferencePolicy.validateLogicalName(it))
        }
    }

    @Test fun type_uses_mime_before_extension_and_unknown_is_safe() {
        assertEquals(AnkiMediaType.AUDIO, AnkiMediaReferencePolicy.typeForMimeOrName("audio/mpeg", "x.txt"))
        assertEquals(AnkiMediaType.FONT, AnkiMediaReferencePolicy.typeForMimeOrName("font/woff2", "x.bin"))
        assertEquals(AnkiMediaType.UNKNOWN, AnkiMediaReferencePolicy.typeForMimeOrName(null, "x.bin"))
    }

    @Test fun remote_and_provider_schemes_are_blocked_by_default() {
        assertEquals(AnkiResourceDecision.BLOCK, AnkiCardResourcePolicy.classify("https://example.test/x").decision)
        assertEquals(AnkiResourceDecision.BLOCK, AnkiCardResourcePolicy.classify("content://other/private").decision)
        assertEquals(AnkiResourceDecision.BLOCK, AnkiCardResourcePolicy.classify("file:///data/data/private").decision)
    }
}

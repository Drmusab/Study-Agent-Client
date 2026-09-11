package com.studyagent.client.tts

import com.studyagent.client.core.voice.tts.TtsVoiceInfo
import com.studyagent.client.core.voice.tts.TtsVoiceSelector
import com.studyagent.client.core.voice.tts.VoiceLatency
import com.studyagent.client.core.voice.tts.VoiceQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §68: ranking is deterministic and follows the documented priority ladder. */
class TtsVoiceSelectorTest {

    private fun voice(
        id: String,
        localeTag: String,
        quality: VoiceQuality = VoiceQuality.NORMAL,
        latency: VoiceLatency = VoiceLatency.NORMAL,
        network: Boolean = false
    ) = TtsVoiceInfo(
        id = id,
        displayName = id,
        localeTag = localeTag,
        quality = quality,
        latency = latency,
        networkRequired = network
    )

    @Test
    fun `exact locale preferred over language-only match`() {
        val us = voice("en-us-a", "en-US", VoiceQuality.NORMAL)
        val uk = voice("en-gb-a", "en-GB", VoiceQuality.NORMAL)
        val selected = TtsVoiceSelector.select(listOf(uk, us), "en", "en-US", null, true)
        assertEquals("en-us-a", selected?.id)
    }

    @Test
    fun `language-only fallback when no exact locale`() {
        val uk = voice("en-gb-a", "en-GB", VoiceQuality.HIGH)
        val selected = TtsVoiceSelector.select(listOf(uk), "en", "en-US", null, true)
        assertEquals("en-gb-a", selected?.id)
    }

    @Test
    fun `offline preferred when preferOffline enabled`() {
        val online = voice("en-us-net", "en-US", VoiceQuality.VERY_HIGH, network = true)
        val offline = voice("en-us-local", "en-US", VoiceQuality.HIGH, network = false)
        val selected = TtsVoiceSelector.select(listOf(online, offline), "en", "en-US", null, preferOffline = true)
        assertEquals("en-us-local", selected?.id)
    }

    @Test
    fun `high quality network voice still wins over bad offline voice`() {
        val online = voice("en-us-net", "en-US", VoiceQuality.VERY_HIGH, VoiceLatency.LOW, network = true)
        val offline = voice("en-us-local", "en-US", VoiceQuality.VERY_LOW, VoiceLatency.VERY_HIGH, network = false)
        val selected = TtsVoiceSelector.select(listOf(online, offline), "en", "en-US", null, preferOffline = true)
        assertEquals("en-us-net", selected?.id)
    }

    @Test
    fun `higher quality wins and lower latency breaks ties`() {
        val lowLatency = voice("en-us-fast", "en-US", VoiceQuality.HIGH, VoiceLatency.VERY_LOW)
        val highLatency = voice("en-us-slow", "en-US", VoiceQuality.HIGH, VoiceLatency.HIGH)
        val normal = voice("en-us-mid", "en-US", VoiceQuality.NORMAL, VoiceLatency.VERY_LOW)
        val selected = TtsVoiceSelector.select(listOf(highLatency, normal, lowLatency), "en", "en-US", null, true)
        assertEquals("en-us-fast", selected?.id)
    }

    @Test
    fun `selection is deterministic regardless of input order`() {
        val a = voice("b-voice", "en-US", VoiceQuality.NORMAL)
        val b = voice("a-voice", "en-US", VoiceQuality.NORMAL)
        val one = TtsVoiceSelector.select(listOf(a, b), "en", "en-US", null, true)
        val two = TtsVoiceSelector.select(listOf(b, a), "en", "en-US", null, true)
        assertEquals(one, two)
        assertEquals("a-voice", one?.id) // identical scores tie-break by id
    }

    @Test
    fun `user selected voice wins when still installed`() {
        val auto = voice("en-us-good", "en-US", VoiceQuality.VERY_HIGH, VoiceLatency.VERY_LOW)
        val user = voice("en-gb-user", "en-GB", VoiceQuality.NORMAL)
        val selected = TtsVoiceSelector.select(listOf(auto, user), "en", "en-US", "en-gb-user", true)
        assertEquals("en-gb-user", selected?.id)
    }

    @Test
    fun `missing selected voice falls back gracefully`() {
        val auto = voice("en-us-good", "en-US", VoiceQuality.HIGH)
        val selected = TtsVoiceSelector.select(listOf(auto), "en", "en-US", "deleted-voice", true)
        assertEquals("en-us-good", selected?.id)
    }

    @Test
    fun `network only voice usable when it is the only option`() {
        val online = voice("ar-net", "ar-SA", VoiceQuality.NORMAL, network = true)
        val selected = TtsVoiceSelector.select(listOf(online), "ar", "ar-SA", null, preferOffline = true)
        assertEquals("ar-net", selected?.id)
    }

    @Test
    fun `no compatible voice returns null`() {
        val en = voice("en-us-a", "en-US")
        assertNull(TtsVoiceSelector.select(listOf(en), "ar", "ar-SA", null, true))
    }

    @Test
    fun `display ranking puts chosen voice first`() {
        val voices = listOf(
            voice("en-net", "en-US", VoiceQuality.HIGH, network = true),
            voice("en-local", "en-US", VoiceQuality.NORMAL, network = false)
        )
        val ranked = TtsVoiceSelector.rankedForDisplay(voices, "en", selectedVoiceId = "en-net")
        assertEquals("en-net", ranked.first().id)
        assertTrue(ranked.all { it.language == "en" })
    }
}

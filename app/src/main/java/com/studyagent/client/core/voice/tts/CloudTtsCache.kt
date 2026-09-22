package com.studyagent.client.core.voice.tts

import java.security.MessageDigest

/**
 * Bounded, privacy-aware client-side cache for cloud TTS audio (§84/§85/§86/§87).
 *
 * Privacy contract:
 *  - Keys are SHA-256 over provider|voice|model|rate|options|normalized text —
 *    NO plaintext text, filename or key material appears anywhere.
 *  - In-memory and session-scoped (default). Disk persistence is deliberately
 *    NOT the default (medical content stays on-device for the session only).
 *  - Bounded in entries AND bytes; LRU eviction; age-limited.
 *
 * The cache never changes behavior: a miss is a transparent synthesis request.
 * Repeat-question and preview replays become cache hits (the server-side
 * repeat cache mirrors this on the PC Agent).
 */
class CloudTtsCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private class Entry(val pcm: ByteArray, val createdAtMs: Long)

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private var sizeBytes = 0L
    private var hits = 0
    private var misses = 0
    private var evictions = 0

    @Synchronized
    fun get(key: String): ByteArray? {
        synchronized(lock) {
            val entry = entries[key] ?: run {
                misses++
                return null
            }
            if (clock() - entry.createdAtMs > maxAgeMs) {
                remove(key)
                misses++
                return null
            }
            // Touch (LRU): move to tail.
            entries.remove(key)
            entries[key] = entry
            hits++
            return entry.pcm
        }
    }

    @Synchronized
    fun put(key: String, pcm: ByteArray) {
        if (pcm.isEmpty()) return
        synchronized(lock) {
            val existing = entries.remove(key)
            if (existing != null) sizeBytes -= existing.pcm.size
            entries[key] = Entry(pcm, clock())
            sizeBytes += pcm.size
            // Enforce bounds: evict least-recently-used (head) first.
            while (entries.size > maxEntries || sizeBytes > maxBytes) {
                val oldestKey = entries.keys.first()
                remove(oldestKey)
            }
        }
    }

    private fun remove(key: String) {
        val entry = entries.remove(key) ?: return
        sizeBytes -= entry.pcm.size
        evictions++
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            sizeBytes = 0L
        }
    }

    fun stats(): CacheStats = synchronized(lock) {
        CacheStats(entries.size, sizeBytes, hits, misses, evictions)
    }

    data class CacheStats(
        val entries: Int,
        val bytes: Long,
        val hits: Int,
        val misses: Int,
        val evictions: Int
    ) {
        val hitRate: Double
            get() {
                val total = hits + misses
                return if (total == 0) 0.0 else hits.toDouble() / total
            }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 16
        const val DEFAULT_MAX_BYTES = 64L * 1024 * 1024
        const val DEFAULT_MAX_AGE_MS = 30 * 60 * 1000L
    }
}

/**
 * Deterministic, content-hashed cache key. The plaintext text is normalized
 * (whitespace-collapsed) and hashed — it never appears in the key itself.
 */
object CloudTtsCacheKeys {
    fun key(
        provider: TtsProvider,
        voiceId: String?,
        model: String?,
        rate: Float,
        options: Map<String, Any>,
        text: String
    ): String {
        val optionsJson = options.entries
            .sortedBy { it.key }
            .joinToString("") { "${it.key}=${it.value}" }
        val raw = listOf(
            provider.storageId,
            voiceId.orEmpty(),
            model.orEmpty(),
            String.format("%.3f", rate),
            optionsJson,
            text.trim().replace(Regex("\\s+"), " ")
        ).joinToString("|")
        return sha256Hex(raw)
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

package com.studyagent.client.testutil

/**
 * A tiny bounded history of what a test actually did (§172).
 *
 * When a seeded chaos run fails, "seed=417 step=23 PAUSE" is worth more than any stack trace: it
 * says which action produced the illegal state, and the seed says how to do it again. The buffer
 * is bounded so a long randomized run cannot make a failure report unreadable.
 */
class TestScroll(private val limit: Int = 200) {

    private val entries = ArrayDeque<String>(limit)

    val size: Int get() = entries.size

    fun add(line: String) {
        if (entries.size >= limit) entries.removeFirst()
        entries.addLast(line)
    }

    fun lines(): List<String> = entries.toList()

    fun render(): String = entries.joinToString(separator = "\n")

    override fun toString(): String = render()
}

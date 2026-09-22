package com.studyagent.client.anki

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source-boundary checks supplement behavioral contracts; no framework is needed to run them. */
class AnkiDomainIsolationTest {
    private val app = generateSequence(File("").absoluteFile) { it.parentFile }
        .mapNotNull { dir ->
            listOf(dir, File(dir, "app")).firstOrNull { File(it, "src/main/java/com/studyagent/client/core/anki").isDirectory }
        }.first()
    private val domain = File(app, "src/main/java/com/studyagent/client/core/anki").walkTopDown()
        .filter { it.extension == "kt" }.toList()
    private fun File.code() = readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("(?m)^\\s*//.*$"), "")

    @Test fun `domain is Android framework transport and backend implementation free`() {
        assertTrue(domain.isNotEmpty())
        val forbidden = listOf("android.", "androidx.", "java.io.", "com.ichi2.", "okhttp3.",
            "com.studyagent.client.data.", "com.studyagent.client.core.network.")
        domain.forEach { file ->
            val imports = file.readLines().filter { it.startsWith("import ") }
            assertFalse("Forbidden import in ${file.name}", imports.any { line -> forbidden.any(line::contains) })
        }
    }

    @Test fun `domain does not expose Throwable or Kotlin Result`() {
        domain.forEach { file ->
            assertFalse(file.name, Regex("\\bThrowable\\b|\\bException\\b|(?<!Anki)\\bResult<").containsMatchIn(file.code()))
        }
    }

    @Test fun `backend owns neither voice evaluation nor presentation state`() {
        val backend = domain.single { it.name == "AnkiBackend.kt" }.code()
        listOf("StudyState", "evaluateAnswer", "speakCard", "listenForAnswer", "showAnswer", "pauseStudy").forEach {
            assertFalse(backend.contains(it))
        }
        val card = domain.single { it.name == "AnkiModels.kt" }.code()
        listOf("aiScore", "isAnswerVisible", "isSelected", "isListening").forEach { assertFalse(card.contains(it)) }
    }

    @Test fun `fake is absent from production sources and container has no concrete unfinished backend`() {
        val main = File(app, "src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertFalse(main.any { it.code().contains("FakeAnkiBackend") })
        val container = main.single { it.name == "AppContainer.kt" }.code()
        assertTrue(container.contains("AnkiBackendRegistry(emptyList())"))
        assertFalse(container.contains("AnkiDroidBackend("))
        assertFalse(container.contains("PcAnkiBackend("))
    }
}

package com.studyagent.client.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studyagent.client.appContainer
import com.studyagent.client.awaitNode
import com.studyagent.client.awaitValue
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.readClipboardText
import com.studyagent.client.ui.screens.diagnostics.DiagnosticsScreen
import com.studyagent.client.ui.screens.diagnostics.DiagnosticsViewModel
import com.studyagent.client.ui.theme.StudyAgentTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The diagnostics export on a real device: the buttons reach the clipboard, what lands there is the
 * structured report, and a secret that a developer logged by hand does not survive the trip.
 *
 * The content assertions (every section present, no transcripts, no fake 0 ms) live in the JVM
 * suite `data/DiagnosticsExportPrivacyTest`, which can inspect the full text without a clipboard.
 * This test covers what only a device can: the button → clipboard path, under the platform's
 * clipboard-focus rules.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsExportInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private companion object {
        const val CLIPBOARD_SENTINEL = "instrumented-test-sentinel"
        const val EXPORT_HEADER = "=== Study Agent Diagnostics Export ==="
        const val SUMMARY_HEADER = "=== Study Agent Diagnostics Summary ==="
        const val FAKE_SECRET = "instrumented-secret-token"
    }

    @Before
    fun setUp() {
        seedClipboardSentinel()

        val container = appContainer()
        compose.setContent {
            StudyAgentTheme {
                DiagnosticsScreen(
                    viewModel = DiagnosticsViewModel(diagnosticsRepository = container.diagnosticsRepository),
                    onNavigateBack = {}
                )
            }
        }
    }

    @Test
    fun export_button_writes_the_detailed_report_to_the_clipboard_without_the_logged_secret() {
        // A developer logging a credential by hand is exactly the case the sanitizer exists for.
        AppLogger.i("InstrumentedTest", "Authorization: Bearer $FAKE_SECRET")

        compose.awaitNode(hasContentDescription("Export detailed diagnostics"))
        compose.onNodeWithContentDescription("Export detailed diagnostics").performClick()

        val text = awaitValue(description = "the detailed export in the clipboard", value = ::readClipboardText) {
            it.contains(EXPORT_HEADER) && it != CLIPBOARD_SENTINEL
        }

        assertTrue("the export must describe the connection section", text.contains("--- Connection ---"))
        assertTrue("the export must include the sanitized log rows", text.contains("--- Log ("))
        assertFalse("a bearer token must never survive into an export", text.contains(FAKE_SECRET))
        // Redaction is visible rather than silent: a reader must be able to tell that a value was
        // removed (`LogSanitizer` keeps the header name and replaces the value).
        assertTrue("the removed value must be visible as redacted", text.contains("***REDACTED***"))
    }

    @Test
    fun summary_button_writes_the_compact_report_without_the_log_rows() {
        compose.awaitNode(hasContentDescription("Copy summary"))
        compose.onNodeWithContentDescription("Copy summary").performClick()

        val summary = awaitValue(description = "the summary in the clipboard", value = ::readClipboardText) {
            it.contains(SUMMARY_HEADER) && it != CLIPBOARD_SENTINEL
        }

        assertTrue("the summary must describe the system section", summary.contains("--- System ---"))
        assertTrue("the summary must describe performance", summary.contains("--- Performance ---"))
        assertFalse("the summary is not the detailed export — no log rows", summary.contains("--- Log ("))
        assertFalse("no credentials in the summary either", summary.contains(FAKE_SECRET))
    }

    @Test
    fun a_complete_export_exists_before_any_session_has_run() {
        // A first-run bug report is a common case: the header and the platform details must exist on
        // a clean install, not only after a study session has populated them. This asserts the
        // content the export button delivers (the button→clipboard path is the test above).
        val repository = appContainer().diagnosticsRepository
        val export = repository.getExportText()

        assertTrue(export.contains(EXPORT_HEADER))
        assertTrue("the platform details must be in the header", export.contains("Android: "))
        assertTrue("the app must name itself honestly", export.contains("App: "))
    }

    private fun seedClipboardSentinel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("instrumented", CLIPBOARD_SENTINEL))
    }
}

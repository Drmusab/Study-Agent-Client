package com.studyagent.client

import android.content.ClipboardManager
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.ComposeContentTestRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.studyagent.client.di.AppContainer
import com.studyagent.client.di.ServiceLocator

/**
 * Support for the instrumented suite.
 *
 * The instrumented layer is deliberately thin (`docs/TESTING_STRATEGY.md`): it exists to prove the
 * things a JVM test cannot — that the screens compose on a real Android runtime, that the real
 * `AppContainer` wiring works, that DataStore really stores, and that the clipboard really receives
 * the sanitized export. Session behaviour is proven by the JVM suites and the device matrix, so
 * nothing here re-implements the session.
 */

/**
 * The same process-lifetime container the app uses (§57).
 *
 * Tests exercise the real wiring on purpose: a fake container would only prove that the fake works.
 */
internal fun appContainer(): AppContainer {
    ServiceLocator.initialize(ApplicationProvider.getApplicationContext())
    return ServiceLocator.appContainer
}

/** Waits until at least one node matches [matcher], advancing the Compose clock while polling. */
internal fun ComposeContentTestRule.awaitNode(
    matcher: SemanticsMatcher,
    timeoutMillis: Long = 10_000
) {
    waitUntil(timeoutMillis) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
}

/** Waits until no node matches [matcher]. */
internal fun ComposeContentTestRule.awaitNoNode(
    matcher: SemanticsMatcher,
    timeoutMillis: Long = 10_000
) {
    waitUntil(timeoutMillis) { onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }
}

/** Polls a message-producing lambda until it satisfies [condition], for clipboard assertions. */
internal fun awaitValue(
    timeoutMillis: Long = 5_000,
    description: String,
    value: () -> String?,
    condition: (String) -> Boolean
): String {
    val deadline = System.currentTimeMillis() + timeoutMillis
    var last: String? = null
    while (System.currentTimeMillis() < deadline) {
        val current = value()
        last = current
        if (current != null && condition(current)) return current
        Thread.sleep(50)
    }
    throw AssertionError("$description did not become true within ${timeoutMillis}ms (last=$last)")
}

/**
 * Primary clip text, or null when the platform refuses the read (no focus, or this process is not
 * the default IME — a documented restriction on API 29+). A refusal is reported as null so the
 * caller's poll can fail with its own message instead of an opaque `SecurityException`.
 */
internal fun readClipboardText(): String? = runCatching {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull()

/**
 * Grants the runtime permissions `MainActivity` asks for *before* it launches, so a system dialog
 * cannot swallow the test's input.
 *
 * Shell grants, not `GrantPermissionRule`: the rule throws for a permission the running API level
 * does not know (`BLUETOOTH_CONNECT` below API 31, `POST_NOTIFICATIONS` below 33), and this suite
 * runs on both API 30 and API 34.
 */
internal fun grantDeclaredRuntimePermissions() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val packageName = instrumentation.targetContext.packageName
    listOf(
        "android.permission.RECORD_AUDIO",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.POST_NOTIFICATIONS"
    ).forEach { permission ->
        runCatching {
            val descriptor: ParcelFileDescriptor =
                instrumentation.uiAutomation.executeShellCommand("pm grant $packageName $permission")
            descriptor.close()
        }
    }
}

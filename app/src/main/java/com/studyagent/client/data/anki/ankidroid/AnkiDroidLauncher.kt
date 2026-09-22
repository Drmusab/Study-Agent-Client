package com.studyagent.client.data.anki.ankidroid

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.studyagent.client.core.common.DefaultDispatcherProvider
import com.studyagent.client.core.common.DispatcherProvider
import kotlinx.coroutines.withContext

/** Outcome of an "Open AnkiDroid" request. Never a crash, never a raw exception (§31/§34). */
sealed interface AnkiDroidOpenResult {

    /** AnkiDroid was launched into the foreground. */
    data object Opened : AnkiDroidOpenResult

    /** No configured endpoint's package is installed: the UI may show install guidance. */
    data object NotInstalled : AnkiDroidOpenResult

    /** The package is installed but exposes no launcher activity we can start. */
    data object NoLaunchIntent : AnkiDroidOpenResult

    /** The platform refused or failed the launch; [causeCategory] is a stable technical token. */
    data class Failed(val causeCategory: String) : AnkiDroidOpenResult
}

/**
 * GATE 02 §31/§98 — opening AnkiDroid on the user's behalf, safely.
 *
 * Deliberately distribution-neutral: it launches *whatever package* serves the detected endpoint
 * through a `PackageManager` launch intent, and it never assumes Google Play, an F-Droid package
 * name, or an install URL (§32). If nothing is installed it reports [AnkiDroidOpenResult.NotInstalled]
 * so a later onboarding screen can decide what guidance (if any) to show.
 */
interface AnkiDroidLauncher {

    /** Attempts to bring AnkiDroid to the foreground. Never throws. */
    suspend fun open(): AnkiDroidOpenResult
}

class AndroidAnkiDroidLauncher(
    context: Context,
    private val endpoints: List<AnkiDroidEndpoint>,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider()
) : AnkiDroidLauncher {

    private val appContext: Context = context.applicationContext

    @Suppress("DEPRECATION") // the typed-flags overload only exists on API 33+
    override suspend fun open(): AnkiDroidOpenResult = withContext(dispatchers.io) {
        var sawInstalledPackage = false

        for (endpoint in endpoints) {
            val installed = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.packageManager.getPackageInfo(
                        endpoint.expectedPackage,
                        PackageManager.PackageInfoFlags.of(0L)
                    )
                } else {
                    appContext.packageManager.getPackageInfo(endpoint.expectedPackage, 0)
                }
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            } catch (_: Throwable) {
                false
            }

            if (!installed) continue
            sawInstalledPackage = true

            val launchIntent = try {
                appContext.packageManager.getLaunchIntentForPackage(endpoint.expectedPackage)
            } catch (_: Throwable) {
                null
            } ?: continue

            // This is a cross-application launch from an application context: NEW_TASK is
            // mandatory. The user initiated it from a visible screen, so background-activity-start
            // restrictions do not apply.
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            return@withContext try {
                appContext.startActivity(launchIntent)
                AnkiDroidOpenResult.Opened
            } catch (_: ActivityNotFoundException) {
                AnkiDroidOpenResult.Failed(causeCategory = "activity-not-found")
            } catch (_: SecurityException) {
                AnkiDroidOpenResult.Failed(causeCategory = "launch-denied")
            } catch (_: Throwable) {
                AnkiDroidOpenResult.Failed(causeCategory = "launch-failed")
            }
        }

        if (sawInstalledPackage) {
            AnkiDroidOpenResult.NoLaunchIntent
        } else {
            AnkiDroidOpenResult.NotInstalled
        }
    }
}

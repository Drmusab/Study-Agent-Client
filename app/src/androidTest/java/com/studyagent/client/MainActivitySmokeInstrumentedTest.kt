package com.studyagent.client

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/**
 * The one test that launches the real `MainActivity`.
 *
 * Everything else in this suite hosts a screen directly, because the activity asks for permissions
 * on start and a system dialog would swallow injected input. This test exists to prove the wiring
 * that only the activity exercises: `StudyAgentApp` → `ServiceLocator` → the navigation host → the
 * Home screen. Permissions are granted by shell before the activity launches, so no dialog appears.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeInstrumentedTest {

    @get:Rule(order = 0)
    val permissions = DevicePermissionRule()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun the_application_starts_on_the_home_screen() {
        compose.awaitNode(hasText("Study Agent"))
    }

    /** Grants the runtime permissions the activity requests, tolerating ones this API level lacks. */
    private class DevicePermissionRule : ExternalResource() {
        override fun before() {
            grantDeclaredRuntimePermissions()
        }
    }
}

package com.studyagent.client

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.studyagent.client.core.audio.HeadsetBroadcastReceiver
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.di.ServiceLocator
import com.studyagent.client.service.StudySessionForegroundService
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.navigation.StudyAgentRoot
import com.studyagent.client.ui.theme.StudyAgentTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val tag = "MainActivity"
    private var headsetReceiver: HeadsetBroadcastReceiver? = null

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach { (permission, isGranted) ->
            AppLogger.i(tag, "Permission $permission: granted=$isGranted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.i(tag, "MainActivity onCreate")

        requestAppPermissions()
        registerHeadsetReceiver()
        observeSessionService()

        setContent {
            StudyAgentTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = AppColors.appBackground
                ) {
                    val container = ServiceLocator.appContainer
                    StudyAgentRoot(container = container)

                    LaunchedEffect(Unit) {
                        // Auto connect to default profile if enabled
                        val settings = container.preferencesDataStore.settingsFlow.first()
                        if (settings.autoReconnect) {
                            container.connectionRepository.connect()
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // GATE 02 §28/§30/§57 — the foreground trigger for AnkiDroid health.
        //
        // `onStart` covers app start, every return to the foreground (including "the user just
        // opened AnkiDroid and finished its setup") and navigation back into the app. The
        // repository debounces the call, so this is at most one provider probe per foreground
        // event — never a polling loop.
        //
        // Wrapped for INV-ANKI-DET-09: availability of an optional integration must never be able
        // to prevent Study-Agent from opening. The integration layer already cannot throw here;
        // this is the last guard at the app boundary, and it reports instead of crashing.
        try {
            ServiceLocator.appContainer.ankiDroidHealthRepository.onAppForeground()
        } catch (e: Throwable) {
            AppLogger.w(tag, "AnkiDroid foreground health trigger failed: ${e.message}")
        }
    }

    private fun requestAppPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val ungranted = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isNotEmpty()) {
            AppLogger.i(tag, "Requesting permissions: $ungranted")
            requestPermissionsLauncher.launch(ungranted.toTypedArray())
        }
    }

    private fun registerHeadsetReceiver() {
        val audioRouteManager = ServiceLocator.appContainer.audioRouteManager
        headsetReceiver = HeadsetBroadcastReceiver(audioRouteManager)
        headsetReceiver?.let { receiver ->
            // Android 14 throws for flag-less registerReceiver; system headset/BT
            // broadcasts are still delivered to a non-exported receiver.
            ContextCompat.registerReceiver(
                this,
                receiver,
                receiver.getIntentFilter(),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    private fun observeSessionService() {
        val repo = ServiceLocator.appContainer.studySessionRepository
        lifecycleScope.launch {
            repo.studyState.collect { state ->
                when (state) {
                    is StudyState.SpeakingQuestion,
                    is StudyState.Listening,
                    is StudyState.Evaluating,
                    is StudyState.ShowingFeedback,
                    is StudyState.WaitingForRating -> {
                        StudySessionForegroundService.startService(this@MainActivity)
                    }
                    is StudyState.SessionFinished,
                    is StudyState.Idle -> {
                        StudySessionForegroundService.stopService(this@MainActivity)
                    }
                    else -> Unit
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.i(tag, "MainActivity onDestroy")
        headsetReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                AppLogger.w(tag, "Error unregistering headset receiver: ${e.message}")
            }
        }
    }
}

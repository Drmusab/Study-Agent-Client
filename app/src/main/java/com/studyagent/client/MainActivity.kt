package com.studyagent.client

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.studyagent.client.core.audio.HeadsetBroadcastReceiver
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.di.AppContainer
import com.studyagent.client.di.ServiceLocator
import com.studyagent.client.service.StudySessionForegroundService
import com.studyagent.client.ui.navigation.AppNavHost
import com.studyagent.client.ui.navigation.Screen
import com.studyagent.client.ui.theme.DarkBackground
import com.studyagent.client.ui.theme.DarkSurface
import com.studyagent.client.ui.theme.PrimaryBlue
import com.studyagent.client.ui.theme.StudyAgentTheme
import com.studyagent.client.ui.theme.TextMuted
import com.studyagent.client.ui.theme.TextPrimary
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
                    color = DarkBackground
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
            registerReceiver(receiver, receiver.getIntentFilter())
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

private data class PrimaryDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
)

/**
 * Root scaffold with the primary navigation (§47): Dashboard / Study / Control.
 * Connection, Settings and Diagnostics remain secondary, reached from the
 * Dashboard header. The bar hides during immersive study turns so voice study
 * is never crowded.
 */
@Composable
private fun StudyAgentRoot(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val studyState by container.studySessionRepository.studyState.collectAsState()

    val immersiveStudy = currentRoute == Screen.Study.route && when (studyState) {
        is StudyState.Idle, is StudyState.SessionFinished, is StudyState.Error -> false
        else -> true
    }
    val showBottomBar = currentRoute in Screen.primaryRoutes && !immersiveStudy

    val destinations = listOf(
        PrimaryDestination(Screen.Home.route, "Dashboard", Icons.Default.Dashboard),
        PrimaryDestination(Screen.Study.route, "Study", Icons.Default.School),
        PrimaryDestination(Screen.Control.route, "Control", Icons.Default.Tune)
    )

    Scaffold(
        containerColor = DarkBackground,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = DarkSurface) {
                    destinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    destination.icon,
                                    contentDescription = destination.label,
                                    tint = if (currentRoute == destination.route) PrimaryBlue else TextMuted
                                )
                            },
                            label = {
                                Text(
                                    destination.label,
                                    color = if (currentRoute == destination.route) TextPrimary else TextMuted
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = DarkSurface)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        AppNavHost(
            container = container,
            navController = navController,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

package com.studyagent.client.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class StudySessionForegroundService : Service() {

    private val tag = "StudySessionFgService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var observerJob: Job? = null
    private lateinit var notificationHelper: NotificationHelper

    companion object {
        private const val WAKE_LOCK_TAG = "StudyAgent:StudySessionWakeLock"

        fun startService(context: Context) {
            val intent = Intent(context, StudySessionForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, StudySessionForegroundService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(tag, "Foreground Service created")
        notificationHelper = NotificationHelper(this)

        acquireWakeLock()
        startAsForeground()
        observeStudySession()
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                acquire(120 * 60 * 1000L) // 2 hours max
            }
        } catch (e: Exception) {
            AppLogger.w(tag, "Failed to acquire wake lock: ${e.message}")
        }
    }

    private fun startAsForeground() {
        val initialNotification = notificationHelper.buildNotification(
            deckName = "Active Session",
            cardNumber = 0,
            totalRemaining = 0,
            state = StudyState.Idle
        )

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }

        ServiceCompat.startForeground(
            this,
            NotificationHelper.NOTIFICATION_ID,
            initialNotification,
            serviceType
        )
    }

    private fun observeStudySession() {
        val container = ServiceLocator.appContainer
        val repo = container.studySessionRepository

        observerJob = serviceScope.launch {
            combine(repo.studyState, repo.currentSession) { state, session ->
                Pair(state, session)
            }.collect { (state, session) ->
                if (state is StudyState.SessionFinished || state is StudyState.Idle) {
                    // Update notification or stop if session ended
                }

                val notification = notificationHelper.buildNotification(
                    deckName = session?.deckName ?: "Study Session",
                    cardNumber = session?.cardNumber ?: 0,
                    totalRemaining = session?.remainingCards ?: 0,
                    state = state
                )
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                notificationManager.notify(NotificationHelper.NOTIFICATION_ID, notification)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        AppLogger.d(tag, "onStartCommand action: $action")

        val repo = ServiceLocator.appContainer.studySessionRepository
        when (action) {
            NotificationHelper.ACTION_PAUSE -> {
                serviceScope.launch { repo.pauseStudy() }
            }
            NotificationHelper.ACTION_RESUME -> {
                serviceScope.launch { repo.resumeStudy() }
            }
            NotificationHelper.ACTION_STOP -> {
                serviceScope.launch {
                    repo.endStudy()
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.i(tag, "Foreground Service destroyed")
        observerJob?.cancel()
        serviceScope.cancel()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            AppLogger.w(tag, "Error releasing wake lock: ${e.message}")
        }
    }
}

package com.studyagent.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.studyagent.client.MainActivity
import com.studyagent.client.R
import com.studyagent.client.core.models.StudyState

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "study_agent_active_session"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PAUSE = "com.studyagent.client.ACTION_PAUSE"
        const val ACTION_RESUME = "com.studyagent.client.ACTION_RESUME"
        const val ACTION_STOP = "com.studyagent.client.ACTION_STOP"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.channel_description)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun buildNotification(
        deckName: String,
        cardNumber: Int,
        totalRemaining: Int,
        state: StudyState
    ): Notification {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isPaused = state is StudyState.Paused
        val statusText = when (state) {
            is StudyState.SpeakingQuestion -> "Speaking question..."
            is StudyState.Listening -> "Listening for your answer..."
            is StudyState.Evaluating -> "PC evaluating answer..."
            is StudyState.ShowingFeedback -> "Speaking feedback..."
            is StudyState.WaitingForRating -> "Waiting for rating (Again / Hard / Good / Easy)"
            is StudyState.Paused -> "Session Paused"
            is StudyState.Loading -> state.message
            is StudyState.SessionFinished -> "Session Complete"
            else -> "Study Agent Active"
        }

        val cardInfo = if (cardNumber > 0) "Card $cardNumber" + (if (totalRemaining > 0) " ($totalRemaining left)" else "") else "Ready"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Study Agent — $deckName")
            .setContentText("$cardInfo • $statusText")
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // Actions
        if (isPaused) {
            val resumeIntent = Intent(context, StudySessionForegroundService::class.java).apply {
                action = ACTION_RESUME
            }
            val resumePendingIntent = PendingIntent.getService(
                context,
                1,
                resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_play, "Resume", resumePendingIntent)
        } else {
            val pauseIntent = Intent(context, StudySessionForegroundService::class.java).apply {
                action = ACTION_PAUSE
            }
            val pausePendingIntent = PendingIntent.getService(
                context,
                2,
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, "Pause", pausePendingIntent)
        }

        val stopIntent = Intent(context, StudySessionForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            3,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)

        return builder.build()
    }
}

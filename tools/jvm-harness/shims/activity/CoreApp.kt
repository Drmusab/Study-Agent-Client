// Harness stub — androidx.core.app (the notification / foreground-service surface the app uses).
// See ../compose/Runtime.kt for the policy.
package androidx.core.app

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Bundle

class NotificationCompat {
    class Builder(private val context: Context, channelId: String) {
        fun setSmallIcon(icon: Int): Builder = this
        fun setSmallIcon(icon: Icon): Builder = this
        fun setContentTitle(title: CharSequence?): Builder = this
        fun setContentText(text: CharSequence?): Builder = this
        fun setSubText(text: CharSequence?): Builder = this
        fun setContentIntent(intent: PendingIntent?): Builder = this
        fun setDeleteIntent(intent: PendingIntent?): Builder = this
        fun setOngoing(flag: Boolean): Builder = this
        fun setOnlyAlertOnce(flag: Boolean): Builder = this
        fun setAutoCancel(flag: Boolean): Builder = this
        fun setVisibility(visibility: Int): Builder = this
        fun setPriority(priority: Int): Builder = this
        fun setCategory(category: String): Builder = this
        fun setColor(color: Int): Builder = this
        fun setColorized(colorized: Boolean): Builder = this
        fun setWhen(time: Long): Builder = this
        fun setShowWhen(showWhen: Boolean): Builder = this
        fun setTicker(ticker: CharSequence?): Builder = this
        fun setNumber(number: Int): Builder = this
        fun setProgress(max: Int, progress: Int, indeterminate: Boolean): Builder = this
        fun setStyle(style: Any?): Builder = this
        fun addAction(action: Notification.Action): Builder = this
        fun addAction(icon: Int, title: CharSequence, intent: PendingIntent?): Builder = this
        fun setExtras(extras: Bundle?): Builder = this
        fun build(): Notification = Notification()
    }

    companion object {
        const val CATEGORY_SERVICE: String = "service"
        const val CATEGORY_PROGRESS: String = "progress"
        const val VISIBILITY_PRIVATE: Int = 0
        const val VISIBILITY_PUBLIC: Int = 1
        const val VISIBILITY_SECRET: Int = -1
        const val PRIORITY_MIN: Int = -2
        const val PRIORITY_LOW: Int = -1
        const val PRIORITY_DEFAULT: Int = 0
        const val PRIORITY_HIGH: Int = 1
        const val PRIORITY_MAX: Int = 2
        const val DEFAULT_ALL: Int = -1
    }
}

object ServiceCompat {
    const val STOP_FOREGROUND_REMOVE: Int = 1
    const val STOP_FOREGROUND_DETACH: Int = 2

    fun startForeground(service: android.app.Service, id: Int, notification: Notification?,
                        foregroundServiceType: Int = 0) = Unit
    fun stopForeground(service: android.app.Service, flags: Int) = Unit
}

object NotificationManagerCompat {
    fun from(context: Context): NotificationManagerCompat = this
    fun areNotificationsEnabled(): Boolean = true
    fun notify(id: Int, notification: Notification) = Unit
    fun cancel(id: Int) = Unit
    fun cancel(tag: String?, id: Int) = Unit
}

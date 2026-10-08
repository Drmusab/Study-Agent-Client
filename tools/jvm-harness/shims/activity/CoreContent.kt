// Harness stub — androidx.core.content. See ../compose/Runtime.kt for the policy.
package androidx.core.content

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

object ContextCompat {
    const val RECEIVER_EXPORTED: Int = 2
    const val RECEIVER_NOT_EXPORTED: Int = 4

    fun checkSelfPermission(context: Context, permission: String): Int =
        android.content.pm.PackageManager.PERMISSION_GRANTED

    fun requestPermissions(activity: android.app.Activity, permissions: Array<String>,
                           requestCode: Int) = Unit

    fun registerReceiver(context: Context, receiver: BroadcastReceiver?, filter: IntentFilter,
                         flags: Int): Intent? = null

    fun registerReceiver(context: Context, receiver: BroadcastReceiver?, filter: IntentFilter,
                         broadcastPermission: String?, scheduler: android.os.Handler?): Intent? = null

    fun getColor(context: Context, id: Int): Int = 0
    fun startForegroundService(context: Context, intent: Intent): Intent? = null
}

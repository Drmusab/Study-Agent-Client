package com.studyagent.client.core.audio

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.studyagent.client.core.common.AppLogger

class HeadsetBroadcastReceiver(
    private val audioRouteManager: AudioRouteManager
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        AppLogger.d("HeadsetReceiver", "Received broadcast action: $action")

        when (action) {
            Intent.ACTION_HEADSET_PLUG,
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                audioRouteManager.refreshAudioDevices()
            }
        }
    }

    fun getIntentFilter(): IntentFilter {
        return IntentFilter().apply {
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
    }
}

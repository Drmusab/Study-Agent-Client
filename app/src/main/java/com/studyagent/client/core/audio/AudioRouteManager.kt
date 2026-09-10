package com.studyagent.client.core.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.studyagent.client.core.common.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface AudioRouteManager {
    val activeOutputDevice: StateFlow<AudioDeviceInfoModel>
    val isHeadsetConnected: StateFlow<Boolean>
    fun refreshAudioDevices()
    fun release()
}

class AndroidAudioRouteManager(
    private val context: Context
) : AudioRouteManager {

    private val tag = "AudioRouteMgr"
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _activeOutputDevice = MutableStateFlow(AudioDeviceInfoModel.DEFAULT_SPEAKER)
    override val activeOutputDevice: StateFlow<AudioDeviceInfoModel> = _activeOutputDevice.asStateFlow()

    private val _isHeadsetConnected = MutableStateFlow(false)
    override val isHeadsetConnected: StateFlow<Boolean> = _isHeadsetConnected.asStateFlow()

    private var deviceCallback: AudioDeviceCallback? = null

    init {
        registerAudioDeviceCallback()
        refreshAudioDevices()
    }

    private fun registerAudioDeviceCallback() {
        val am = audioManager ?: return
        deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                AppLogger.d(tag, "Audio devices added: ${addedDevices?.size ?: 0}")
                refreshAudioDevices()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                AppLogger.d(tag, "Audio devices removed: ${removedDevices?.size ?: 0}")
                refreshAudioDevices()
            }
        }
        try {
            am.registerAudioDeviceCallback(deviceCallback, null)
        } catch (e: Exception) {
            AppLogger.w(tag, "Failed to register audio device callback: ${e.message}")
        }
    }

    override fun refreshAudioDevices() {
        val am = audioManager ?: return
        try {
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            var foundHeadset: AudioDeviceInfoModel? = null
            var defaultSpeaker: AudioDeviceInfoModel? = null

            for (dev in devices) {
                val isBt = isBluetoothDevice(dev.type)
                val isHeadset = isHeadsetDevice(dev.type)
                val model = AudioDeviceInfoModel(
                    id = dev.id,
                    name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) dev.productName.toString() else "Audio Device ${dev.id}",
                    typeName = getTypeName(dev.type),
                    isBluetooth = isBt,
                    isHeadset = isHeadset,
                    isMicrophone = dev.isSource
                )

                if (isBt || isHeadset) {
                    foundHeadset = model
                    break
                }
                if (dev.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    defaultSpeaker = model
                }
            }

            if (foundHeadset != null) {
                _isHeadsetConnected.value = true
                _activeOutputDevice.value = foundHeadset
                AppLogger.i(tag, "Active audio route set to Headset: ${foundHeadset.name} (${foundHeadset.typeName})")
            } else {
                _isHeadsetConnected.value = false
                _activeOutputDevice.value = defaultSpeaker ?: AudioDeviceInfoModel.DEFAULT_SPEAKER
                AppLogger.i(tag, "Active audio route set to Speaker: ${_activeOutputDevice.value.name}")
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Error querying audio devices: ${e.message}", e)
        }
    }

    private fun isBluetoothDevice(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_SPEAKER) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && type == AudioDeviceInfo.TYPE_BLE_BROADCAST)
    }

    private fun isHeadsetDevice(type: Int): Boolean {
        return isBluetoothDevice(type) ||
                type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                type == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    private fun getTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in Speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO (Headset)"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP (Stereo)"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            else -> "Audio Route ($type)"
        }
    }

    override fun release() {
        try {
            deviceCallback?.let { audioManager?.unregisterAudioDeviceCallback(it) }
        } catch (e: Exception) {
            AppLogger.w(tag, "Error unregistering audio callback: ${e.message}")
        }
        deviceCallback = null
    }
}

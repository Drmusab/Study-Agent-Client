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

/** What kind of device the microphone input most likely comes from. */
enum class InputRouteKind {
    BLUETOOTH_COMMUNICATION,
    BLE_HEADSET,
    WIRED_HEADSET,
    USB_HEADSET,
    BUILTIN_MIC,
    UNKNOWN
}

/**
 * Best available answer to "which microphone is the recognizer using?" (§60/§61).
 *
 * [isCertain] is the important field. Android does not publish which input route a
 * `SpeechRecognizer` actually selected, so anything inferred from the device list is a
 * *likely* route, not a fact. When it cannot be pinned down the label says so, rather than
 * claiming a Bluetooth microphone just because Bluetooth headphones happen to be connected.
 */
data class InputRouteInfo(
    val kind: InputRouteKind = InputRouteKind.UNKNOWN,
    val label: String = "Unknown",
    val isCertain: Boolean = false
) {
    /** Diagnostics wording that never overstates what we know. */
    val displayLabel: String
        get() = if (isCertain) label else "$label (system-selected)"

    companion object {
        val UNKNOWN = InputRouteInfo()
    }
}

interface AudioRouteManager {
    val activeOutputDevice: StateFlow<AudioDeviceInfoModel>
    val isHeadsetConnected: StateFlow<Boolean>

    /**
     * Enumerated input devices (§60). A2DP is output-only and never appears here, which is
     * exactly why this list — not the output list — is the right source of microphone truth.
     */
    val availableInputDevices: StateFlow<List<AudioDeviceInfoModel>>

    val likelyInputRoute: StateFlow<InputRouteInfo>

    /** True when a connected device can actually supply a microphone to the recognizer. */
    val hasExternalMicrophone: StateFlow<Boolean>

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

    private val _availableInputDevices = MutableStateFlow<List<AudioDeviceInfoModel>>(emptyList())
    override val availableInputDevices: StateFlow<List<AudioDeviceInfoModel>> = _availableInputDevices.asStateFlow()

    private val _likelyInputRoute = MutableStateFlow(InputRouteInfo.UNKNOWN)
    override val likelyInputRoute: StateFlow<InputRouteInfo> = _likelyInputRoute.asStateFlow()

    private val _hasExternalMicrophone = MutableStateFlow(false)
    override val hasExternalMicrophone: StateFlow<Boolean> = _hasExternalMicrophone.asStateFlow()

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
            AppLogger.e(tag, "Error querying output devices: ${e.message}", e)
        }

        refreshInputDevices()
    }

    /**
     * Enumerate input devices and infer the likely microphone route (§60/§61).
     *
     * The distinction that matters: `TYPE_BLUETOOTH_A2DP` is playback-only and never appears
     * in the input list, whereas `TYPE_BLUETOOTH_SCO` appearing here means the communication
     * profile — and therefore a usable headset microphone — is actually available. Reporting
     * "Bluetooth mic active" from the output list alone would have been a guess.
     */
    private fun refreshInputDevices() {
        val am = audioManager ?: return
        try {
            val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val models = inputs.map { dev ->
                AudioDeviceInfoModel(
                    id = dev.id,
                    name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        dev.productName.toString()
                    } else {
                        "Input Device ${dev.id}"
                    },
                    typeName = getTypeName(dev.type),
                    isBluetooth = isBluetoothDevice(dev.type),
                    isHeadset = isHeadsetDevice(dev.type),
                    isMicrophone = dev.isSource
                )
            }
            _availableInputDevices.value = models

            val types = inputs.map { it.type }.toSet()
            val bleHeadset = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                AudioDeviceInfo.TYPE_BLE_HEADSET in types

            val route = when {
                AudioDeviceInfo.TYPE_WIRED_HEADSET in types -> InputRouteInfo(
                    kind = InputRouteKind.WIRED_HEADSET,
                    label = "Wired headset microphone",
                    // A wired headset with a mic is unambiguous: Android routes input there.
                    isCertain = true
                )

                AudioDeviceInfo.TYPE_USB_HEADSET in types -> InputRouteInfo(
                    kind = InputRouteKind.USB_HEADSET,
                    label = "USB headset microphone",
                    isCertain = true
                )

                bleHeadset -> InputRouteInfo(
                    kind = InputRouteKind.BLE_HEADSET,
                    label = "Bluetooth LE headset microphone",
                    isCertain = false
                )

                AudioDeviceInfo.TYPE_BLUETOOTH_SCO in types -> InputRouteInfo(
                    kind = InputRouteKind.BLUETOOTH_COMMUNICATION,
                    label = "Bluetooth headset microphone",
                    // The SCO input exists, but whether the recognizer picked it over the
                    // built-in mic is not observable from here.
                    isCertain = false
                )

                AudioDeviceInfo.TYPE_BUILTIN_MIC in types -> InputRouteInfo(
                    kind = InputRouteKind.BUILTIN_MIC,
                    label = "Built-in microphone",
                    isCertain = types.size == 1
                )

                else -> InputRouteInfo.UNKNOWN
            }
            _likelyInputRoute.value = route
            _hasExternalMicrophone.value = route.kind != InputRouteKind.BUILTIN_MIC &&
                route.kind != InputRouteKind.UNKNOWN
            AppLogger.i(tag, "Likely microphone route: ${route.displayLabel}")
        } catch (e: Exception) {
            AppLogger.e(tag, "Error querying input devices: ${e.message}", e)
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
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Device"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Microphone"
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

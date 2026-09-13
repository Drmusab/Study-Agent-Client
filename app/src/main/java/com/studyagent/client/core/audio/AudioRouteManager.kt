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
    SYSTEM_SELECTED,
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

        /**
         * The built-in microphone is a normal, supported study route (§2/§10/§21/§46).
         * It used to be reported as an untrusted fallback; it is now first-class.
         */
        fun builtIn(certain: Boolean) = InputRouteInfo(
            kind = InputRouteKind.BUILTIN_MIC,
            label = "Built-in microphone",
            isCertain = certain
        )
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

    /**
     * Pure device facts for the study-audio policy (§9/§70). Always available: with no
     * external device this describes the phone (speaker + built-in microphone).
     */
    val routeSnapshot: StateFlow<AudioRouteSnapshot>

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

    // A bare phone is the correct initial description (§71/§90) — it is never "no route".
    private val _routeSnapshot = MutableStateFlow(AudioRouteSnapshot.PHONE_ONLY)
    override val routeSnapshot: StateFlow<AudioRouteSnapshot> = _routeSnapshot.asStateFlow()

    private val availableOutputDevices = MutableStateFlow<List<AudioDeviceInfoModel>>(emptyList())

    private var deviceCallback: AudioDeviceCallback? = null
    private var revision: Long = 0L

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
        val outputs: List<AudioDeviceInfoModel>
        try {
            outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { dev ->
                val deviceClass = classifyOutput(dev.type)
                AudioDeviceInfoModel(
                    id = dev.id,
                    name = deviceName(dev),
                    typeName = getTypeName(dev.type),
                    isBluetooth = deviceClass.isBluetooth,
                    isHeadset = deviceClass.isHeadsetOutput,
                    isMicrophone = dev.isSource,
                    deviceClass = deviceClass
                )
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Error querying output devices: ${e.message}", e)
            return
        }

        val headset = outputs.firstOrNull { it.deviceClass.isHeadsetOutput }
        val speaker = outputs.firstOrNull { it.deviceClass == AudioDeviceClass.BUILTIN_SPEAKER }

        _activeOutputDevice.value = headset ?: speaker ?: AudioDeviceInfoModel.DEFAULT_SPEAKER
        _isHeadsetConnected.value = headset != null
        availableOutputDevices.value = outputs

        if (headset != null) {
            AppLogger.i(tag, "Active output route: ${headset.name} (${headset.typeName})")
        } else {
            AppLogger.i(tag, "Active output route: ${_activeOutputDevice.value.name}")
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
        val inputs: List<AudioDeviceInfoModel>
        try {
            inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS).map { dev ->
                val deviceClass = classifyInput(dev.type)
                AudioDeviceInfoModel(
                    id = dev.id,
                    name = deviceName(dev),
                    typeName = getTypeName(dev.type),
                    isBluetooth = deviceClass.isBluetooth,
                    isHeadset = deviceClass.isHeadsetMic,
                    isMicrophone = dev.isSource,
                    deviceClass = deviceClass
                )
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Error querying input devices: ${e.message}", e)
            return
        }
        _availableInputDevices.value = inputs

        val headsetMic = inputs.firstOrNull { it.deviceClass.isHeadsetMic }
        val builtInMic = inputs.any { it.deviceClass == AudioDeviceClass.BUILTIN_MIC }

        _likelyInputRoute.value = when (headsetMic?.deviceClass) {
            AudioDeviceClass.WIRED_HEADSET -> InputRouteInfo(
                kind = InputRouteKind.WIRED_HEADSET,
                label = "Wired headset microphone",
                // A wired headset with a mic is unambiguous: Android routes input there.
                isCertain = true
            )

            AudioDeviceClass.USB_HEADSET, AudioDeviceClass.USB_DEVICE -> InputRouteInfo(
                kind = InputRouteKind.USB_HEADSET,
                label = "USB headset microphone",
                isCertain = true
            )

            AudioDeviceClass.BLE_HEADSET -> InputRouteInfo(
                kind = InputRouteKind.BLE_HEADSET,
                label = "Bluetooth LE headset microphone",
                isCertain = false
            )

            AudioDeviceClass.BLUETOOTH_SCO -> InputRouteInfo(
                kind = InputRouteKind.BLUETOOTH_COMMUNICATION,
                label = "Bluetooth headset microphone",
                // The SCO input exists, but whether the recognizer picked it over the
                // built-in mic is not observable from here.
                isCertain = false
            )

            else -> if (builtInMic) {
                InputRouteInfo.builtIn(certain = inputs.size == 1)
            } else if (inputs.isNotEmpty()) {
                InputRouteInfo(
                    kind = InputRouteKind.SYSTEM_SELECTED,
                    label = "System-selected microphone",
                    isCertain = false
                )
            } else {
                InputRouteInfo.UNKNOWN
            }
        }

        // External-microphone *fact* (kept for Diagnostics/back-compat). Note that nothing in
        // the study path may require this to be true: the built-in microphone is enough (§11).
        _hasExternalMicrophone.value = _likelyInputRoute.value.kind != InputRouteKind.BUILTIN_MIC &&
            _likelyInputRoute.value.kind != InputRouteKind.SYSTEM_SELECTED &&
            _likelyInputRoute.value.kind != InputRouteKind.UNKNOWN

        revision += 1
        _routeSnapshot.value = AudioRouteSnapshotFactory.fromDevices(
            outputs = availableOutputDevices.value,
            inputs = inputs,
            revision = revision
        )

        AppLogger.i(
            tag,
            "Likely microphone route: ${_likelyInputRoute.value.displayLabel} " +
                "(external=${_hasExternalMicrophone.value})"
        )
    }

    private fun deviceName(dev: AudioDeviceInfo): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dev.productName?.toString().orEmpty().ifBlank { "Audio Device ${dev.id}" }
        } else {
            "Audio Device ${dev.id}"
        }

    private fun classifyOutput(type: Int): AudioDeviceClass = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioDeviceClass.BLUETOOTH_A2DP
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioDeviceClass.BLUETOOTH_SCO
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioDeviceClass.WIRED_HEADSET
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioDeviceClass.WIRED_HEADPHONES
        AudioDeviceInfo.TYPE_USB_HEADSET -> AudioDeviceClass.USB_HEADSET
        AudioDeviceInfo.TYPE_USB_DEVICE -> AudioDeviceClass.USB_DEVICE
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioDeviceClass.BUILTIN_SPEAKER
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioDeviceClass.BUILTIN_EARPIECE
        AudioDeviceInfo.TYPE_BLE_HEADSET -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioDeviceClass.BLE_HEADSET
        } else {
            AudioDeviceClass.OTHER
        }
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioDeviceClass.BLE_SPEAKER
        } else {
            AudioDeviceClass.OTHER
        }
        else -> AudioDeviceClass.OTHER
    }

    private fun classifyInput(type: Int): AudioDeviceClass = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioDeviceClass.BUILTIN_MIC
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioDeviceClass.WIRED_HEADSET
        AudioDeviceInfo.TYPE_USB_DEVICE -> AudioDeviceClass.USB_DEVICE
        AudioDeviceInfo.TYPE_USB_HEADSET -> AudioDeviceClass.USB_HEADSET
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioDeviceClass.BLUETOOTH_SCO
        AudioDeviceInfo.TYPE_FM_TUNER -> AudioDeviceClass.OTHER
        AudioDeviceInfo.TYPE_TELEPHONY -> AudioDeviceClass.TELEPHONY
        AudioDeviceInfo.TYPE_BLE_HEADSET -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioDeviceClass.BLE_HEADSET
        } else {
            AudioDeviceClass.OTHER
        }
        else -> AudioDeviceClass.OTHER
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

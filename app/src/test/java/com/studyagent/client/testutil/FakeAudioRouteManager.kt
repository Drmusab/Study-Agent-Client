package com.studyagent.client.testutil

import com.studyagent.client.core.audio.AudioDeviceClass
import com.studyagent.client.core.audio.AudioDeviceInfoModel
import com.studyagent.client.core.audio.AudioRouteManager
import com.studyagent.client.core.audio.AudioRouteSnapshot
import com.studyagent.client.core.audio.HeadsetMicKind
import com.studyagent.client.core.audio.HeadsetOutputKind
import com.studyagent.client.core.audio.InputRouteInfo
import com.studyagent.client.core.audio.InputRouteKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Audio routing without Android (§12).
 *
 * The device *facts* live in one [AudioRouteSnapshot] and the derived flows are recomputed from
 * it, so a test changes the world in one place — `routeManager.setRoute(TestRoutes.bluetooth)` —
 * and every consumer (mode resolver, coordinator, diagnostics) sees a consistent view.
 *
 * No `AudioManager` is involved, and nothing here re-implements the production resolver: the
 * projections below are pure functions of the snapshot, exactly like the Android layer's
 * `AudioManager.getDevices()` mapping.
 */
class FakeAudioRouteManager(initial: AudioRouteSnapshot = TestRoutes.phone) : AudioRouteManager {

    private val _routeSnapshot = MutableStateFlow(initial)
    override val routeSnapshot: StateFlow<AudioRouteSnapshot> = _routeSnapshot.asStateFlow()

    private val _activeOutputDevice = MutableStateFlow(initial.outputDevice())
    override val activeOutputDevice: StateFlow<AudioDeviceInfoModel> = _activeOutputDevice.asStateFlow()

    /** True when an external output exists — the same meaning the production flow carries. */
    private val _isHeadsetConnected = MutableStateFlow(initial.headsetOutputAvailable)
    override val isHeadsetConnected: StateFlow<Boolean> = _isHeadsetConnected.asStateFlow()

    private val _availableInputDevices = MutableStateFlow(initial.inputDevices())
    override val availableInputDevices: StateFlow<List<AudioDeviceInfoModel>> =
        _availableInputDevices.asStateFlow()

    private val _likelyInputRoute = MutableStateFlow(initial.likelyInputRoute())
    override val likelyInputRoute: StateFlow<InputRouteInfo> = _likelyInputRoute.asStateFlow()

    private val _hasExternalMicrophone = MutableStateFlow(initial.headsetMicAvailable)
    override val hasExternalMicrophone: StateFlow<Boolean> = _hasExternalMicrophone.asStateFlow()

    var refreshCount: Int = 0
        private set
    var releaseCount: Int = 0
        private set

    /** Publishes a new device set, exactly as the Android layer would after a route change. */
    fun setRoute(snapshot: AudioRouteSnapshot) {
        _routeSnapshot.value = snapshot
        _activeOutputDevice.value = snapshot.outputDevice()
        _isHeadsetConnected.value = snapshot.headsetOutputAvailable
        _availableInputDevices.value = snapshot.inputDevices()
        _likelyInputRoute.value = snapshot.likelyInputRoute()
        _hasExternalMicrophone.value = snapshot.headsetMicAvailable
    }

    override fun refreshAudioDevices() {
        refreshCount++
    }

    override fun release() {
        releaseCount++
    }

    // ------------------------------------------------------------------ projections

    private fun AudioRouteSnapshot.outputDevice(): AudioDeviceInfoModel = when (headsetOutput) {
        HeadsetOutputKind.BLUETOOTH -> AudioDeviceInfoModel(
            id = BLUETOOTH_OUTPUT_ID,
            name = headsetOutputLabel ?: "Bluetooth headset",
            typeName = "Bluetooth A2DP",
            isBluetooth = true,
            isHeadset = true,
            isMicrophone = false,
            deviceClass = AudioDeviceClass.BLUETOOTH_A2DP
        )

        HeadsetOutputKind.WIRED -> AudioDeviceInfoModel(
            id = WIRED_OUTPUT_ID,
            name = headsetOutputLabel ?: "Wired headset",
            typeName = "Wired headphones",
            isBluetooth = false,
            isHeadset = true,
            isMicrophone = false,
            deviceClass = AudioDeviceClass.WIRED_HEADPHONES
        )

        HeadsetOutputKind.USB -> AudioDeviceInfoModel(
            id = USB_OUTPUT_ID,
            name = headsetOutputLabel ?: "USB audio",
            typeName = "USB audio",
            isBluetooth = false,
            isHeadset = true,
            isMicrophone = false,
            deviceClass = AudioDeviceClass.USB_HEADSET
        )

        null -> AudioDeviceInfoModel.DEFAULT_SPEAKER
    }

    private fun AudioRouteSnapshot.inputDevices(): List<AudioDeviceInfoModel> {
        val devices = mutableListOf(
            AudioDeviceInfoModel(
                id = BUILTIN_MIC_ID,
                name = "Built-in microphone",
                typeName = "Built-in Mic",
                isBluetooth = false,
                isHeadset = false,
                isMicrophone = true,
                deviceClass = AudioDeviceClass.BUILTIN_MIC
            )
        )
        if (headsetMicAvailable) {
            val bluetooth = headsetMic == HeadsetMicKind.BLUETOOTH_MIC
            devices += AudioDeviceInfoModel(
                id = HEADSET_MIC_ID,
                name = headsetMicLabel ?: "Headset microphone",
                typeName = if (bluetooth) "Bluetooth SCO" else "Wired headset",
                isBluetooth = bluetooth,
                isHeadset = true,
                isMicrophone = true,
                deviceClass = if (bluetooth) AudioDeviceClass.BLUETOOTH_SCO else AudioDeviceClass.WIRED_HEADSET
            )
        }
        return devices
    }

    /**
     * Android does not publish which input the recognizer actually selected, so an inferred route
     * is reported as *not certain* — the same honesty rule the production layer follows.
     */
    private fun AudioRouteSnapshot.likelyInputRoute(): InputRouteInfo = when {
        !headsetMicAvailable -> InputRouteInfo.builtIn(certain = false)

        headsetMic == HeadsetMicKind.BLUETOOTH_MIC -> InputRouteInfo(
            kind = InputRouteKind.BLUETOOTH_COMMUNICATION,
            label = headsetMicLabel ?: "Bluetooth headset microphone",
            isCertain = false
        )

        headsetMic == HeadsetMicKind.USB_MIC -> InputRouteInfo(
            kind = InputRouteKind.USB_HEADSET,
            label = headsetMicLabel ?: "USB microphone",
            isCertain = true
        )

        else -> InputRouteInfo(
            kind = InputRouteKind.WIRED_HEADSET,
            label = headsetMicLabel ?: "Wired headset microphone",
            isCertain = true
        )
    }

    private companion object {
        const val BLUETOOTH_OUTPUT_ID = 101
        const val WIRED_OUTPUT_ID = 102
        const val USB_OUTPUT_ID = 103
        const val BUILTIN_MIC_ID = 201
        const val HEADSET_MIC_ID = 202
    }
}

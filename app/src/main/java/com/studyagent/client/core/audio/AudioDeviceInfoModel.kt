package com.studyagent.client.core.audio

/**
 * One enumerated audio device, described in types the rest of the app can reason about.
 *
 * [deviceClass] is the Android-free classification ([AudioDeviceClass]) so the study-audio
 * policy can be unit-tested, and so "A2DP present but no microphone" is visible as fact
 * rather than inferred from a `isHeadset` boolean.
 */
data class AudioDeviceInfoModel(
    val id: Int,
    val name: String,
    val typeName: String,
    val isBluetooth: Boolean,
    val isHeadset: Boolean,
    val isMicrophone: Boolean,
    val deviceClass: AudioDeviceClass = AudioDeviceClass.OTHER
) {
    companion object {
        val DEFAULT_SPEAKER = AudioDeviceInfoModel(
            id = 0,
            name = "Phone Speaker",
            typeName = "Built-in Speaker",
            isBluetooth = false,
            isHeadset = false,
            isMicrophone = false,
            deviceClass = AudioDeviceClass.BUILTIN_SPEAKER
        )
    }
}

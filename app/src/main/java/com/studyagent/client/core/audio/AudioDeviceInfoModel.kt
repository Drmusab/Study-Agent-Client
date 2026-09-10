package com.studyagent.client.core.audio

data class AudioDeviceInfoModel(
    val id: Int,
    val name: String,
    val typeName: String,
    val isBluetooth: Boolean,
    val isHeadset: Boolean,
    val isMicrophone: Boolean
) {
    companion object {
        val DEFAULT_SPEAKER = AudioDeviceInfoModel(
            id = 0,
            name = "Phone Speaker",
            typeName = "Built-in Speaker",
            isBluetooth = false,
            isHeadset = false,
            isMicrophone = false
        )
    }
}

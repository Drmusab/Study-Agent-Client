package com.studyagent.client.core.audio

/**
 * Device families, expressed without Android types so the policy that consumes them stays
 * unit-testable (§70/§89). The Android layer maps `AudioDeviceInfo.type` onto this enum.
 */
enum class AudioDeviceClass {
    BLUETOOTH_A2DP,
    BLUETOOTH_SCO,
    BLE_HEADSET,
    BLE_SPEAKER,
    BLE_BROADCAST,
    WIRED_HEADSET,
    WIRED_HEADPHONES,
    USB_HEADSET,
    USB_DEVICE,
    BUILTIN_SPEAKER,
    BUILTIN_EARPIECE,
    BUILTIN_MIC,
    TELEPHONY,
    OTHER;

    val isBluetooth: Boolean
        get() = this == BLUETOOTH_A2DP ||
            this == BLUETOOTH_SCO ||
            this == BLE_HEADSET ||
            this == BLE_SPEAKER ||
            this == BLE_BROADCAST

    /** Can carry playback. A2DP is output-only; that is exactly why it never proves a mic. */
    val isOutputCapable: Boolean
        get() = this == BLUETOOTH_A2DP ||
            this == BLUETOOTH_SCO ||
            this == BLE_HEADSET ||
            this == BLE_SPEAKER ||
            this == WIRED_HEADSET ||
            this == WIRED_HEADPHONES ||
            this == USB_HEADSET ||
            this == BUILTIN_SPEAKER ||
            this == BUILTIN_EARPIECE

    /**
     * External headset **output**: an output device that belongs to a headset/headphones.
     * BLE speakers are deliberately excluded — they are not private.
     */
    val isHeadsetOutput: Boolean
        get() = this == BLUETOOTH_A2DP ||
            this == BLUETOOTH_SCO ||
            this == BLE_HEADSET ||
            this == WIRED_HEADSET ||
            this == WIRED_HEADPHONES ||
            this == USB_HEADSET

    /**
     * A microphone that comes with a headset. `TYPE_BLUETOOTH_A2DP` cannot appear here: it is
     * playback-only, which is how output-only headphones are detected honestly (§73).
     */
    val isHeadsetMic: Boolean
        get() = this == BLUETOOTH_SCO ||
            this == BLE_HEADSET ||
            this == WIRED_HEADSET ||
            this == USB_HEADSET ||
            this == USB_DEVICE
}

/**
 * Pure translation of enumerated devices into the policy snapshot.
 *
 * Kept free of Android types so that "A2DP present, no Bluetooth microphone, built-in mic
 * available" resolutions are testable without a device (§89/§99).
 */
object AudioRouteSnapshotFactory {

    /** Bluetooth microphone profiles, best first (SCO/BLE carry the communication profile). */
    private val headsetMicPriority = listOf(
        AudioDeviceClass.WIRED_HEADSET,
        AudioDeviceClass.USB_HEADSET,
        AudioDeviceClass.USB_DEVICE,
        AudioDeviceClass.BLE_HEADSET,
        AudioDeviceClass.BLUETOOTH_SCO
    )

    /** Headset outputs, best first: a communication-capable headset beats A2DP-only. */
    private val headsetOutputPriority = listOf(
        AudioDeviceClass.WIRED_HEADSET,
        AudioDeviceClass.USB_HEADSET,
        AudioDeviceClass.BLUETOOTH_SCO,
        AudioDeviceClass.BLE_HEADSET,
        AudioDeviceClass.BLUETOOTH_A2DP,
        AudioDeviceClass.WIRED_HEADPHONES
    )

    fun fromDevices(
        outputs: List<AudioDeviceInfoModel>,
        inputs: List<AudioDeviceInfoModel>,
        revision: Long
    ): AudioRouteSnapshot {
        val outputClass = firstByPriority(outputs.map { it.deviceClass }, headsetOutputPriority)
        val micDevice = inputs.firstOrNull { it.deviceClass.isHeadsetMic }
            ?: inputs.firstOrNull { firstByPriority(listOf(it.deviceClass), headsetMicPriority) != null }

        val headsetOutput = outputClass?.toHeadsetOutputKind()
        val headsetMic = micDevice?.deviceClass?.toHeadsetMicKind()

        val builtInSpeaker = outputs.any { it.deviceClass == AudioDeviceClass.BUILTIN_SPEAKER }
        val builtInEarpiece = outputs.any { it.deviceClass == AudioDeviceClass.BUILTIN_EARPIECE }
        val builtInMic = inputs.any { it.deviceClass == AudioDeviceClass.BUILTIN_MIC }

        // When a wired/USB headset is present, Android routes its microphone deterministically;
        // Bluetooth and inferred routes stay "likely" because the platform does not confirm.
        val inputCertainty = when {
            micDevice == null -> if (builtInMic) RouteCertainty.LIKELY else RouteCertainty.UNKNOWN
            micDevice.deviceClass == AudioDeviceClass.WIRED_HEADSET ||
                micDevice.deviceClass == AudioDeviceClass.USB_HEADSET -> RouteCertainty.CONFIRMED
            else -> RouteCertainty.LIKELY
        }

        return AudioRouteSnapshot(
            revision = revision,
            headsetOutput = headsetOutput,
            headsetOutputLabel = when (headsetOutput) {
                HeadsetOutputKind.BLUETOOTH -> "Bluetooth headset"
                HeadsetOutputKind.WIRED -> if (outputClass == AudioDeviceClass.WIRED_HEADPHONES) {
                    "Wired headphones"
                } else {
                    "Wired headset"
                }
                HeadsetOutputKind.USB -> "USB headset"
                null -> null
            },
            headsetMic = headsetMic,
            headsetMicLabel = when (headsetMic) {
                HeadsetMicKind.BLUETOOTH_MIC -> "Bluetooth headset microphone"
                HeadsetMicKind.WIRED_MIC -> "Wired headset microphone"
                HeadsetMicKind.USB_MIC -> "USB microphone"
                null -> null
            },
            builtInSpeakerAvailable = builtInSpeaker,
            builtInEarpieceAvailable = builtInEarpiece,
            builtInMicAvailable = builtInMic,
            // Enumeration succeeded; what remains uncertain is which device the recognizer or
            // the media stream actually picks, which is expressed per-route by the resolver.
            outputCertainty = if (outputs.isEmpty()) RouteCertainty.UNKNOWN else RouteCertainty.CONFIRMED,
            inputCertainty = inputCertainty
        )
    }

    private fun firstByPriority(
        classes: List<AudioDeviceClass>,
        priority: List<AudioDeviceClass>
    ): AudioDeviceClass? = priority.firstOrNull { it in classes }

    private fun AudioDeviceClass.toHeadsetOutputKind(): HeadsetOutputKind? = when {
        this == AudioDeviceClass.WIRED_HEADSET || this == AudioDeviceClass.WIRED_HEADPHONES ->
            HeadsetOutputKind.WIRED
        this == AudioDeviceClass.USB_HEADSET -> HeadsetOutputKind.USB
        this == AudioDeviceClass.BLUETOOTH_SCO ||
            this == AudioDeviceClass.BLUETOOTH_A2DP ||
            this == AudioDeviceClass.BLE_HEADSET -> HeadsetOutputKind.BLUETOOTH
        else -> null
    }

    private fun AudioDeviceClass.toHeadsetMicKind(): HeadsetMicKind? = when (this) {
        AudioDeviceClass.WIRED_HEADSET -> HeadsetMicKind.WIRED_MIC
        AudioDeviceClass.USB_HEADSET, AudioDeviceClass.USB_DEVICE -> HeadsetMicKind.USB_MIC
        AudioDeviceClass.BLUETOOTH_SCO, AudioDeviceClass.BLE_HEADSET -> HeadsetMicKind.BLUETOOTH_MIC
        else -> null
    }
}

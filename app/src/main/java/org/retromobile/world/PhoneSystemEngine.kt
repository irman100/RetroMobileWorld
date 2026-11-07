package org.retromobile.world

import java.io.File

enum class PhoneMachineState { STOPPED, READY, RUNNING, PAUSED, FAILED }

enum class FramePixelFormat { RGB_565, RGB_888, RGBA_8888 }

data class PhoneMachineFrame(
    val width: Int,
    val height: Int,
    val rowStrideBytes: Int,
    val pixelFormat: FramePixelFormat,
    val pixels: ByteArray,
    val frameNumber: Long
) {
    init {
        require(width > 0 && height > 0)
        require(rowStrideBytes > 0)
        require(frameNumber >= 0)
        val bytesPerPixel = when (pixelFormat) {
            FramePixelFormat.RGB_565 -> 2
            FramePixelFormat.RGB_888 -> 3
            FramePixelFormat.RGBA_8888 -> 4
        }
        require(rowStrideBytes >= width * bytesPerPixel)
        require(pixels.size >= rowStrideBytes * height)
    }
}

data class PhoneMachineAudio(
    val sampleRateHz: Int,
    val channelCount: Int,
    val samples: ShortArray
) {
    init {
        require(sampleRateHz > 0)
        require(channelCount in 1..2)
        require(samples.size % channelCount == 0)
    }
}

data class PhoneFirmwareSet(
    val core: FirmwareImageRecord,
    val languagePack: FirmwareImageRecord? = null,
    val contentPack: FirmwareImageRecord? = null
) {
    init {
        require(core.metadata.kind == FirmwareImageKind.CORE) {
            "The primary firmware component must be an MCU/MCUSW image."
        }
        require(languagePack == null || languagePack.metadata.kind == FirmwareImageKind.LANGUAGE_PACK) {
            "The optional language component must be a PPM image."
        }
        require(contentPack == null || contentPack.metadata.kind == FirmwareImageKind.CONTENT_PACK) {
            "The optional content component must be an IMAGE pack."
        }
    }
}

data class PhoneMachineStep(
    val state: PhoneMachineState,
    val frameChanged: Boolean,
    val audioAvailable: Boolean,
    val diagnostic: String? = null
)

/**
 * Contract for a complete phone operating-system machine (CPU + SoC + firmware),
 * not for an individual Java ME or Symbian application runtime.
 *
 * No implementation is registered yet. The RM-217 backend will be added only after
 * its CPU/SoC model has been validated against a real user-provided firmware image.
 */
interface PhoneSystemEngine : AutoCloseable {
    val id: String
    val deviceOsFamily: DeviceOsFamily
    val state: PhoneMachineState

    fun boot(
        firmware: PhoneFirmwareSet,
        persistentStorageDirectory: File
    ): PhoneMachineStep

    /** Run a bounded amount of emulated time, then return to the Android render loop. */
    fun runSlice(budgetMicros: Long): PhoneMachineStep

    /** Both press and release edges must reach the emulated keypad controller. */
    fun sendKey(key: CanonicalKey, pressed: Boolean)

    /** Returns the newest native LCD frame, or null until the first frame is produced. */
    fun latestFrame(): PhoneMachineFrame?

    /** Drains pending mono/stereo PCM samples; returns null when no audio is ready. */
    fun drainAudio(): PhoneMachineAudio?

    fun pause()
    fun resume()
    fun stop()
}

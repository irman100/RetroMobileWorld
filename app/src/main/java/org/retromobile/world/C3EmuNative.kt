package org.retromobile.world

/**
 * Thin JNI facade over the upstream c3emu-web C ABI.
 *
 * C3EmuEngine serializes every call through one worker thread because upstream
 * c3emu-web stores its emulator session in thread-local state.
 */
internal object C3EmuNative {
    init {
        System.loadLibrary("c3emu_android")
    }

    external fun nativeSetFile(kind: Int, data: ByteArray): String
    external fun nativeBoot(): String
    external fun nativeRun(budgetMillis: Double): Double
    external fun nativeResultText(): String
    external fun nativeFrameSerial(): Int
    external fun nativeFrame(): ByteArray
    external fun nativeDrainAudio(): ShortArray
    external fun nativeDrainDirtyDisks(): ByteArray
    external fun nativeKey(index: Int, down: Int)
    external fun nativeStats(): String
    external fun nativeSetTime(unixSeconds: Double, utcOffsetSeconds: Double)
    external fun nativeClearDisks()
    external fun nativeSetDiskSize(device: Int, sizeBytes: Long)
    external fun nativeRestoreDiskChunk(
        device: Int,
        offsetBytes: Double,
        diskSizeBytes: Long,
        chunk: ByteArray
    ): String
    external fun nativeStop()
}

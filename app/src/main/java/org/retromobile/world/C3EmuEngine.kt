package org.retromobile.world

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TimeZone
import android.os.SystemClock
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Nokia C3-00 / RM-614 Series 40 machine backed by upstream c3emu.
 *
 * c3emu-web keeps emulator state in thread-local storage. Every native call is
 * serialized through this engine's dedicated worker thread.
 */
data class C3EmuCycle(
    val step: PhoneMachineStep,
    val frame: PhoneMachineFrame?,
    val audio: PhoneMachineAudio?,
    val waitMillis: Double
)

class C3EmuEngine : PhoneSystemEngine {
    override val id: String = "c3emu-rm614"
    override val deviceOsFamily: DeviceOsFamily = DeviceOsFamily.NOKIA_S40

    @Volatile
    override var state: PhoneMachineState = PhoneMachineState.STOPPED
        private set

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "c3emu-rm614-native").apply { isDaemon = true }
    }
    @Volatile private var nativeThread: Thread? = null
    private var storageDirectory: File? = null
    @Volatile private var cachedFrame: PhoneMachineFrame? = null
    private var frameSerial = -1
    private var nextWaitMillis = 0.0
    private var lastStorageFlushAtMs = 0L
    private var lastFrameDeliveryAtMs = 0L
    private var pendingAudio: ShortArray? = null
    private var closed = false

    private fun <T> onNativeThread(block: () -> T): T {
        check(!closed) { "The C3-00 emulator engine is closed." }
        if (Thread.currentThread() === nativeThread) return block()
        return executor.submit(Callable {
            nativeThread = Thread.currentThread()
            block()
        }).get(60, TimeUnit.SECONDS)
    }

    override fun boot(
        firmware: PhoneFirmwareSet,
        persistentStorageDirectory: File
    ): PhoneMachineStep = onNativeThread {
        check(state == PhoneMachineState.STOPPED || state == PhoneMachineState.FAILED) {
            "Stop the current C3-00 session before booting another firmware set."
        }

        val records = listOfNotNull(
            firmware.core,
            firmware.languagePack,
            firmware.contentPack
        )
        records.forEach { record ->
            val normalized = record.metadata.originalFileName
                .lowercase()
                .replace("-", "")
                .replace("_", "")
            require("rm614" in normalized) {
                "c3emu runs Nokia C3-00 / RM-614 firmware only. " +
                    "${record.metadata.originalFileName} is not identified as RM-614."
            }
            require(record.file.isFile && record.file.length() > 0L) {
                "Firmware component is missing: ${record.metadata.originalFileName}"
            }
        }

        storageDirectory = persistentStorageDirectory.apply { mkdirs() }
        C3EmuNative.nativeStop()
        C3EmuNative.nativeClearDisks()
        C3EmuNative.nativeSetFileFromPath(0, firmware.core.file.absolutePath)
            .requireNativeSuccess("MCUSW")
        firmware.languagePack?.let {
            C3EmuNative.nativeSetFileFromPath(1, it.file.absolutePath)
                .requireNativeSuccess("PPM")
        }
        firmware.contentPack?.let {
            C3EmuNative.nativeSetFileFromPath(2, it.file.absolutePath)
                .requireNativeSuccess("IMAGE")
        }
        val nowMillis = System.currentTimeMillis()
        C3EmuNative.nativeSetTime(
            nowMillis / 1000.0,
            TimeZone.getDefault().getOffset(nowMillis) / 1000.0
        )

        restorePersistentStorage(persistentStorageDirectory)
        val error = C3EmuNative.nativeBoot()
        if (error.isNotBlank()) {
            state = PhoneMachineState.FAILED
            return@onNativeThread PhoneMachineStep(state, false, false, error)
        }

        frameSerial = -1
        lastFrameDeliveryAtMs = 0L
        cachedFrame = null
        pendingAudio = null
        state = PhoneMachineState.READY
        PhoneMachineStep(state, false, false, "RM-614 firmware loaded; CPU is ready.")
    }

    override fun runSlice(budgetMicros: Long): PhoneMachineStep = onNativeThread {
        if (state == PhoneMachineState.PAUSED) {
            return@onNativeThread PhoneMachineStep(state, false, pendingAudio?.isNotEmpty() == true)
        }
        check(state == PhoneMachineState.READY || state == PhoneMachineState.RUNNING) {
            "The C3-00 machine is not ready to run (state=$state)."
        }

        nextWaitMillis = C3EmuNative.nativeRun(
            budgetMicros.coerceIn(250L, 50_000L) / 1000.0
        )
        if (!nextWaitMillis.isFinite() || nextWaitMillis < 0.0) {
            val diagnostic = C3EmuNative.nativeResultText()
                .ifBlank { "c3emu остановил эмулятор без диагностического сообщения." }
            state = PhoneMachineState.FAILED
            persistDirtyStorageIfDue(force = true)
            return@onNativeThread PhoneMachineStep(state, false, false, diagnostic)
        }
        state = PhoneMachineState.RUNNING

        // Upstream c3emu-web sends at most one frame every 15 ms. Match that
        // cadence so Android does not copy JNI frame buffers faster than the LCD
        // can visibly refresh. Keep the old serial until an actual frame is read.
        val nextSerial = C3EmuNative.nativeFrameSerial()
        val now = SystemClock.elapsedRealtime()
        var frameChanged = false
        if (nextSerial != frameSerial && now - lastFrameDeliveryAtMs >= FRAME_DELIVERY_INTERVAL_MS) {
            val pixels = C3EmuNative.nativeFrame()
            if (pixels.size == FRAME_BYTES) {
                cachedFrame = PhoneMachineFrame(
                    width = LCD_WIDTH,
                    height = LCD_HEIGHT,
                    rowStrideBytes = LCD_WIDTH * 4,
                    pixelFormat = FramePixelFormat.RGBA_8888,
                    pixels = pixels,
                    frameNumber = nextSerial.toLong() and 0xFFFF_FFFFL
                )
                frameSerial = nextSerial
                lastFrameDeliveryAtMs = now
                frameChanged = true
            }
        }

        val audio = C3EmuNative.nativeDrainAudio()
        if (audio.isNotEmpty()) {
            val previous = pendingAudio
            pendingAudio = if (previous == null || previous.isEmpty()) audio else previous + audio
        }
        persistDirtyStorageIfDue()
        PhoneMachineStep(state, frameChanged, pendingAudio?.isNotEmpty() == true)
    }

    override fun sendKey(key: CanonicalKey, pressed: Boolean) {
        matrixIndexFor(key)?.let { sendMatrixKey(it, pressed) }
    }

    /**
     * Run, fetch the changed LCD frame and drain audio in one queue operation.
     * This keeps the thread-local native session on its single owner thread and
     * avoids a second executor hop for every 12 ms emulator slice.
     */
    fun runCycleAsync(
        budgetMicros: Long,
        callback: (Result<C3EmuCycle>) -> Unit
    ) {
        executor.execute {
            nativeThread = Thread.currentThread()
            val result = runCatching {
                val step = runSlice(budgetMicros)
                val frame = if (step.frameChanged) latestFrame() else null
                val audio = drainAudio()
                C3EmuCycle(step, frame, audio, nextWaitMillis)
            }
            callback(result)
        }
    }

    /** Queue touch/key edges directly on the native session's owner thread. */
    fun sendMatrixKeyAsync(index: Int, pressed: Boolean) {
        if (index !in 0..255 || closed || state !in setOf(
                PhoneMachineState.READY,
                PhoneMachineState.RUNNING,
                PhoneMachineState.PAUSED
            )
        ) return
        executor.execute {
            nativeThread = Thread.currentThread()
            if (!closed && state in setOf(
                    PhoneMachineState.READY,
                    PhoneMachineState.RUNNING,
                    PhoneMachineState.PAUSED
                )
            ) {
                C3EmuNative.nativeKey(index, if (pressed) 1 else 0)
            }
        }
    }

    /** Send one C3-00 physical keyboard matrix index. */
    fun sendMatrixKey(index: Int, pressed: Boolean) {
        if (index !in 0..255 || state !in setOf(
                PhoneMachineState.READY,
                PhoneMachineState.RUNNING,
                PhoneMachineState.PAUSED
            )
        ) return
        onNativeThread { C3EmuNative.nativeKey(index, if (pressed) 1 else 0) }
    }

    override fun latestFrame(): PhoneMachineFrame? = cachedFrame

    override fun drainAudio(): PhoneMachineAudio? = onNativeThread {
        val samples = pendingAudio ?: return@onNativeThread null
        pendingAudio = null
        if (samples.isEmpty()) null else PhoneMachineAudio(
            sampleRateHz = AUDIO_SAMPLE_RATE,
            channelCount = AUDIO_CHANNELS,
            samples = samples
        )
    }

    override fun pause() {
        if (state == PhoneMachineState.RUNNING || state == PhoneMachineState.READY) {
            state = PhoneMachineState.PAUSED
            if (!closed) {
                executor.execute {
                    nativeThread = Thread.currentThread()
                    runCatching { persistDirtyStorageIfDue(force = true) }
                }
            }
        }
    }

    override fun resume() {
        if (state == PhoneMachineState.PAUSED) state = PhoneMachineState.RUNNING
    }

    override fun stop() {
        if (closed) return
        if (state != PhoneMachineState.STOPPED) {
            runCatching {
                onNativeThread {
                    persistDirtyStorageIfDue(force = true)
                    C3EmuNative.nativeStop()
                }
            }
            state = PhoneMachineState.STOPPED
            cachedFrame = null
            pendingAudio = null
        }
    }

    override fun close() {
        if (closed) return
        stop()
        closed = true
        executor.shutdown()
    }

    private fun restorePersistentStorage(directory: File) {
        C3EmuNative.nativeClearDisks()
        directory.mkdirs()
        val sizes = directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.matches(Regex("dev_[0-9]+\\.size")) }
            .mapNotNull { file ->
                val dev = file.name.removePrefix("dev_").removeSuffix(".size").toIntOrNull()
                val size = file.readText().trim().toLongOrNull()
                if (dev == null || size == null || size < 0L) null else dev to size
            }
        sizes.forEach { (dev, size) -> C3EmuNative.nativeSetDiskSize(dev, size) }

        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.matches(Regex("dev_[0-9]+_[0-9]+\\.chunk")) }
            .forEach { file ->
                val parts = file.name.removeSuffix(".chunk").split('_')
                val dev = parts.getOrNull(1)?.toIntOrNull() ?: return@forEach
                val offset = parts.getOrNull(2)?.toLongOrNull() ?: return@forEach
                val size = sizes.firstOrNull { it.first == dev }?.second ?: return@forEach
                val chunk = file.readBytes()
                if (chunk.size == CHUNK_BYTES) {
                    C3EmuNative.nativeRestoreDiskChunk(dev, offset.toDouble(), size, chunk)
                        .requireNativeSuccess("restore flash chunk")
                }
            }
    }

    private fun persistDirtyStorageIfDue(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastStorageFlushAtMs < STORAGE_FLUSH_INTERVAL_MS) return
        persistDirtyStorage()
        lastStorageFlushAtMs = now
    }

    private fun persistDirtyStorage() {
        val directory = storageDirectory ?: return
        val encoded = C3EmuNative.nativeDrainDirtyDisks()
        if (encoded.isEmpty()) return
        directory.mkdirs()
        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.hasRemaining()) {
            require(buffer.remaining() >= DISK_RECORD_HEADER_BYTES) {
                "Native flash journal contains a truncated record header."
            }
            val device = buffer.int
            val offsetValue = buffer.double
            val flags = buffer.int
            val diskSize = buffer.long
            require(device >= 0 && offsetValue.isFinite() && offsetValue >= 0.0 && diskSize >= 0L) {
                "Native flash journal contains invalid metadata."
            }
            val offset = offsetValue.toLong()
            require(offsetValue == offset.toDouble()) {
                "Native flash journal contains a non-integral chunk offset."
            }

            File(directory, "dev_${device}.size").writeText(diskSize.toString())
            val chunkFile = File(directory, "dev_${device}_${offset}.chunk")
            if ((flags and 1) != 0) {
                require(buffer.remaining() >= CHUNK_BYTES) {
                    "Native flash journal contains a truncated 64 KiB chunk."
                }
                val data = ByteArray(CHUNK_BYTES)
                buffer.get(data)
                val staging = File(directory, ".${chunkFile.name}.tmp")
                staging.writeBytes(data)
                if (chunkFile.exists() && !chunkFile.delete()) {
                    staging.delete()
                    error("Unable to replace saved C: storage chunk.")
                }
                check(staging.renameTo(chunkFile)) { "Unable to commit saved C: storage chunk." }
            } else if (chunkFile.exists()) {
                check(chunkFile.delete()) { "Unable to remove cleared C: storage chunk." }
            }
        }
    }

    private fun String.requireNativeSuccess(operation: String) {
        if (isNotBlank()) error("c3emu $operation failed: $this")
    }

    companion object {
        const val LCD_WIDTH = 320
        const val LCD_HEIGHT = 240
        const val AUDIO_SAMPLE_RATE = 44_100
        const val AUDIO_CHANNELS = 2
        private const val FRAME_BYTES = LCD_WIDTH * LCD_HEIGHT * 4
        private const val CHUNK_BYTES = 64 * 1024
        private const val DISK_RECORD_HEADER_BYTES = 4 + 8 + 4 + 8
        private const val STORAGE_FLUSH_INTERVAL_MS = 1_000L
        private const val FRAME_DELIVERY_INTERVAL_MS = 15L

        /** C3-00 matrix positions documented in the upstream c3emu KEYMAP. */
        fun matrixIndexFor(key: CanonicalKey): Int? = when (key) {
            CanonicalKey.UP -> C3KeyMatrix.UP
            CanonicalKey.DOWN -> C3KeyMatrix.DOWN
            CanonicalKey.LEFT -> C3KeyMatrix.LEFT
            CanonicalKey.RIGHT -> C3KeyMatrix.RIGHT
            CanonicalKey.SELECT -> C3KeyMatrix.SELECT
            CanonicalKey.SOFT_LEFT -> C3KeyMatrix.SOFT_LEFT
            CanonicalKey.SOFT_RIGHT -> C3KeyMatrix.SOFT_RIGHT
            CanonicalKey.CALL -> C3KeyMatrix.CALL
            CanonicalKey.END -> C3KeyMatrix.END
            CanonicalKey.BACK -> 34
            CanonicalKey.DIGIT_1 -> 3
            CanonicalKey.DIGIT_2 -> 4
            CanonicalKey.DIGIT_3 -> 5
            CanonicalKey.DIGIT_4 -> 11
            CanonicalKey.DIGIT_5 -> 12
            CanonicalKey.DIGIT_6 -> 13
            CanonicalKey.DIGIT_7 -> 19
            CanonicalKey.DIGIT_8 -> 20
            CanonicalKey.DIGIT_9 -> 21
            CanonicalKey.DIGIT_0 -> 22
            CanonicalKey.STAR -> 28
            CanonicalKey.HASH -> 45
        }

        fun matrixIndexForCharacter(value: Char): Int? = when (value.lowercaseChar()) {
            'q' -> 0; 'w' -> 1; 'e' -> 2; 'r' -> 3; 't' -> 4
            'y' -> 5; 'u' -> 6; 'i' -> 24; 'o' -> 25; 'p' -> 26
            'a' -> 8; 's' -> 9; 'd' -> 10; 'f' -> 11; 'g' -> 12
            'h' -> 13; 'j' -> 14; 'k' -> 32; 'l' -> 33
            'z' -> 16; 'x' -> 17; 'c' -> 18; 'v' -> 19; 'b' -> 20
            'n' -> 21; 'm' -> 22
            ' ' -> 49; '.' -> 28; ',' -> 45; '-' -> 40; '=' -> 41
            else -> null
        }
    }
}

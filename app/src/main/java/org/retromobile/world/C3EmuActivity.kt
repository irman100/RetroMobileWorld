package org.retromobile.world

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Nokia C3-00 / RM-614. The device photograph is the complete physical shell;
 * the guest LCD and all key input are rendered/hit-tested in that same geometry.
 */
class C3EmuActivity : Activity() {
    private lateinit var library: C3FirmwareLibrary
    private lateinit var deviceView: C3DeviceView
    private lateinit var menuView: TextView
    private var pendingPickerKind: FirmwareImageKind? = null

    private val engine = C3EmuEngine()
    private var hostClockReceiverRegistered = false
    private val hostClockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            syncHostClock()
        }
    }
    private val fileExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "c3emu-files").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    // Pixel conversion and Bitmap updates must not run on Android's input/UI thread.
    private val frameExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "c3emu-frame-render").apply { isDaemon = true }
    }
    // Keep blocking AudioTrack writes off the single native-emulation worker.
    // A small bounded queue drops stale audio rather than slowing CPU execution.
    private val audioExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(4),
        { task -> Thread(task, "c3emu-audio").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var runningLoop = false
    @Volatile private var destroyed = false
    private val pendingFrame = AtomicReference<PhoneMachineFrame?>()
    private val frameRenderScheduled = AtomicBoolean(false)
    private val frameRenderRunnable = Runnable {
        val frame = pendingFrame.getAndSet(null)
        if (!destroyed) frame?.let(deviceView::showFrame)
        frameRenderScheduled.set(false)
        if (pendingFrame.get() != null) scheduleFrameRender()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        library = C3FirmwareLibrary(this)
        buildDeviceUi()
        registerHostClockReceiver()
        syncHostClock()
        refreshFirmwareStatus()
    }

    private fun registerHostClockReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
        }
        ContextCompat.registerReceiver(
            this,
            hostClockReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        hostClockReceiverRegistered = true
    }

    private fun syncHostClock() {
        val nowMillis = System.currentTimeMillis()
        val zone = TimeZone.getDefault()
        val offsetSeconds = zone.getOffset(nowMillis).toLong() / 1000L
        val unixSeconds = nowMillis / 1000L
        Log.i(
            "C3EmuClock",
            "hostClock unix=$unixSeconds zone=${zone.id} offsetSeconds=$offsetSeconds"
        )
        engine.requestHostClockSync(unixSeconds, offsetSeconds)
    }

    private fun buildDeviceUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(13, 16, 19))
        }

        deviceView = C3DeviceView(this).apply {
            onMatrixKey = { index, pressed ->
                engine.sendMatrixKeyAsync(index, pressed)
            }
        }
        root.addView(
            deviceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // One unobtrusive menu replaces the debug/import/keypad button wall.
        menuView = TextView(this).apply {
            text = "⋮"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(190, 18, 25, 31))
                setStroke(dp(1), Color.argb(120, 230, 240, 245))
            }
            elevation = dp(8).toFloat()
            contentDescription = "Меню Nokia C3-00"
            setOnClickListener { showDeviceMenu() }
        }
        root.addView(
            menuView,
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(12), dp(12), 0)
            }
        )
        setContentView(root)
    }

    private fun showDeviceMenu() {
        PopupMenu(this, menuView).apply {
            menu.add(Menu.NONE, MENU_SELECT_PHONE, Menu.NONE, "Выбрать телефон…")
            menu.add(Menu.NONE, MENU_IMPORT_ZIP, Menu.NONE, "Импорт Phoenix ZIP (RM-614)…")
            menu.add(Menu.NONE, MENU_IMPORT_CORE, Menu.NONE, "Импорт MCUSW отдельно…")
            menu.add(Menu.NONE, MENU_IMPORT_PPM, Menu.NONE, "Импорт PPM отдельно…")
            menu.add(Menu.NONE, MENU_IMPORT_IMAGE, Menu.NONE, "Импорт IMAGE отдельно…")
            menu.add(Menu.NONE, MENU_BOOT, Menu.NONE, "Запустить / перезапустить")
            menu.add(Menu.NONE, MENU_STOP, Menu.NONE, "Остановить и сохранить")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_SELECT_PHONE -> {
                        showPhonePicker()
                        true
                    }
                    MENU_IMPORT_ZIP -> {
                        openPicker(null)
                        true
                    }
                    MENU_IMPORT_CORE -> {
                        openPicker(FirmwareImageKind.CORE)
                        true
                    }
                    MENU_IMPORT_PPM -> {
                        openPicker(FirmwareImageKind.LANGUAGE_PACK)
                        true
                    }
                    MENU_IMPORT_IMAGE -> {
                        openPicker(FirmwareImageKind.CONTENT_PACK)
                        true
                    }
                    MENU_BOOT -> {
                        bootFirmware()
                        true
                    }
                    MENU_STOP -> {
                        stopFirmware()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun showPhonePicker() {
        val phones = arrayOf(
            "Nokia C3-00 · RM-614 — доступен",
            "Nokia 6300 · RM-217 — эмулятор ещё не готов"
        )
        AlertDialog.Builder(this)
            .setTitle("Выбрать телефон")
            .setSingleChoiceItems(phones, 0) { dialog, which ->
                dialog.dismiss()
                if (which == 1) {
                    showMessage(
                        "Nokia 6300",
                        "Эмулятор RM-217 пока находится в разработке. " +
                            "Текущая сессия Nokia C3-00 не затронута."
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sendKey(index: Int, pressed: Boolean) {
        engine.sendMatrixKeyAsync(index, pressed)
    }

    private fun openPicker(kind: FirmwareImageKind?) {
        pendingPickerKind = kind
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/octet-stream"
                )
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQUEST_IMPORT_C3_FIRMWARE)
        } catch (error: ActivityNotFoundException) {
            pendingPickerKind = null
            showMessage("Не удалось открыть системный выбор файлов.")
        }
    }

    @Deprecated("Uses the platform activity-result callback for broad Android compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT_C3_FIRMWARE) return
        if (resultCode != RESULT_OK) {
            pendingPickerKind = null
            return
        }
        val uri = data?.data ?: run {
            pendingPickerKind = null
            return
        }
        val displayName = queryDisplayName(uri)
        val selectedKind = pendingPickerKind
        pendingPickerKind = null
        menuView.isEnabled = false
        showMessage("Импортирую $displayName…")

        fileExecutor.execute {
            val result = runCatching {
                if (displayName.endsWith(".zip", ignoreCase = true)) {
                    require(selectedKind == null) {
                        "Для импорта отдельного компонента выбери сам файл, а не ZIP."
                    }
                    library.importArchiveFromUri(uri, displayName)
                } else {
                    val record = library.importComponentFromUri(uri, displayName)
                    require(selectedKind == null || record.metadata.kind == selectedKind) {
                        "Выбран файл другого типа. Проверь расширение."
                    }
                    listOf(record)
                }
            }
            runOnUiThread {
                menuView.isEnabled = true
                result.onSuccess { records ->
                    val imageImported = records.any { it.metadata.kind == FirmwareImageKind.CONTENT_PACK }
                    val message = when {
                        imageImported && hasPersistedCDrive() ->
                            "IMAGE импортирован. Существующая память C: сохранена; новый IMAGE не перезаписывает её автоматически."
                        imageImported ->
                            "IMAGE импортирован. Его содержимое C: будет установлено при первом создании памяти телефона."
                        else -> "Импортировано компонентов: " + records.size
                    }
                    showMessage(message)
                    refreshFirmwareStatus()
                }.onFailure { error ->
                    showMessage("Ошибка импорта: " + (error.message ?: error.javaClass.simpleName))
                    refreshFirmwareStatus()
                }
            }
        }
    }

    private fun bootFirmware() {
        menuView.isEnabled = false
        showMessage("Запускаю Nokia Series 40…")
        fileExecutor.execute {
            val result = runCatching {
                val selection = library.selectFirmware()
                    ?: error("Импортируй Phoenix ZIP или отдельный RM-614 MCUSW.")
                val step = engine.boot(selection.firmware, File(filesDir, "c3emu-storage"))
                selection to step
            }
            runOnUiThread {
                menuView.isEnabled = true
                result.onSuccess { (selection, step) ->
                    if (step.state == PhoneMachineState.FAILED) {
                        showMessage("Загрузка не удалась: " + (step.diagnostic ?: "ошибка"))
                    } else {
                        startAudio()
                        startRunLoop()
                        val variant = selection.variant?.uppercase()
                        val warning = selection.warnings.firstOrNull()
                        showMessage(
                            warning ?: if (variant != null) {
                                "Nokia C3-00 загружается, комплект $variant"
                            } else {
                                "Nokia C3-00 загружается"
                            }
                        )
                    }
                }.onFailure { error ->
                    showMessage("Ошибка запуска: " + (error.message ?: error.javaClass.simpleName))
                }
            }
        }
    }

    private fun hasPersistedCDrive(): Boolean =
        File(filesDir, "c3emu-storage").listFiles().orEmpty().any { file ->
            file.isFile && (file.name == "dev_0.size" ||
                file.name.matches(Regex("dev_0_[0-9]+\\.chunk")))
        }

    private fun stopFirmware() {
        stopRunLoop()
        fileExecutor.execute {
            engine.stop()
            runOnUiThread {
                stopAudio()
                if (!destroyed) showMessage("Эмулятор остановлен. Память сохранена.")
            }
        }
    }

    private fun postFrame(frame: PhoneMachineFrame) {
        pendingFrame.set(frame)
        scheduleFrameRender()
    }

    private fun scheduleFrameRender() {
        if (destroyed || !frameRenderScheduled.compareAndSet(false, true)) return
        runCatching { frameExecutor.execute(frameRenderRunnable) }
            .onFailure { frameRenderScheduled.set(false) }
    }

    private fun startRunLoop() {
        if (runningLoop || destroyed) return
        runningLoop = true
        engine.startRealtimeLoop(CPU_BUDGET_MICROS) { result ->
            result.onSuccess { cycle ->
                // Audio stays off the UI thread. Frames are coalesced so a slow
                // renderer never builds a queue of stale LCD images.
                cycle.audio?.samples?.let(::queueAudio)
                cycle.frame?.let(::postFrame)
                if (cycle.step.state == PhoneMachineState.FAILED) {
                    runningLoop = false
                    mainHandler.post {
                        if (!destroyed) {
                            stopAudio()
                            showMessage(
                            "Гостевая Nokia остановилась. Память C: сохранена. " +
                                "Открой меню и выбери «Запустить / перезапустить». " +
                                (cycle.step.diagnostic ?: "ошибка")
                        )
                        }
                    }
                }
            }.onFailure { error ->
                runningLoop = false
                mainHandler.post {
                    if (!destroyed) {
                        stopAudio()
                        showMessage("Ошибка эмулятора: " + (error.message ?: error.javaClass.simpleName))
                    }
                }
            }
        }
    }

    private fun stopRunLoop() {
        runningLoop = false
        engine.stopRealtimeLoop()
    }

    private fun queueAudio(samples: ShortArray) {
        if (samples.isEmpty()) return
        val track = audioTrack ?: return
        runCatching {
            audioExecutor.execute {
                var offset = 0
                while (offset < samples.size) {
                    val written = track.write(
                        samples,
                        offset,
                        samples.size - offset,
                        AudioTrack.WRITE_BLOCKING
                    )
                    if (written <= 0) break
                    offset += written
                }
            }
        }
    }

    private fun startAudio() {
        stopAudio()
        val minimum = AudioTrack.getMinBufferSize(
            C3EmuEngine.AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(C3EmuEngine.AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minimum * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        track.play()
    }

    private fun stopAudio() {
        val track = audioTrack ?: return
        audioTrack = null
        runCatching {
            audioExecutor.execute {
                runCatching {
                    track.pause()
                    track.flush()
                    track.stop()
                }
                track.release()
            }
        }.onFailure {
            runCatching { track.release() }
        }
    }

    private fun refreshFirmwareStatus() {
        fileExecutor.execute {
            val hasCore = library.find(FirmwareImageKind.CORE) != null
            runOnUiThread {
                if (destroyed || isFinishing) return@runOnUiThread
                menuView.contentDescription = if (hasCore) {
                    "Меню Nokia C3-00. Прошивка импортирована."
                } else {
                    "Меню Nokia C3-00. Требуется импорт Phoenix ZIP."
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        var displayName: String? = null
        val cursor: Cursor? = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val column = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) displayName = it.getString(column)
            }
        }
        return displayName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "rm614-firmware.zip"
    }

    private fun showMessage(message: String) {
        if (!destroyed) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onPause() {
        stopRunLoop()
        if (engine.state == PhoneMachineState.RUNNING || engine.state == PhoneMachineState.READY) {
            engine.pause()
        }
        runCatching { audioTrack?.pause() }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        syncHostClock()
        if (engine.state == PhoneMachineState.PAUSED) engine.resume()
        if (engine.state == PhoneMachineState.RUNNING || engine.state == PhoneMachineState.READY) {
            runCatching { audioTrack?.play() }
            startRunLoop()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        stopRunLoop()
        fileExecutor.execute { engine.stop() }
        super.onBackPressed()
    }

    override fun onDestroy() {
        destroyed = true
        if (hostClockReceiverRegistered) {
            runCatching { unregisterReceiver(hostClockReceiver) }
            hostClockReceiverRegistered = false
        }
        stopRunLoop()
        pendingFrame.set(null)
        stopAudio()
        frameExecutor.shutdown()
        audioExecutor.shutdown()
        fileExecutor.execute { engine.close() }
        fileExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_IMPORT_C3_FIRMWARE = 6140
        private const val MENU_SELECT_PHONE = 7
        private const val MENU_IMPORT_ZIP = 1
        private const val MENU_IMPORT_CORE = 2
        private const val MENU_IMPORT_PPM = 3
        private const val MENU_IMPORT_IMAGE = 4
        private const val MENU_BOOT = 5
        private const val MENU_STOP = 6
        // c3emu upstream web runner uses a 16 ms slice.
        private const val CPU_BUDGET_MICROS = 16_000L
    }
}

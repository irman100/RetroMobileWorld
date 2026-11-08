package org.retromobile.world

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
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
import android.view.Gravity
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.ceil

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
    private val fileExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "c3emu-files").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var audioTrack: AudioTrack? = null
    private var runningLoop = false
    private var cycleInFlight = false
    private var destroyed = false

    private val loopRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!runningLoop || destroyed || cycleInFlight) return
            cycleInFlight = true
            engine.runCycleAsync(CPU_BUDGET_MICROS) { result ->
                // Audio is handed to AudioTrack away from the UI thread.
                result.getOrNull()?.audio?.samples?.let { samples ->
                    if (samples.isNotEmpty()) {
                        runCatching {
                            audioTrack?.write(
                                samples,
                                0,
                                samples.size,
                                AudioTrack.WRITE_BLOCKING
                            )
                        }
                    }
                }
                runOnUiThread {
                    cycleInFlight = false
                    if (destroyed) return@runOnUiThread

                    result.onSuccess { cycle ->
                        cycle.frame?.let(deviceView::showFrame)
                        if (cycle.step.state == PhoneMachineState.FAILED) {
                            stopRunLoop()
                            stopAudio()
                            showMessage("Эмулятор остановлен: " + (cycle.step.diagnostic ?: "ошибка"))
                        } else if (runningLoop) {
                            val delay = if (cycle.waitMillis > 0.0) {
                                ceil(cycle.waitMillis).toLong().coerceIn(1L, 1000L)
                            } else {
                                0L
                            }
                            mainHandler.postDelayed(loopRunnable, delay)
                        }
                    }.onFailure { error ->
                        stopRunLoop()
                        showMessage("Ошибка эмулятора: " + (error.message ?: error.javaClass.simpleName))
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        library = C3FirmwareLibrary(this)
        buildDeviceUi()
        refreshFirmwareStatus()
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
            menu.add(Menu.NONE, MENU_IMPORT_ZIP, Menu.NONE, "Импорт Phoenix ZIP (RM-614)…")
            menu.add(Menu.NONE, MENU_IMPORT_CORE, Menu.NONE, "Импорт MCUSW отдельно…")
            menu.add(Menu.NONE, MENU_IMPORT_PPM, Menu.NONE, "Импорт PPM отдельно…")
            menu.add(Menu.NONE, MENU_IMPORT_IMAGE, Menu.NONE, "Импорт IMAGE отдельно…")
            menu.add(Menu.NONE, MENU_BOOT, Menu.NONE, "Запустить / перезапустить")
            menu.add(Menu.NONE, MENU_STOP, Menu.NONE, "Остановить и сохранить")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
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
                    showMessage("Импортировано компонентов: " + records.size)
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
                val core = library.find(FirmwareImageKind.CORE)
                    ?: error("Сначала импортируй Phoenix ZIP для Nokia C3-00 / RM-614.")
                val firmware = PhoneFirmwareSet(
                    core = core,
                    languagePack = library.find(FirmwareImageKind.LANGUAGE_PACK),
                    contentPack = library.find(FirmwareImageKind.CONTENT_PACK)
                )
                engine.boot(firmware, File(filesDir, "c3emu-storage"))
            }
            runOnUiThread {
                menuView.isEnabled = true
                result.onSuccess { step ->
                    if (step.state == PhoneMachineState.FAILED) {
                        showMessage("Загрузка не удалась: " + (step.diagnostic ?: "ошибка"))
                    } else {
                        startAudio()
                        startRunLoop()
                        showMessage("Nokia C3-00 загружается")
                    }
                }.onFailure { error ->
                    showMessage("Ошибка запуска: " + (error.message ?: error.javaClass.simpleName))
                }
            }
        }
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

    private fun startRunLoop() {
        if (runningLoop) return
        runningLoop = true
        mainHandler.removeCallbacks(loopRunnable)
        mainHandler.post(loopRunnable)
    }

    private fun stopRunLoop() {
        runningLoop = false
        mainHandler.removeCallbacks(loopRunnable)
    }

    private fun startAudio() {
        stopAudio()
        val minimum = AudioTrack.getMinBufferSize(
            C3EmuEngine.AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        audioTrack = AudioTrack.Builder()
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
            .apply { play() }
    }

    private fun stopAudio() {
        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        }
        audioTrack = null
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
        stopRunLoop()
        stopAudio()
        fileExecutor.execute { engine.close() }
        fileExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_IMPORT_C3_FIRMWARE = 6140
        private const val MENU_IMPORT_ZIP = 1
        private const val MENU_IMPORT_CORE = 2
        private const val MENU_IMPORT_PPM = 3
        private const val MENU_IMPORT_IMAGE = 4
        private const val MENU_BOOT = 5
        private const val MENU_STOP = 6
        private const val CPU_BUDGET_MICROS = 12_000L
    }
}

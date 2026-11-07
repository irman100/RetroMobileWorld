package org.retromobile.world

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dedicated Nokia C3-00 / RM-614 emulator screen. Its landscape LCD and QWERTY
 * keypad are intentionally separate from the RM-217 candybar profile.
 */
class C3EmuActivity : Activity() {
    private lateinit var library: C3FirmwareLibrary
    private lateinit var statusView: TextView
    private lateinit var screenView: ImageView
    private lateinit var bootButton: Button
    private lateinit var importZipButton: Button
    private lateinit var importCoreButton: Button
    private lateinit var importPpmButton: Button
    private lateinit var importImageButton: Button

    private val engine = C3EmuEngine()
    private val workExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "c3emu-android-ui").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val runInFlight = AtomicBoolean(false)
    private var audioTrack: AudioTrack? = null
    private var latestBitmap: Bitmap? = null
    private var pendingPickerKind: FirmwareImageKind? = null
    private var runningLoop = false
    private var destroyed = false

    private val loopRunnable = object : Runnable {
        override fun run() {
            if (!runningLoop || destroyed) return
            if (runInFlight.compareAndSet(false, true)) {
                workExecutor.execute {
                    try {
                        val step = engine.runSlice(12_000)
                        val frame = if (step.frameChanged) engine.latestFrame() else null
                        val audio = engine.drainAudio()
                        if (audio != null && audio.samples.isNotEmpty()) {
                            audioTrack?.write(
                                audio.samples,
                                0,
                                audio.samples.size,
                                AudioTrack.WRITE_BLOCKING
                            )
                        }
                        runOnUiThread {
                            frame?.let(::renderFrame)
                            if (step.state == PhoneMachineState.FAILED) {
                                stopRunLoop()
                                setImportControlsEnabled(true)
                                bootButton.isEnabled = library.find(FirmwareImageKind.CORE) != null
                                statusView.text = "ОСТАНОВЛЕНО: ${step.diagnostic ?: "ошибка эмулятора"}"
                            } else if (step.state == PhoneMachineState.RUNNING) {
                                statusView.text = "NOKIA C3-00 · SERIES 40 · ${engine.id}"
                            }
                        }
                    } catch (error: Exception) {
                        runOnUiThread {
                            stopRunLoop()
                            setImportControlsEnabled(true)
                            statusView.text = "ОШИБКА: ${error.message ?: error.javaClass.simpleName}"
                        }
                    } finally {
                        runInFlight.set(false)
                    }
                }
            }
            mainHandler.postDelayed(this, 16L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = C3FirmwareLibrary(this)
        buildUi()
        refreshFirmwareStatus()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 15, 22))
            setPadding(dp(10), dp(8), dp(10), dp(12))
        }

        val header = TextView(this).apply {
            text = "NOKIA C3-00  ·  RM-614"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(2))
        }
        root.addView(header, matchWrap())

        val subtitle = TextView(this).apply {
            text = "Series 40 · ARM926EJ-S · 320 × 240"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(subtitle, matchWrap())

        screenView = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "Nokia C3-00 emulated LCD"
        }
        root.addView(screenView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(205)
        ))

        statusView = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.rgb(176, 206, 222))
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(8))
        }
        root.addView(statusView, matchWrap())

        importZipButton = actionButton("ИМПОРТ PHOENIX ZIP (RM-614)") {
            pendingPickerKind = null
            openPicker()
        }
        root.addView(importZipButton, matchWrap())

        val componentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        importCoreButton = compactButton("MCUSW") {
            pendingPickerKind = FirmwareImageKind.CORE
            openPicker()
        }
        importPpmButton = compactButton("PPM") {
            pendingPickerKind = FirmwareImageKind.LANGUAGE_PACK
            openPicker()
        }
        importImageButton = compactButton("IMAGE") {
            pendingPickerKind = FirmwareImageKind.CONTENT_PACK
            openPicker()
        }
        listOf(importCoreButton, importPpmButton, importImageButton).forEach {
            componentRow.addView(it, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                setMargins(dp(2), dp(4), dp(2), dp(4))
            })
        }
        root.addView(componentRow, matchWrap())

        bootButton = actionButton("ЗАПУСТИТЬ SERIES 40") { bootFirmware() }
        root.addView(bootButton, matchWrap())

        val controlLabel = TextView(this).apply {
            text = "КЛАВИАТУРА C3-00"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
        }
        root.addView(controlLabel, matchWrap())

        addKeyRow(root, listOf(
            "Q" to 0, "W" to 1, "E" to 2, "R" to 3, "T" to 4,
            "Y" to 5, "U" to 6, "I" to 24, "O" to 25, "P" to 26
        ))
        addKeyRow(root, listOf(
            "A" to 8, "S" to 9, "D" to 10, "F" to 11, "G" to 12,
            "H" to 13, "J" to 14, "K" to 32, "L" to 33
        ))
        addKeyRow(root, listOf(
            "Z" to 16, "X" to 17, "C" to 18, "V" to 19,
            "B" to 20, "N" to 21, "M" to 22, "⌫" to 34
        ))
        addKeyRow(root, listOf(
            "SHIFT" to 35, "SYM" to 48, "SPACE" to 49, "FN" to 50, "ENTER" to 42
        ))

        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(
            "CALL" to 30, "↑" to 56, "↓" to 57, "←" to 58,
            "OK" to 60, "→" to 59, "END" to 255
        ).forEach { (label, index) ->
            navRow.addView(keyButton(label, index), LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                setMargins(dp(2), dp(3), dp(2), dp(3))
            })
        }
        root.addView(navRow, matchWrap())

        val softRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf("LEFT" to 29, "CONTACTS" to 38, "MESSAGES" to 52, "RIGHT" to 43).forEach { (label, index) ->
            softRow.addView(keyButton(label, index), LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        root.addView(softRow, matchWrap())

        val stopButton = compactButton("СТОП / ПАУЗА") {
            stopRunLoop()
            workExecutor.execute {
                engine.stop()
                runOnUiThread {
                    stopAudio()
                    setImportControlsEnabled(true)
                    bootButton.isEnabled = library.find(FirmwareImageKind.CORE) != null
                    statusView.text = "ЭМУЛЯТОР ОСТАНОВЛЕН · ВИРТУАЛЬНАЯ ПАМЯТЬ СОХРАНЕНА"
                }
            }
        }
        root.addView(stopButton, matchWrap())

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(root)
        }
        setContentView(scroll)
    }

    private fun addKeyRow(parent: LinearLayout, keys: List<Pair<String, Int>>) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        keys.forEach { (label, index) ->
            row.addView(keyButton(label, index), LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                setMargins(dp(1), dp(2), dp(1), dp(2))
            })
        }
        parent.addView(row, matchWrap())
    }

    private fun keyButton(label: String, matrixIndex: Int): Button =
        Button(this).apply {
            text = label
            textSize = when {
                label.length > 5 -> 7f
                label.length > 2 -> 8f
                else -> 11f
            }
            minWidth = 0
            minHeight = 0
            setPadding(dp(1), 0, dp(1), 0)
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(38, 58, 72))
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        sendKey(matrixIndex, true)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        sendKey(matrixIndex, false)
                        true
                    }
                    else -> true
                }
            }
        }

    private fun actionButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 11f
            setOnClickListener { action() }
        }

    private fun compactButton(label: String, action: () -> Unit): Button =
        actionButton(label, action).apply { textSize = 9f }

    private fun sendKey(index: Int, pressed: Boolean) {
        if (engine.state !in setOf(
                PhoneMachineState.READY,
                PhoneMachineState.RUNNING,
                PhoneMachineState.PAUSED
            )
        ) return
        workExecutor.execute {
            runCatching { engine.sendMatrixKey(index, pressed) }
        }
    }

    private fun openPicker() {
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
            statusView.text = "Не удалось открыть системный выбор файлов."
        }
    }

    @Deprecated("Uses the platform activity-result callback for broad Android compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT_C3_FIRMWARE) return
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val displayName = queryDisplayName(uri)
        val selectedKind = pendingPickerKind
        setImportControlsEnabled(false)
        bootButton.isEnabled = false
        statusView.text = "Импортирую ${displayName}…"

        workExecutor.execute {
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
                setImportControlsEnabled(true)
                result.onSuccess { records ->
                    statusView.text = "Импортировано: ${records.joinToString { it.metadata.originalFileName }}"
                    refreshFirmwareStatus()
                }.onFailure { error ->
                    statusView.text = "ОШИБКА ИМПОРТА: ${error.message}"
                    refreshFirmwareStatus()
                }
            }
        }
    }

    private fun bootFirmware() {
        bootButton.isEnabled = false
        setImportControlsEnabled(false)
        statusView.text = "ПРОВЕРЯЮ КОМПОНЕНТЫ RM-614…"

        workExecutor.execute {
            val result = runCatching {
                val core = library.find(FirmwareImageKind.CORE)
                    ?: error("Сначала загрузи RM-614 MCUSW или Phoenix ZIP.")
                val firmware = PhoneFirmwareSet(
                    core = core,
                    languagePack = library.find(FirmwareImageKind.LANGUAGE_PACK),
                    contentPack = library.find(FirmwareImageKind.CONTENT_PACK)
                )
                engine.boot(firmware, File(filesDir, "c3emu-storage"))
            }
            runOnUiThread {
                bootButton.isEnabled = true
                result.onSuccess { step ->
                    if (step.state == PhoneMachineState.FAILED) {
                        statusView.text = "ЗАГРУЗКА НЕ УДАЛАСЬ: ${step.diagnostic}"
                        setImportControlsEnabled(true)
                    } else {
                        statusView.text = "ПРОШИВКА ПРИНЯТА. ВЫПОЛНЯЕТСЯ ARM926…"
                        startAudio()
                        startRunLoop()
                    }
                }.onFailure { error ->
                    statusView.text = "ОШИБКА ЗАПУСКА: ${error.message}"
                    setImportControlsEnabled(true)
                }
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

    private fun renderFrame(frame: PhoneMachineFrame) {
        val pixels = frame.pixels
        if (pixels.size < frame.width * frame.height * 4) return
        val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val colors = IntArray(frame.width * frame.height)
        var offset = 0
        for (i in colors.indices) {
            val red = pixels[offset].toInt() and 0xFF
            val green = pixels[offset + 1].toInt() and 0xFF
            val blue = pixels[offset + 2].toInt() and 0xFF
            val alpha = pixels[offset + 3].toInt() and 0xFF
            colors[i] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            offset += 4
        }
        bitmap.setPixels(colors, 0, frame.width, 0, 0, frame.width, frame.height)
        val old = latestBitmap
        latestBitmap = bitmap
        screenView.setImageBitmap(bitmap)
        old?.recycle()
    }

    private fun startAudio() {
        stopAudio()
        val minBuffer = AudioTrack.getMinBufferSize(
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
            .setBufferSizeInBytes(minBuffer * 2)
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
        workExecutor.execute {
            val images = library.listImages()
            val core = images.firstOrNull { it.metadata.kind == FirmwareImageKind.CORE }
            val ppm = images.firstOrNull { it.metadata.kind == FirmwareImageKind.LANGUAGE_PACK }
            val content = images.firstOrNull { it.metadata.kind == FirmwareImageKind.CONTENT_PACK }
            val status = buildString {
                append("MCUSW: ${core?.metadata?.originalFileName ?: "НЕТ"}\n")
                append("PPM: ${ppm?.metadata?.originalFileName ?: "необязательно"}\n")
                append("IMAGE: ${content?.metadata?.originalFileName ?: "необязательно"}")
            }
            runOnUiThread {
                if (destroyed || isFinishing) return@runOnUiThread
                statusView.text = status
                if (engine.state == PhoneMachineState.STOPPED || engine.state == PhoneMachineState.FAILED) {
                    bootButton.isEnabled = core != null
                }
            }
        }
    }

    private fun setImportControlsEnabled(enabled: Boolean) {
        importZipButton.isEnabled = enabled
        importCoreButton.isEnabled = enabled
        importPpmButton.isEnabled = enabled
        importImageButton.isEnabled = enabled
    }

    private fun queryDisplayName(uri: Uri): String {
        var displayName: String? = null
        val cursor: Cursor? = contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
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

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        workExecutor.execute { engine.stop() }
        super.onBackPressed()
    }

    override fun onDestroy() {
        destroyed = true
        stopRunLoop()
        stopAudio()
        val bitmap = latestBitmap
        latestBitmap = null
        bitmap?.recycle()
        workExecutor.execute { engine.close() }
        workExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_IMPORT_C3_FIRMWARE = 6140
    }
}

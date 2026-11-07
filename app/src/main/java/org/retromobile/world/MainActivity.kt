package org.retromobile.world

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var phoneView: RetroPhoneView
    private lateinit var firmwareLibrary: FirmwareImageLibrary
    private val importExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        firmwareLibrary = FirmwareImageLibrary(this)
        phoneView = RetroPhoneView(this).apply {
            onImportFirmwareRequested = { requestFirmwareImport() }
            onFirmwareInfoRequested = { showFirmwareInfo() }
        }
        setContentView(phoneView)

        ViewCompat.setOnApplyWindowInsetsListener(phoneView) { view, insets ->
            val safeInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(safeInsets.left, safeInsets.top, safeInsets.right, safeInsets.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(phoneView)
        refreshFirmwareState()
    }

    private fun requestFirmwareImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivityForResult(intent, REQUEST_IMPORT_FIRMWARE)
        } catch (error: ActivityNotFoundException) {
            showMessage("Загрузка недоступна", "Android не смог открыть системный выбор файлов.")
        }
    }

    @Deprecated("Uses the platform activity-result callback for broad Android compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT_FIRMWARE) return

        if (resultCode != RESULT_OK) {
            phoneView.setImporting(false)
            return
        }

        val uri = data?.data
        if (uri == null) {
            phoneView.setImporting(false)
            showMessage("Файл не выбран", "Выбери ZIP-архив прошивки Nokia RM-217.")
            return
        }

        val displayName = queryDisplayName(uri)
        phoneView.setImporting(true)

        importExecutor.execute {
            val isArchive = displayName.endsWith(".zip", ignoreCase = true)
            val result = runCatching {
                if (isArchive) {
                    firmwareLibrary.importArchiveFromUri(uri, displayName)
                } else {
                    firmwareLibrary.importFromUri(uri, displayName)
                    null
                }
            }

            runOnUiThread {
                if (isFinishing || isDestroyed || !::phoneView.isInitialized) return@runOnUiThread
                phoneView.setImporting(false)

                result.onSuccess { imported ->
                    val images = firmwareLibrary.listImages()
                    val summary = firmwareLibrary.lastImportSummary()
                    phoneView.updateFirmwareState(images, summary)

                    if (imported != null) {
                        val warning = imported.summary.mismatchingChecks
                        if (warning.isNotEmpty()) {
                            val mismatch = warning.first()
                            showMessage(
                                "Файлы загружены с предупреждением",
                                "Набор RM-217 ${imported.version} сохранён в памяти приложения.\n\n" +
                                    "${mismatch.fileName}: VPL CRC ${mismatch.expectedCrc32}, " +
                                    "фактический CRC ${mismatch.actualCrc32}.\n\n" +
                                    "Это не проверенный комплект для запуска. Прошивка не запускалась."
                            )
                        } else if (!imported.summary.manifestFound) {
                            showMessage(
                                "Файлы загружены",
                                "Компоненты RM-217 ${imported.version} сохранены, но подходящий VPL " +
                                    "не найден. CRC не подтверждены. Прошивка не запускалась."
                            )
                        }
                    } else {
                        showMessage(
                            "Компонент сохранён",
                            "Отдельный компонент сохранён. Для полного набора выбери Phoenix ZIP " +
                                "с MCU, PPM и IMAGE."
                        )
                    }
                }.onFailure { error ->
                    showMessage(
                        "Не удалось загрузить прошивку",
                        error.message ?: "Файл не распознан как поддерживаемый компонент Nokia RM-217."
                    )
                }
            }
        }
    }

    private fun refreshFirmwareState() {
        importExecutor.execute {
            val images = firmwareLibrary.listImages()
            val summary = firmwareLibrary.lastImportSummary()
            runOnUiThread {
                if (!isFinishing && !isDestroyed && ::phoneView.isInitialized) {
                    phoneView.updateFirmwareState(images, summary)
                }
            }
        }
    }

    private fun showFirmwareInfo() {
        val summary = firmwareLibrary.lastImportSummary()
        val images = firmwareLibrary.listImages()
        val body = buildString {
            append("Локально сохранено компонентов: ${images.size}.\n")
            if (summary == null) {
                append("VPL CRC: нет сохранённого отчёта.\n")
            } else {
                append("Версия: ${summary.version}\n")
                append("Product code: ${summary.productCode ?: "не определён"}\n")
                append("Вариант: ${summary.description ?: "не определён"}\n")
                append(
                    if (summary.manifestFound) {
                        "VPL CRC: ${summary.matchingChecks}/${summary.checks.size} совпали.\n"
                    } else {
                        "VPL CRC: манифест не найден.\n"
                    }
                )
                summary.checks.forEach { check ->
                    append(
                        "${check.fileName}: expected ${check.expectedCrc32 ?: "—"}, " +
                            "actual ${check.actualCrc32}, result ${check.matches ?: "unknown"}\n"
                    )
                }
            }
            append("\nRM-217 emulator is not implemented yet; these files are not booted or flashed.")
        }
        showMessage("Состояние прошивки", body)
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
            ?: "RM-217-firmware.zip"
    }

    private fun showMessage(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (::phoneView.isInitialized && ::firmwareLibrary.isInitialized) {
            refreshFirmwareState()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::phoneView.isInitialized && phoneView.handleBack()) return
        super.onBackPressed()
    }

    override fun onDestroy() {
        importExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_IMPORT_FIRMWARE = 4103
    }
}

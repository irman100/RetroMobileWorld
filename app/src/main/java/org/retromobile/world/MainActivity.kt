package org.retromobile.world

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var phoneView: RetroPhoneView
    private lateinit var packageLibrary: LegacyPackageLibrary
    private lateinit var firmwareLibrary: FirmwareImageLibrary
    private val importExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        val profiles = runCatching {
            assets.open("device-profiles.json").bufferedReader().use { reader ->
                ProfileJsonLoader.parse(reader.readText())
            }
        }.getOrElse { ProfileCatalog.builtIn }

        packageLibrary = LegacyPackageLibrary(this)
        firmwareLibrary = FirmwareImageLibrary(this)
        phoneView = RetroPhoneView(this, profiles).apply {
            onImportFirmwareRequested = { requestFirmwareImport() }
            onImportJavaAppRequested = { requestJavaPackageImport() }
            onLaunchJavaApp = { record -> launchJavaApp(record) }
            onGalleryFolderSelected = { folder -> requestGalleryFolder(folder) }
            onOpenGalleryItem = { item -> openGalleryItem(item) }
        }
        setContentView(phoneView)
        ViewCompat.setOnApplyWindowInsetsListener(phoneView) { view, insets ->
            val safeInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(safeInsets.left, safeInsets.top, safeInsets.right, safeInsets.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(phoneView)
        refreshJavaPackages()
        refreshFirmwareImages()
    }

    private fun requestFirmwareImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQUEST_IMPORT_FIRMWARE)
        } catch (error: ActivityNotFoundException) {
            showMessage("Firmware import unavailable", "Android did not provide a document picker.")
        }
    }

    private fun requestJavaPackageImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivityForResult(intent, REQUEST_IMPORT_PACKAGE)
        } catch (error: ActivityNotFoundException) {
            showMessage("File picker unavailable", "Android did not provide a document picker.")
        }
    }

    @Deprecated("Uses the platform activity-result callback for broad Android compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMPORT_FIRMWARE) {
            if (resultCode != RESULT_OK) return
            val uri = data?.data ?: return
            val displayName = queryDisplayName(uri)
            importExecutor.execute {
                val result = runCatching {
                    if (displayName.substringAfterLast('.').equals("zip", ignoreCase = true)) {
                        val imported = firmwareLibrary.importArchiveFromUri(uri, displayName)
                        "RM-217 software ${imported.version}\n" +
                            imported.records.joinToString("\n") { record ->
                                "${record.metadata.originalFileName} · ${record.metadata.blockCount} blocks"
                            } +
                            "\n\nOnly MCU, PPM and canonical IMAGE components were stored. " +
                            "Other archive entries were not extracted or executed."
                    } else {
                        val record = firmwareLibrary.importFromUri(uri, displayName)
                        "${record.metadata.originalFileName}\n" +
                            "${record.metadata.kind.displayName}\n" +
                            "${record.metadata.blockCount} blocks · " +
                            "${record.metadata.namedRegions.size} named regions"
                    }
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed || !::phoneView.isInitialized) return@runOnUiThread
                    result.onSuccess { summary ->
                        refreshFirmwareImages()
                        showMessage(
                            "Firmware added to private storage",
                            summary + "\n\nOriginal files remain only on this device."
                        )
                    }.onFailure { error ->
                        showMessage(
                            "Firmware import rejected",
                            error.message ?: "The selected file is not a supported RM-217 firmware component or complete archive."
                        )
                    }
                }
            }
            return
        }

        if (requestCode == REQUEST_GALLERY_PICKER) {
            if (resultCode != RESULT_OK) {
                pendingGalleryFolder = null
                return
            }
            val folder = pendingGalleryFolder ?: "Images"
            val selectedUris = mutableListOf<Uri>()
            data?.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) selectedUris.add(clip.getItemAt(index).uri)
            }
            data?.data?.let { uri ->
                if (selectedUris.none { it == uri }) selectedUris.add(uri)
            }
            val items = selectedUris.map { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                RetroPhoneView.GalleryMediaItem(
                    name = queryDisplayName(uri),
                    uri = uri.toString(),
                    mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                )
            }
            pendingGalleryFolder = null
            phoneView.updateGalleryItems(folder, items)
            return
        }

        if (requestCode != REQUEST_IMPORT_PACKAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val displayName = queryDisplayName(uri)

        importExecutor.execute {
            val result = runCatching {
                packageLibrary.importFromUri(uri, displayName)
            }
            runOnUiThread {
                if (isFinishing || !::phoneView.isInitialized) return@runOnUiThread
                result.onSuccess { record ->
                    refreshJavaPackages()
                    showMessage(
                        "Added to Java applications",
                        record.metadata.title + " · " + record.metadata.vendor +
                            " · " + record.metadata.version +
                            "\nSelect the application on the phone and press RUN."
                    )
                }.onFailure { error ->
                    showMessage(
                        "Package not imported",
                        error.message ?: "The selected file could not be read as a Java ME package."
                    )
                }
            }
        }
    }

    private var pendingGalleryFolder: String? = null

    private fun requestGalleryFolder(folder: String) {
        pendingGalleryFolder = folder
        val mimeType = when (folder) {
            "Images", "Graphics" -> "image/*"
            "Video clips" -> "video/*"
            "Music files", "Tones", "Recordings" -> "audio/*"
            else -> "*/*"
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQUEST_GALLERY_PICKER)
        } catch (error: ActivityNotFoundException) {
            pendingGalleryFolder = null
            showMessage("Gallery unavailable", "Android did not provide a file picker.")
        }
    }

    private fun openGalleryItem(item: RetroPhoneView.GalleryMediaItem) {
        val uri = Uri.parse(item.uri)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            showMessage("Cannot open file", "No compatible viewer is installed for this file.")
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
            ?: "legacy-package.jar"
    }

    private fun launchJavaApp(record: LegacyPackageRecord) {
        if (record.metadata.kind == LegacyPackageKind.JAD) {
            val jarUri = record.metadata.jarUrl?.let(Uri::parse)
            val scheme = jarUri?.scheme?.lowercase()
            if (scheme != "https") {
                showMessage(
                    "JAD needs its JAR file",
                    "This descriptor uses a relative or local MIDlet-Jar-URL. Import the matching .jar file instead."
                )
                return
            }
        }

        val contentUri = runCatching {
            FileProvider.getUriForFile(
                this,
                packageName + ".fileprovider",
                record.file
            )
        }.getOrElse { error ->
            showMessage("Cannot share package", error.message ?: "The package URI could not be created.")
            return
        }

        val mimeType = when (record.metadata.kind) {
            LegacyPackageKind.JAR -> "application/java-archive"
            LegacyPackageKind.JAD -> "text/vnd.sun.j2me.app-descriptor"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setPackage(J2ME_LOADER_PACKAGE)
            setDataAndType(contentUri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, record.metadata.title, contentUri)
        }

        try {
            startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            AlertDialog.Builder(this)
                .setTitle("Java ME engine is not installed")
                .setMessage(
                    "The first Java ME integration uses J2ME Loader as a separate runtime. " +
                        "Install the open-source engine, then return here and press RUN again."
                )
                .setPositiveButton("Project page") { _, _ ->
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/nikita36078/J2ME-Loader")
                        )
                    )
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun refreshJavaPackages() {
        importExecutor.execute {
            val packages = packageLibrary.listInstalled()
            runOnUiThread {
                if (!isFinishing && !isDestroyed && ::phoneView.isInitialized) {
                    phoneView.updateJavaPackages(packages)
                }
            }
        }
    }

    private fun refreshFirmwareImages() {
        importExecutor.execute {
            val images = firmwareLibrary.listImages()
            runOnUiThread {
                if (!isFinishing && !isDestroyed && ::phoneView.isInitialized) {
                    phoneView.updateFirmwareImages(images)
                }
            }
        }
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
        if (::phoneView.isInitialized && ::packageLibrary.isInitialized) {
            refreshJavaPackages()
            refreshFirmwareImages()
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
        private const val REQUEST_IMPORT_PACKAGE = 4101
        private const val REQUEST_GALLERY_PICKER = 4102
        private const val REQUEST_IMPORT_FIRMWARE = 4103
        private const val J2ME_LOADER_PACKAGE = "ru.playsoftware.j2meloader"
    }
}

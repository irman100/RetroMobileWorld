package org.retromobile.world

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class FirmwareImageRecord(
    val id: String,
    val file: File,
    val metadata: FirmwareImageMetadata
)

data class FirmwareArchiveImportResult(
    val version: String,
    val records: List<FirmwareImageRecord>
)

/**
 * Local-only store for firmware components supplied by the user.
 * No firmware data is uploaded, added to APK assets or written into repository files.
 */
class FirmwareImageLibrary(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "system-images/nokia-rm-217")

    fun listImages(): List<FirmwareImageRecord> =
        directory.listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile && !it.name.startsWith(".") && "--" in it.name }
            .mapNotNull { file ->
                runCatching {
                    val id = file.name.substringBefore("--")
                    val originalName = file.name.substringAfter("--")
                    FirmwareImageRecord(
                        id = id,
                        file = file,
                        metadata = FirmwareImageInspector.inspect(file, originalName)
                    )
                }.getOrNull()
            }
            .sortedWith(compareBy<FirmwareImageRecord> { it.metadata.kind.ordinal }
                .thenBy { it.metadata.originalFileName.lowercase() })
            .toList()

    @Throws(IOException::class, IllegalArgumentException::class)
    fun importFromUri(uri: Uri, displayName: String): FirmwareImageRecord {
        val safeName = sanitizeFileName(displayName)
        FirmwareImageInspector.classify(safeName)
        ensureDirectory()

        val staging = File(directory, ".${UUID.randomUUID()}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            val source = appContext.contentResolver.openInputStream(uri)
                ?: throw IOException("Android could not open the selected firmware file.")

            source.use { input ->
                FileOutputStream(staging).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        if (totalBytes > FirmwareImageInspector.MAX_FILE_BYTES) {
                            throw IOException("Firmware component exceeds the 256 MiB import limit.")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }

            require(totalBytes >= 9L) { "The selected firmware file is empty or truncated." }
            val metadata = FirmwareImageInspector.inspect(staging, safeName)
            return commitStaged(staging, safeName, digest.digest(), metadata)
        } catch (error: Exception) {
            staging.delete()
            throw error
        }
    }

    /**
     * Imports only the RM-217 MCU, PPM and canonical IMAGE components from a Phoenix ZIP.
     * Installer executables and alternate colour IMAGE variants are streamed past,
     * never extracted or executed.
     */
    @Throws(IOException::class, IllegalArgumentException::class)
    fun importArchiveFromUri(uri: Uri, archiveName: String): FirmwareArchiveImportResult {
        require(archiveName.substringAfterLast('.').equals("zip", ignoreCase = true)) {
            "Choose the RM-217 firmware ZIP archive."
        }
        ensureDirectory()

        val staged = mutableListOf<StagedComponent>()
        val createdFiles = mutableListOf<File>()
        var expandedBytes = 0L
        var entryCount = 0

        try {
            val source = appContext.contentResolver.openInputStream(uri)
                ?: throw IOException("Android could not open the firmware archive.")

            source.use { raw ->
                ZipInputStream(raw).use { zip ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    while (true) {
                        val entry: ZipEntry = zip.nextEntry ?: break
                        entryCount += 1
                        if (entryCount > MAX_ARCHIVE_ENTRIES) {
                            throw IOException("Firmware archive contains too many entries.")
                        }

                        val baseName = entry.name.substringAfterLast('/').substringAfterLast('\\')
                        val selected = !entry.isDirectory &&
                            FirmwareArchiveSelection.isPotentialComponent(baseName)
                        val safeName = if (selected) sanitizeFileName(baseName) else null
                        val temp = if (selected) File(directory, ".${UUID.randomUUID()}.part") else null
                        val digest = if (selected) MessageDigest.getInstance("SHA-256") else null
                        var entryBytes = 0L

                        try {
                            val output = temp?.let { FileOutputStream(it) }
                            try {
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    entryBytes += count
                                    expandedBytes += count
                                    if (expandedBytes > MAX_ARCHIVE_EXPANDED_BYTES) {
                                        throw IOException("Expanded firmware archive exceeds the 512 MiB safety limit.")
                                    }
                                    if (selected && entryBytes > FirmwareImageInspector.MAX_FILE_BYTES) {
                                        throw IOException("Firmware component $baseName exceeds the 256 MiB import limit.")
                                    }
                                    digest?.update(buffer, 0, count)
                                    output?.write(buffer, 0, count)
                                }
                                output?.fd?.sync()
                            } finally {
                                output?.close()
                            }

                            if (selected) {
                                require(entryBytes >= 9L) { "Firmware component $baseName is empty or truncated." }
                                val metadata = FirmwareImageInspector.inspect(temp!!, safeName!!)
                                staged += StagedComponent(
                                    originalName = safeName,
                                    file = temp,
                                    digest = digest!!.digest(),
                                    metadata = metadata
                                )
                            }
                        } catch (error: Exception) {
                            temp?.delete()
                            throw error
                        } finally {
                            zip.closeEntry()
                        }
                    }
                }
            }

            val selectedNames = FirmwareArchiveSelection.selectRequired(staged.map { it.originalName })
            val selected = selectedNames.map { name ->
                staged.single { it.originalName.equals(name, ignoreCase = true) }
            }

            val records = mutableListOf<FirmwareImageRecord>()
            selected.forEach { component ->
                val id = component.digest.toHex()
                val destination = File(directory, "$id--${component.originalName}")
                if (destination.exists()) {
                    component.file.delete()
                    records += FirmwareImageRecord(id, destination, component.metadata)
                } else {
                    if (!component.file.renameTo(destination)) {
                        throw IOException("Could not save ${component.originalName} in private storage.")
                    }
                    createdFiles += destination
                    records += FirmwareImageRecord(id, destination, component.metadata)
                }
            }

            staged.filterNot { it in selected }.forEach { it.file.delete() }
            return FirmwareArchiveImportResult(
                version = FirmwareArchiveSelection.versionFrom(selectedNames),
                records = records
            )
        } catch (error: Exception) {
            staged.forEach { it.file.delete() }
            createdFiles.forEach { it.delete() }
            throw error
        }
    }

    private fun commitStaged(
        staging: File,
        safeName: String,
        digest: ByteArray,
        metadata: FirmwareImageMetadata
    ): FirmwareImageRecord {
        val id = digest.toHex()
        val destination = File(directory, "$id--$safeName")
        if (destination.exists()) {
            staging.delete()
        } else if (!staging.renameTo(destination)) {
            throw IOException("Firmware component could not be committed to private storage.")
        }
        return FirmwareImageRecord(id, destination, metadata)
    }

    private fun ensureDirectory() {
        require(directory.exists() || directory.mkdirs()) {
            "The private firmware library could not be created."
        }
    }

    private fun sanitizeFileName(value: String): String {
        val leaf = value.substringAfterLast('/').substringAfterLast('\\')
        val safe = leaf.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
        require(safe.isNotBlank()) { "The firmware file name is invalid." }
        return safe
    }

    private data class StagedComponent(
        val originalName: String,
        val file: File,
        val digest: ByteArray,
        val metadata: FirmwareImageMetadata
    )

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }

    companion object {
        private const val MAX_ARCHIVE_ENTRIES = 1024
        private const val MAX_ARCHIVE_EXPANDED_BYTES = 512L * 1024L * 1024L
    }
}

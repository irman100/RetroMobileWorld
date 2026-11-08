package org.retromobile.world

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Private, device-local firmware store for Nokia C3-00 / RM-614.
 * Firmware is never copied into source control or bundled in the APK.
 */
class C3FirmwareLibrary(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "system-images/nokia-rm-614")

    fun listImages(): List<FirmwareImageRecord> =
        directory.listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile && !it.name.startsWith(".") && "--" in it.name }
            .mapNotNull { file ->
                runCatching {
                    val originalName = file.name.substringAfter("--")
                    FirmwareImageRecord(
                        id = file.name.substringBefore("--"),
                        file = file,
                        metadata = FirmwareImageInspector.inspect(file, originalName)
                    )
                }.getOrNull()
            }
            .sortedWith(
                compareBy<FirmwareImageRecord> { it.metadata.kind.ordinal }
                    .thenBy { it.metadata.originalFileName.lowercase(Locale.ROOT) }
                    .thenBy { it.file.lastModified() }
            )
            .toList()

    fun importComponentFromUri(uri: Uri, displayName: String): FirmwareImageRecord {
        val safeName = sanitizeName(displayName)
        val kind = FirmwareImageInspector.classify(safeName)
        require(isRm614Name(safeName)) {
            "This file name does not identify Nokia C3-00 / RM-614."
        }
        directory.mkdirs()
        val staging = File(directory, ".${System.nanoTime()}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val source = appContext.contentResolver.openInputStream(uri)
                ?: throw IOException("Android could not open the selected firmware component.")
            source.use { input ->
                FileOutputStream(staging).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= FirmwareImageInspector.MAX_FILE_BYTES) {
                            "Firmware component exceeds the 256 MiB import limit."
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            require(total >= 9L) { "The selected firmware component is empty or truncated." }
            val metadata = try {
                FirmwareImageInspector.inspect(staging, safeName)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "Firmware component '$safeName': ${error.message}",
                    error
                )
            }
            require(metadata.kind == kind)
            return commit(staging, safeName, digest.digest(), metadata)
        } catch (error: Exception) {
            staging.delete()
            throw error
        }
    }

    /** Import only RM-614 MCU/PPM/IMAGE entries from a Phoenix ZIP. */
    fun importArchiveFromUri(uri: Uri, archiveName: String): List<FirmwareImageRecord> {
        require(archiveName.substringAfterLast('.').equals("zip", ignoreCase = true)) {
            "Choose a Phoenix ZIP archive."
        }
        directory.mkdirs()
        val imported = mutableListOf<FirmwareImageRecord>()
        val staged = mutableListOf<File>()
        var expanded = 0L
        var entries = 0

        try {
            val source = appContext.contentResolver.openInputStream(uri)
                ?: throw IOException("Android could not open the firmware archive.")
            source.use { raw ->
                ZipInputStream(raw).use { zip ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries++
                        require(entries <= MAX_ARCHIVE_ENTRIES) {
                            "Phoenix archive contains too many entries."
                        }

                        val rawName = entry.name
                            .substringAfterLast('/')
                            .substringAfterLast('\\')
                        val safeName = rawName.takeIf { it.isNotBlank() }?.let(::sanitizeName)
                        val candidate = !entry.isDirectory &&
                            safeName != null &&
                            isRm614Name(safeName) &&
                            runCatching { FirmwareImageInspector.classify(safeName) }.isSuccess

                        val temp = if (candidate) {
                            File(directory, ".${System.nanoTime()}-${entries}.part").also(staged::add)
                        } else null
                        val digest = if (candidate) MessageDigest.getInstance("SHA-256") else null
                        var componentBytes = 0L
                        val output = temp?.let(::FileOutputStream)
                        try {
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                expanded += count
                                require(expanded <= MAX_ARCHIVE_EXPANDED_BYTES) {
                                    "Expanded firmware archive exceeds 512 MiB."
                                }
                                if (candidate) {
                                    componentBytes += count
                                    require(componentBytes <= FirmwareImageInspector.MAX_FILE_BYTES) {
                                        "Firmware component ${safeName} exceeds 256 MiB."
                                    }
                                    digest?.update(buffer, 0, count)
                                    output?.write(buffer, 0, count)
                                }
                            }
                            output?.fd?.sync()
                        } finally {
                            output?.close()
                        }

                        if (candidate) {
                            require(componentBytes >= 9L) {
                                "Firmware component ${safeName} is truncated."
                            }
                            val metadata = try {
                                FirmwareImageInspector.inspect(temp!!, safeName!!)
                            } catch (error: IllegalArgumentException) {
                                throw IllegalArgumentException(
                                    "Phoenix ZIP entry '$safeName': ${error.message}",
                                    error
                                )
                            }
                            imported += commit(temp, safeName, digest!!.digest(), metadata)
                            staged.remove(temp)
                        }
                        zip.closeEntry()
                    }
                }
            }
            require(imported.any { it.metadata.kind == FirmwareImageKind.CORE }) {
                "This archive did not contain an RM-614 MCUSW file."
            }
            return imported
        } catch (error: Exception) {
            staged.forEach { it.delete() }
            throw error
        }
    }

    fun find(kind: FirmwareImageKind): FirmwareImageRecord? =
        listImages()
            .filter { it.metadata.kind == kind }
            .maxByOrNull { it.file.lastModified() }

    private fun commit(
        staging: File,
        originalName: String,
        digest: ByteArray,
        metadata: FirmwareImageMetadata
    ): FirmwareImageRecord {
        val id = digest.joinToString("") { "%02x".format(it) }
        val destination = File(directory, "${id}--${originalName}")
        if (destination.exists()) {
            staging.delete()
            destination.setLastModified(System.currentTimeMillis())
            return FirmwareImageRecord(id, destination, metadata)
        }
        check(staging.renameTo(destination)) { "Unable to commit the imported RM-614 component." }
        return FirmwareImageRecord(id, destination, metadata)
    }

    private fun isRm614Name(name: String): Boolean =
        name.lowercase(Locale.ROOT).replace("-", "").replace("_", "").contains("rm614")

    private fun sanitizeName(value: String): String {
        val base = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(180)
        require(base.isNotBlank() && base != "." && base != "..") {
            "Firmware component has an invalid file name."
        }
        return base
    }

    companion object {
        private const val MAX_ARCHIVE_ENTRIES = 256
        private const val MAX_ARCHIVE_EXPANDED_BYTES = 512L * 1024L * 1024L
    }
}

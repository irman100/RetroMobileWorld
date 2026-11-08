package org.retromobile.world

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

data class C3FirmwareSelection(
    val firmware: PhoneFirmwareSet,
    val variant: String?,
    val warnings: List<String>
)

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

    /**
     * Select only components belonging to the MCUSW software release and, when
     * both optional packages exist, only a PPM/IMAGE pair with the same region
     * key (for example MH1+MH1 or MR+MR). Never combine independent "latest"
     * results from different regional variants.
     */
    fun selectFirmware(): C3FirmwareSelection? {
        val records = listImages()
        val core = records
            .filter { it.metadata.kind == FirmwareImageKind.CORE }
            .maxByOrNull { it.file.lastModified() }
            ?: return null
        val coreStem = releaseStem(core.metadata.originalFileName)
        val allPpm = records.filter { it.metadata.kind == FirmwareImageKind.LANGUAGE_PACK }
        val allImages = records.filter { it.metadata.kind == FirmwareImageKind.CONTENT_PACK }
        val ppms = allPpm.filter { releaseStem(it.metadata.originalFileName) == coreStem }
        val images = allImages.filter { releaseStem(it.metadata.originalFileName) == coreStem }
        val warnings = mutableListOf<String>()

        if (allPpm.isNotEmpty() && ppms.isEmpty()) {
            warnings += "PPM пропущен: его версия не совпадает с выбранным MCUSW."
        }
        if (allImages.isNotEmpty() && images.isEmpty()) {
            warnings += "IMAGE пропущен: его версия не совпадает с выбранным MCUSW."
        }

        var selectedPpm: FirmwareImageRecord? = null
        var selectedImage: FirmwareImageRecord? = null
        var selectedVariant: String? = null

        if (ppms.isNotEmpty() && images.isNotEmpty()) {
            val ppmByVariant = ppms.groupBy(::variantOf)
                .mapNotNull { (variant, matches) ->
                    variant?.let { it to matches.maxBy { record -> record.file.lastModified() } }
                }.toMap()
            val imageByVariant = images.groupBy(::variantOf)
                .mapNotNull { (variant, matches) ->
                    variant?.let { it to matches.maxBy { record -> record.file.lastModified() } }
                }.toMap()
            val commonVariants = ppmByVariant.keys.intersect(imageByVariant.keys)
            selectedVariant = commonVariants.maxByOrNull { variant ->
                maxOf(
                    ppmByVariant.getValue(variant).file.lastModified(),
                    imageByVariant.getValue(variant).file.lastModified()
                )
            }
            if (selectedVariant != null) {
                selectedPpm = ppmByVariant.getValue(selectedVariant!!)
                selectedImage = imageByVariant.getValue(selectedVariant!!)
            } else {
                warnings += "PPM и IMAGE найдены, но региональные варианты не совпадают. Оба пакета пропущены; загрузка продолжится с MCUSW."
            }
        } else {
            selectedPpm = ppms.maxByOrNull { it.file.lastModified() }
            selectedImage = images.maxByOrNull { it.file.lastModified() }
            selectedVariant = selectedPpm?.let(::variantOf) ?: selectedImage?.let(::variantOf)
        }

        return C3FirmwareSelection(
            firmware = PhoneFirmwareSet(
                core = core,
                languagePack = selectedPpm,
                contentPack = selectedImage
            ),
            variant = selectedVariant,
            warnings = warnings
        )
    }

    private fun releaseStem(fileName: String): String? {
        val lower = fileName.lowercase(Locale.ROOT)
        val marker = listOf(".mcusw", ".ppm_", ".image_")
            .map { lower.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
            ?: return null
        return lower.substring(0, marker)
    }

    private fun variantOf(record: FirmwareImageRecord): String? {
        val extension = record.metadata.originalFileName
            .substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
        return when (record.metadata.kind) {
            FirmwareImageKind.LANGUAGE_PACK ->
                extension.removePrefix("ppm_").substringBefore('_').takeIf { it.isNotBlank() }
            FirmwareImageKind.CONTENT_PACK ->
                extension.removePrefix("image_").substringBefore('_').takeIf { it.isNotBlank() }
            FirmwareImageKind.CORE -> null
        }
    }

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

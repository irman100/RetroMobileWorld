package org.retromobile.world

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

data class FirmwareImageRecord(
    val id: String,
    val file: File,
    val metadata: FirmwareImageMetadata
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
        require(directory.exists() || directory.mkdirs()) {
            "The private firmware library could not be created."
        }

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
            val id = digest.digest().joinToString("") { byte ->
                (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
            val destination = File(directory, "$id--$safeName")

            if (destination.exists()) {
                staging.delete()
            } else if (!staging.renameTo(destination)) {
                throw IOException("Firmware component could not be committed to private storage.")
            }

            return FirmwareImageRecord(id, destination, metadata)
        } catch (error: Exception) {
            staging.delete()
            throw error
        }
    }

    private fun sanitizeFileName(value: String): String {
        val leaf = value.substringAfterLast('/').substringAfterLast('\\')
        val safe = leaf.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
        require(safe.isNotBlank()) { "The firmware file name is invalid." }
        return safe
    }
}

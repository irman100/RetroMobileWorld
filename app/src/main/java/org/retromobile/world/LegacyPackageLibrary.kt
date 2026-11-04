package org.retromobile.world

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

data class LegacyPackageRecord(
    val id: String,
    val file: File,
    val metadata: LegacyPackageMetadata
)

/**
 * Keeps imported legacy packages in app-private storage.
 * The source URI is copied once and is not retained as a long-lived permission.
 */
class LegacyPackageLibrary(context: Context) {
    private val appContext = context.applicationContext
    private val packagesDirectory = File(appContext.filesDir, "legacy-library/packages")

    fun listInstalled(): List<LegacyPackageRecord> {
        val files = packagesDirectory.listFiles().orEmpty()
        return files.asSequence()
            .filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
            .mapNotNull { file ->
                runCatching {
                    LegacyPackageRecord(
                        id = file.nameWithoutExtension,
                        file = file,
                        metadata = LegacyPackageInspector.inspect(file)
                    )
                }.getOrNull()
            }
            .sortedBy { it.metadata.title.lowercase() }
            .toList()
    }

    @Throws(IOException::class, IllegalArgumentException::class)
    fun importFromUri(uri: Uri, displayName: String): LegacyPackageRecord {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        require(extension in SUPPORTED_EXTENSIONS) {
            "Choose a Java ME .jar application or .jad descriptor."
        }
        require(packagesDirectory.exists() || packagesDirectory.mkdirs()) {
            "The private application library could not be created."
        }

        val stagingFile = File(packagesDirectory, "." + UUID.randomUUID() + "." + extension)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw IOException("Android could not open the selected document.")

            input.use { source ->
                FileOutputStream(stagingFile).use { destination ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        if (totalBytes > LegacyPackageInspector.MAX_PACKAGE_BYTES) {
                            throw IOException("Package exceeds the 128 MiB import limit.")
                        }
                        digest.update(buffer, 0, count)
                        destination.write(buffer, 0, count)
                    }
                }
            }

            require(totalBytes > 0) { "The selected file is empty." }
            val metadata = LegacyPackageInspector.inspect(stagingFile)
            val id = digest.digest().joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
            val storedFile = File(packagesDirectory, "$id.$extension")

            if (storedFile.exists()) {
                stagingFile.delete()
            } else if (!stagingFile.renameTo(storedFile)) {
                throw IOException("The package could not be moved into the private library.")
            }

            return LegacyPackageRecord(id, storedFile, metadata)
        } catch (error: Exception) {
            stagingFile.delete()
            throw error
        }
    }

    companion object {
        private val SUPPORTED_EXTENSIONS = setOf("jar", "jad")
    }
}

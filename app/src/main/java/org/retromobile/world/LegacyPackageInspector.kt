package org.retromobile.world

import java.io.File
import java.io.IOException
import java.util.jar.JarFile

enum class LegacyPackageKind {
    JAR,
    JAD
}

data class MidletDescriptor(
    val index: Int,
    val name: String,
    val icon: String?,
    val className: String
)

data class LegacyPackageMetadata(
    val kind: LegacyPackageKind,
    val title: String,
    val vendor: String,
    val version: String,
    val midlets: List<MidletDescriptor>,
    val jarUrl: String?
)

/**
 * Reads only package metadata. It never extracts or executes untrusted package contents.
 */
object LegacyPackageInspector {
    const val MAX_PACKAGE_BYTES = 128L * 1024L * 1024L
    private const val MAX_JAD_BYTES = 1024L * 1024L
    private const val MAX_ARCHIVE_ENTRIES = 4096
    private const val MAX_ENTRY_BYTES = 64L * 1024L * 1024L
    private const val MAX_EXPANDED_BYTES = 256L * 1024L * 1024L

    private val midletAttribute = Regex("^MIDlet-(\\d+)$", RegexOption.IGNORE_CASE)

    @Throws(IOException::class, IllegalArgumentException::class)
    fun inspect(file: File): LegacyPackageMetadata {
        require(file.isFile) { "Selected package is not a file." }
        require(file.length() in 1..MAX_PACKAGE_BYTES) {
            "Package must be between 1 byte and 128 MiB."
        }

        return when (file.extension.lowercase()) {
            "jar" -> inspectJar(file)
            "jad" -> inspectJad(file)
            else -> throw IllegalArgumentException("Only .jar and .jad packages are supported.")
        }
    }

    private fun inspectJar(file: File): LegacyPackageMetadata =
        try {
            JarFile(file, false).use { archive ->
                val manifest = archive.manifest
                    ?: throw IllegalArgumentException("The JAR does not contain a manifest.")
                validateArchiveEntries(archive)

                val attributes = manifest.mainAttributes.entries.associate { entry ->
                    entry.key.toString() to entry.value.toString()
                }
                createMetadata(LegacyPackageKind.JAR, attributes, requireJarUrl = false)
            }
        } catch (error: IOException) {
            throw IllegalArgumentException("The JAR archive is damaged or unreadable.", error)
        }

    private fun inspectJad(file: File): LegacyPackageMetadata {
        require(file.length() <= MAX_JAD_BYTES) { "The JAD descriptor is larger than 1 MiB." }
        val attributes = parseDescriptor(file.readText(Charsets.UTF_8))
        return createMetadata(LegacyPackageKind.JAD, attributes, requireJarUrl = true)
    }

    private fun validateArchiveEntries(archive: JarFile) {
        val entries = archive.entries()
        var count = 0
        var expandedBytes = 0L

        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            count += 1
            require(count <= MAX_ARCHIVE_ENTRIES) {
                "The JAR contains too many entries."
            }

            val normalizedName = entry.name.replace('\\', '/')
            require(!normalizedName.startsWith("/") &&
                normalizedName.split('/').none { it == ".." }) {
                "The JAR contains an unsafe archive path."
            }

            val size = entry.size
            if (size >= 0) {
                require(size <= MAX_ENTRY_BYTES) {
                    "The JAR contains an unusually large entry."
                }
                expandedBytes += size
                require(expandedBytes <= MAX_EXPANDED_BYTES) {
                    "The JAR expands beyond the supported inspection limit."
                }
            }
        }
    }

    private fun parseDescriptor(text: String): Map<String, String> {
        val attributes = linkedMapOf<String, String>()
        var previousKey: String? = null

        text.replace("\r\n", "\n").replace('\r', '\n').lineSequence().forEach { rawLine ->
            if (rawLine.isBlank()) return@forEach

            if (rawLine.startsWith(" ") && previousKey != null) {
                val key = previousKey
                attributes[key] = attributes.getValue(key) + rawLine.trim()
                return@forEach
            }

            val separator = rawLine.indexOf(':')
            require(separator in 1 until rawLine.length) {
                "The JAD descriptor contains a malformed attribute."
            }

            val key = rawLine.substring(0, separator).trim()
            require(key.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]*"))) {
                "The JAD descriptor contains an invalid attribute name."
            }

            attributes[key] = rawLine.substring(separator + 1).trim()
            previousKey = key
        }

        return attributes
    }

    private fun createMetadata(
        kind: LegacyPackageKind,
        attributes: Map<String, String>,
        requireJarUrl: Boolean
    ): LegacyPackageMetadata {
        fun value(name: String): String? = attributes.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val midlets = attributes.entries.mapNotNull { (key, rawValue) ->
            val match = midletAttribute.matchEntire(key) ?: return@mapNotNull null
            val parts = rawValue.split(',', limit = 3).map(String::trim)
            require(parts.size == 3 && parts[0].isNotEmpty() && parts[2].isNotEmpty()) {
                "MIDlet descriptor '$key' must contain a name, icon and class."
            }
            MidletDescriptor(
                index = match.groupValues[1].toInt(),
                name = parts[0],
                icon = parts[1].takeIf { it.isNotEmpty() },
                className = parts[2]
            )
        }.sortedBy { it.index }

        require(midlets.isNotEmpty()) { "No MIDlet entry was found in the package." }

        val jarUrl = value("MIDlet-Jar-URL")
        if (requireJarUrl) {
            require(!jarUrl.isNullOrBlank()) { "The JAD descriptor has no MIDlet-Jar-URL." }
        }

        val title = value("MIDlet-Name") ?: midlets.first().name
        return LegacyPackageMetadata(
            kind = kind,
            title = title,
            vendor = value("MIDlet-Vendor") ?: "Unknown vendor",
            version = value("MIDlet-Version") ?: "Unknown version",
            midlets = midlets,
            jarUrl = jarUrl
        )
    }
}

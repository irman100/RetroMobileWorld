package org.retromobile.world

import java.util.Locale

data class FirmwareManifest(
    val productCode: String,
    val softwareVersion: String,
    val description: String,
    val crc32ByFileName: Map<String, String>
)

data class FirmwareComponentChecksum(
    val fileName: String,
    val expectedCrc32: String?,
    val actualCrc32: String,
    val matches: Boolean?
)

data class FirmwareImportSummary(
    val version: String,
    val productCode: String?,
    val description: String?,
    val manifestFound: Boolean,
    val checks: List<FirmwareComponentChecksum>
) {
    val matchingChecks: Int
        get() = checks.count { it.matches == true }

    val mismatchingChecks: List<FirmwareComponentChecksum>
        get() = checks.filter { it.matches == false }

    val allChecksMatch: Boolean
        get() = manifestFound && checks.isNotEmpty() && checks.all { it.matches == true }
}

/**
 * Reads only the small XML manifest shipped with a Nokia Phoenix package.
 * DTD/entity declarations are rejected; no external entities are resolved.
 */
object FirmwareManifestInspector {
    const val MAX_MANIFEST_BYTES = 1024 * 1024

    fun parse(bytes: ByteArray): FirmwareManifest {
        require(bytes.isNotEmpty() && bytes.size <= MAX_MANIFEST_BYTES) {
            "VPL manifest must be between 1 byte and 1 MiB."
        }

        val xml = bytes.toString(Charsets.UTF_8)
        val upper = xml.uppercase(Locale.ROOT)
        require("<!DOCTYPE" !in upper && "<!ENTITY" !in upper) {
            "VPL manifest must not contain DTD or entity declarations."
        }

        val productCode = tagText(xml, "ProductCode")
            ?: throw IllegalArgumentException("VPL is missing ProductCode.")
        val softwareVersion = tagText(xml, "SwVersion")
            ?: throw IllegalArgumentException("VPL is missing SwVersion.")
        val description = tagText(xml, "Description").orEmpty()

        val fileBlocks = Regex(
            "<File\\b[^>]*>(.*?)</File>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(xml)

        val checksums = linkedMapOf<String, String>()
        fileBlocks.forEach { match ->
            val block = match.groupValues[1]
            val name = tagText(block, "Name") ?: return@forEach
            val crc = tagText(block, "Crc") ?: return@forEach
            if (Regex("[0-9A-Fa-f]{8}").matches(crc)) {
                val key = name.lowercase(Locale.ROOT)
                require(key !in checksums) { "VPL contains duplicate checksum entries for $name." }
                checksums[key] = crc.uppercase(Locale.ROOT)
            }
        }

        require(productCode.isNotBlank()) { "VPL ProductCode is empty." }
        require(softwareVersion.matches(Regex("[0-9]+\\.[0-9]+"))) {
            "VPL software version is invalid."
        }
        require(checksums.isNotEmpty()) { "VPL contains no usable component checksums." }

        return FirmwareManifest(
            productCode = productCode,
            softwareVersion = softwareVersion,
            description = description,
            crc32ByFileName = checksums
        )
    }

    fun check(
        fileName: String,
        actualCrc32: String,
        expectedByName: Map<String, String>
    ): FirmwareComponentChecksum {
        val expected = expectedByName[fileName.lowercase(Locale.ROOT)]
        return FirmwareComponentChecksum(
            fileName = fileName,
            expectedCrc32 = expected,
            actualCrc32 = actualCrc32.uppercase(Locale.ROOT),
            matches = expected?.equals(actualCrc32, ignoreCase = true)
        )
    }

    private fun tagText(source: String, tag: String): String? {
        val pattern = Regex(
            "<$tag(?:\\s[^>]*)?>(.*?)</$tag\\s*>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        return pattern.find(source)?.groupValues?.get(1)
            ?.trim()
            ?.replace("&amp;", "&")
            ?.replace("&lt;", "<")
            ?.replace("&gt;", ">")
            ?.replace("&quot;", "\"")
            ?.replace("&apos;", "'")
            ?.takeIf { it.isNotEmpty() }
    }
}

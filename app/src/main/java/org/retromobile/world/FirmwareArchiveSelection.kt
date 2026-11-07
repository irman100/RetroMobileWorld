package org.retromobile.world

/**
 * Resolves the three required files from a Nokia Phoenix RM-217 firmware archive.
 * The canonical content image is image_c_tr; optional colour-specific alternatives
 * are deliberately not selected.
 */
object FirmwareArchiveSelection {
    private val mcuPattern = Regex("""^rm217__([0-9]+\.[0-9]+)\.mcusw$""", RegexOption.IGNORE_CASE)
    private val candidatePattern = Regex(
        """^rm217__([0-9]+\.[0-9]+)\.(mcusw|ppm_[a-z0-9_-]+|image_c_tr)$""",
        RegexOption.IGNORE_CASE
    )

    fun isPotentialComponent(fileName: String): Boolean =
        candidatePattern.matches(fileName)

    fun versionFrom(selectedNames: List<String>): String {
        require(selectedNames.size == 3) { "A complete RM-217 set must contain three components." }
        return selectedNames.firstOrNull { it.endsWith(".mcusw", ignoreCase = true) }
            ?.let { mcuPattern.matchEntire(it)?.groupValues?.get(1) }
            ?: throw IllegalArgumentException("RM-217 MCU component does not contain a versioned file name.")
    }

    fun selectRequired(fileNames: List<String>): List<String> {
        val byLowerName = fileNames.groupBy { it.lowercase() }
        val duplicates = byLowerName.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "The archive contains duplicate firmware component names: ${duplicates.joinToString()}."
        }

        val mcus = fileNames.filter { mcuPattern.matches(it) }
        require(mcus.size == 1) {
            "The archive must contain exactly one versioned RM-217 .mcusw component."
        }
        val version = mcuPattern.matchEntire(mcus.single())!!.groupValues[1]
        val prefix = "rm217__$version."
        val required = listOf(
            "${prefix}mcusw",
            "${prefix}ppm_c",
            "${prefix}image_c_tr"
        )
        val missing = required.filter { requiredName ->
            fileNames.none { it.equals(requiredName, ignoreCase = true) }
        }
        require(missing.isEmpty()) {
            "The RM-217 $version firmware set is incomplete. Missing: ${missing.joinToString()}."
        }
        return required
    }
}

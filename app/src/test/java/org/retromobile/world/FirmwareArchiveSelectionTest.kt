package org.retromobile.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareArchiveSelectionTest {
    @Test
    fun selectsCanonicalComponentsAndIgnoresAlternateColourImages() {
        val names = listOf(
            "rm217__07.30.mcusw",
            "rm217__07.30.ppm_c",
            "rm217__07.30.image_c_tr",
            "rm217__07.30.image_c_tr_white",
            "rm217__07.30.image_c_tr_black",
            "rm217__07.30_sd_euro_512.fpsimage",
            "unins000.exe"
        )
        assertEquals(
            listOf("rm217__07.30.mcusw", "rm217__07.30.ppm_c", "rm217__07.30.image_c_tr"),
            FirmwareArchiveSelection.selectRequired(names)
        )
        assertEquals("07.30", FirmwareArchiveSelection.versionFrom(names.take(3)))
    }

    @Test
    fun refusesASetWhoseComponentsDoNotShareOneVersion() {
        val error = runCatching {
            FirmwareArchiveSelection.selectRequired(
                listOf(
                    "rm217__07.30.mcusw",
                    "rm217__07.30.ppm_c",
                    "rm217__07.29.image_c_tr"
                )
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("incomplete"))
    }

    @Test
    fun refusesDuplicateOrIncompleteFirmwareSets() {
        assertFalse(
            runCatching {
                FirmwareArchiveSelection.selectRequired(
                    listOf(
                        "rm217__07.30.mcusw",
                        "rm217__07.30.mcusw",
                        "rm217__07.30.ppm_c",
                        "rm217__07.30.image_c_tr"
                    )
                )
            }.isSuccess
        )
        assertFalse(
            runCatching {
                FirmwareArchiveSelection.selectRequired(
                    listOf("rm217__07.30.mcusw", "rm217__07.30.ppm_c")
                )
            }.isSuccess
        )
    }
}

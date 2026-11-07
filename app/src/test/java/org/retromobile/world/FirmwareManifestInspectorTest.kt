package org.retromobile.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareManifestInspectorTest {
    @Test
    fun readsProductVersionAndComponentChecksums() {
        val manifest = FirmwareManifestInspector.parse(vplFixture())

        assertEquals("0537625", manifest.productCode)
        assertEquals("07.30", manifest.softwareVersion)
        assertEquals("RM-217 6300 EURO-C TR", manifest.description)
        assertEquals("01C45DB7", manifest.crc32ByFileName["rm217__07.30.mcusw"])
        assertEquals("C585B71C", manifest.crc32ByFileName["rm217__07.30.ppm_c"])
        assertEquals("5008F91F", manifest.crc32ByFileName["rm217__07.30.image_c_tr"])
    }

    @Test
    fun reportsTheKnownPpmMismatchWithoutRejectingTheManifest() {
        val manifest = FirmwareManifestInspector.parse(vplFixture())

        val mcu = FirmwareManifestInspector.check(
            "rm217__07.30.mcusw",
            "01C45DB7",
            manifest.crc32ByFileName
        )
        val ppm = FirmwareManifestInspector.check(
            "rm217__07.30.ppm_c",
            "613D565F",
            manifest.crc32ByFileName
        )
        val image = FirmwareManifestInspector.check(
            "rm217__07.30.image_c_tr",
            "5008F91F",
            manifest.crc32ByFileName
        )

        assertEquals(true, mcu.matches)
        assertEquals(false, ppm.matches)
        assertEquals(true, image.matches)
        assertEquals(2, listOf(mcu, ppm, image).count { it.matches == true })
        assertEquals("C585B71C", ppm.expectedCrc32)
        assertEquals("613D565F", ppm.actualCrc32)
    }

    @Test
    fun reportsUnknownWhenManifestDoesNotListAComponent() {
        val check = FirmwareManifestInspector.check(
            "rm217__07.30.ppm_c",
            "613D565F",
            emptyMap()
        )
        assertNull(check.expectedCrc32)
        assertNull(check.matches)
    }

    @Test
    fun rejectsDtdAndEntityDeclarations() {
        val xml = """<!DOCTYPE x [<!ENTITY local "value">]><x>&local;</x>""".toByteArray()
        assertFalse(runCatching { FirmwareManifestInspector.parse(xml) }.isSuccess)
    }

    private fun vplFixture(): ByteArray = """
        <?xml version="1.0" encoding="UTF-8"?>
        <VariantPackingList>
          <Variant>
            <VariantIdentification>
              <Description>RM-217 6300 EURO-C TR</Description>
              <ProductCode>0537625</ProductCode>
            </VariantIdentification>
            <SwVersion>07.30</SwVersion>
            <FileList>
              <File><Name>rm217__07.30.mcusw</Name><Crc>01C45DB7</Crc></File>
              <File><Name>rm217__07.30.ppm_c</Name><Crc>C585B71C</Crc></File>
              <File><Name>rm217__07.30.image_c_tr</Name><Crc>5008F91F</Crc></File>
            </FileList>
          </Variant>
        </VariantPackingList>
    """.trimIndent().toByteArray()
}

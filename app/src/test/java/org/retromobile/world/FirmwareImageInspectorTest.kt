package org.retromobile.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FirmwareImageInspectorTest {
    @Test
    fun acceptsNamedMcuswRegionInBb5Container() {
        val file = fixtureFile(".mcusw", fpsx("MCUSW"))
        val metadata = FirmwareImageInspector.inspect(file)

        assertEquals(FirmwareImageKind.CORE, metadata.kind)
        assertEquals("Nokia BB5 FPSX", metadata.format)
        assertTrue("MCUSW" in metadata.namedRegions)
        assertEquals(1, metadata.blockCount)
        file.delete()
    }

    @Test
    fun recognizesPpmAndContentPackNaming() {
        assertEquals(
            FirmwareImageKind.LANGUAGE_PACK,
            FirmwareImageInspector.classify("rm217__07.30.ppm_c")
        )
        assertEquals(
            FirmwareImageKind.CONTENT_PACK,
            FirmwareImageInspector.classify("rm217__07.30.image_c_tr")
        )
    }

    @Test
    fun acceptsBb5SpecialUnmappedAddress() {
        val file = fixtureFile(".ppm_c", fpsx("PAPUBKEYS", 0xFFFF_FFFFL))
        val metadata = FirmwareImageInspector.inspect(file)

        assertTrue("PAPUBKEYS" in metadata.namedRegions)
        assertEquals(1, metadata.blockCount)
        file.delete()
    }

    @Test
    fun stillRejectsOverflowForMappedFlashAddresses() {
        val file = fixtureFile(".ppm_c", fpsx("PPM", 0xFFFF_FFFEL))
        assertFalse(runCatching { FirmwareImageInspector.inspect(file) }.isSuccess)
        file.delete()
    }

    @Test
    fun rejectsMcuswWithoutOperatingSystemRegion() {
        val file = fixtureFile(".mcusw", fpsx("OTHER"))
        val error = runCatching { FirmwareImageInspector.inspect(file) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("MCUSW"))
        file.delete()
    }

    @Test
    fun rejectsNonFpsxAndTruncatedBlocks() {
        val notFpsx = fixtureFile(".ppm_c", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9))
        assertFalse(runCatching { FirmwareImageInspector.inspect(notFpsx) }.isSuccess)
        notFpsx.delete()

        val broken = fpsx("MCUSW").copyOf(32)
        val truncated = fixtureFile(".mcusw", broken)
        assertFalse(runCatching { FirmwareImageInspector.inspect(truncated) }.isSuccess)
        truncated.delete()
    }

    private fun fixtureFile(suffix: String, bytes: ByteArray): File =
        File.createTempFile("rm217-test-", suffix).apply {
            writeBytes(bytes)
            deleteOnExit()
        }

    private fun fpsx(regionName: String, address: Long = 0x8000_0000L): ByteArray {
        val out = ByteArrayOutputStream()
        // FPSX header: B2, header length (4 bytes), TLV count (4 bytes = zero).
        out.write(byteArrayOf(0xB2.toByte(), 0, 0, 0, 4, 0, 0, 0, 0))

        val name = regionName.toByteArray(Charsets.US_ASCII)
        require(name.size <= 12)
        out.write(0x5D)
        out.write(byteArrayOf(0x01, 0x27))
        out.write(ByteArray(20)) // SHA-1 field
        out.write('q'.code) // named-block marker
        out.write(name)
        out.write(ByteArray(12 - name.size))
        out.write(ByteArray(5)) // flags and CRC
        out.write(u32(4))
        out.write(u32(address))
        out.write(0) // block checksum placeholder
        out.write(byteArrayOf(1, 2, 3, 4))
        return out.toByteArray()
    }

    private fun u32(value: Long): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt()).array()
}

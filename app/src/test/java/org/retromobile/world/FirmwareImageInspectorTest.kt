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
            FirmwareImageInspector.classify("rm614__08.71.ppm_mh1")
        )
        assertEquals(
            FirmwareImageKind.CONTENT_PACK,
            FirmwareImageInspector.classify("rm614__08.71.image_mh1_059G792")
        )
    }

    @Test
    fun acceptsRm614FactoryContentImageAndItsSparseFatBootSector() {
        val file = fixtureFile(".image_mh1_059G792", contentImage())
        val metadata = FirmwareImageInspector.inspect(
            file,
            "rm614__08.71.image_mh1_059G792"
        )

        assertEquals(FirmwareImageKind.CONTENT_PACK, metadata.kind)
        assertEquals("Nokia RM-614 C: content image", metadata.format)
        assertEquals(1, metadata.blockCount)
        assertEquals(512L, metadata.payloadBytes)
        file.delete()
    }

    @Test
    fun acceptsMultipleSparseBlocksInOneFactoryContentImage() {
        val extraBlock = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val file = fixtureFile(
            ".image_mh1_059G792",
            contentImage(extraBlocks = listOf(4096L to extraBlock))
        )
        val metadata = FirmwareImageInspector.inspect(
            file,
            "rm614__08.71.image_mh1_059G792"
        )

        assertEquals(FirmwareImageKind.CONTENT_PACK, metadata.kind)
        assertEquals(2, metadata.blockCount)
        assertEquals(520L, metadata.payloadBytes)
        file.delete()
    }

    @Test
    fun rejectsContentImageWithoutFatBootSector() {
        val file = fixtureFile(
            ".image_mr_059B810",
            contentImage(bootAddress = 1L)
        )
        val error = runCatching {
            FirmwareImageInspector.inspect(file, "rm614__08.71.image_mr_059B810")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("boot sector"))
        file.delete()
    }

    @Test
    fun keepsLegacyFpsimageComponentsOnTheExistingFpsxPath() {
        val file = fixtureFile(".fpsimage", fpsx("CONTENT"))
        val metadata = FirmwareImageInspector.inspect(
            file,
            "rm217__07.30_sd_euro_512.fpsimage"
        )

        assertEquals(FirmwareImageKind.CONTENT_PACK, metadata.kind)
        assertEquals("Nokia BB5 FPSX", metadata.format)
        assertEquals(1, metadata.blockCount)
        file.delete()
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
    fun acceptsRm614PpmFlashAddressBeyond23Bits() {
        // RM-614's PPM image may be addressed above the 8 MiB / 23-bit boundary.
        // The FPSX parser must preserve the 32-bit target address instead of
        // rejecting a valid physical flash offset at container-inspection time.
        val file = fixtureFile(".ppm_c", fpsx("PPM", 0x01E0_0000L))
        val metadata = FirmwareImageInspector.inspect(file)

        assertTrue("PPM" in metadata.namedRegions)
        assertEquals(1, metadata.blockCount)
        file.delete()
    }

    @Test
    fun accepts32BitFlashAddressWhoseRegionCrossesUnsignedAddressBoundary() {
        // The FPSX address field is a u32 start address. Upstream c3emu consumes it
        // as metadata and maps named images in the boot layer; the generic container
        // inspector must not impose an extra address+length overflow rejection.
        val file = fixtureFile(".ppm_c", fpsx("PPM", 0xFFFF_FFFEL))
        val metadata = FirmwareImageInspector.inspect(file)

        assertTrue("PPM" in metadata.namedRegions)
        assertEquals(1, metadata.blockCount)
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

    private fun contentImage(
        bootAddress: Long = 0L,
        extraBlocks: List<Pair<Long, ByteArray>> = emptyList()
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // The IMAGE parser searches for its own sparse-volume block magic.
        // A leading 0x7B byte must not be mistaken for an FPSX block type.
        out.write(byteArrayOf(0xB2.toByte(), 0, 0, 0, 4, 0, 0, 0, 0, 0x7B))

        val boot = ByteArray(512)
        boot[11] = 0x00
        boot[12] = 0x02 // 512 bytes per sector
        val totalSectors = 150_656L
        for (index in 0 until 4) {
            boot[32 + index] = (totalSectors ushr (index * 8)).toByte()
        }
        boot[510] = 0x55
        boot[511] = 0xAA

        out.write(byteArrayOf(0x54, 0x01, 0x30, 0x15, 0x00, 0x03, 0x00))
        out.write(ByteArray(10))
        out.write(u32(boot.size.toLong()))
        out.write(u32(bootAddress))
        out.write(0) // header checksum placeholder
        out.write(boot)

        extraBlocks.forEach { (address, payload) ->
            out.write(byteArrayOf(0x54, 0x01, 0x30, 0x15, 0x00, 0x03, 0x00))
            out.write(ByteArray(10))
            out.write(u32(payload.size.toLong()))
            out.write(u32(address))
            out.write(0) // header checksum placeholder
            out.write(payload)
        }
        return out.toByteArray()
    }

    private fun u32(value: Long): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt()).array()
}

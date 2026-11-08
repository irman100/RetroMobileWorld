package org.retromobile.world

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

enum class FirmwareImageKind(val displayName: String) {
    CORE("MCU operating system"),
    LANGUAGE_PACK("Language pack (PPM)"),
    CONTENT_PACK("Factory content (IMAGE)")
}

data class FirmwareImageMetadata(
    val originalFileName: String,
    val kind: FirmwareImageKind,
    val format: String,
    val namedRegions: List<String>,
    val payloadBytes: Long,
    val blockCount: Int
)

/**
 * Structural reader for Nokia BB5 FPSX firmware containers.
 *
 * It validates container boundaries and records named image regions. It does not
 * decrypt, patch, extract, execute or redistribute firmware contents.
 */
object FirmwareImageInspector {
    const val MAX_FILE_BYTES = 256L * 1024L * 1024L
    private const val MAX_TLV_COUNT = 4096
    private const val MAX_BLOCK_COUNT = 32768
    private const val MAX_NAMED_REGION_BYTES = 64L * 1024L * 1024L

    fun inspect(file: File, originalFileName: String = file.name): FirmwareImageMetadata {
        require(file.isFile) { "The selected firmware component is not a file." }
        require(file.length() in 9L..MAX_FILE_BYTES) {
            "Firmware component must be between 9 bytes and 256 MiB."
        }
        val kind = classify(originalFileName)

        try {
            RandomAccessFile(file, "r").use { input ->
                require(input.readUnsignedByte() == 0xB2) {
                    "This is not a Nokia BB5 FPSX file (expected B2 header)."
                }
                val headerLength = readUInt32(input)
                val tlvCount = readUInt32(input)
                require(headerLength >= 4L) { "FPSX header length is invalid." }
                require(tlvCount <= MAX_TLV_COUNT) { "FPSX has too many header records." }

                val fileLength = input.length()
                val headerEnd = 5L + headerLength
                require(headerEnd in 9L..fileLength) { "FPSX header extends beyond the file." }

                var cursor = 9L
                repeat(tlvCount.toInt()) {
                    require(cursor + 2L <= headerEnd) { "FPSX header record is truncated." }
                    input.seek(cursor)
                    input.readUnsignedByte() // tag
                    val valueLength = input.readUnsignedByte()
                    cursor += 2L + valueLength
                    require(cursor <= headerEnd) { "FPSX header record exceeds the header." }
                }

                val names = linkedSetOf<String>()
                var payloadBytes = 0L
                var blockCount = 0
                cursor = headerEnd

                while (cursor < fileLength) {
                    input.seek(cursor)
                    val type = input.readUnsignedByte()
                    if (type == 0xFF) {
                        cursor += 1L
                        continue
                    }

                    blockCount += 1
                    require(blockCount <= MAX_BLOCK_COUNT) { "FPSX has too many data blocks." }

                    val lengthOffset: Long
                    val addressOffset: Long
                    val dataOffset: Long

                    when (type) {
                        0x54 -> {
                            // Raw/continuation block: 4-byte prefix, flags, CRC, unknown byte.
                            lengthOffset = cursor + 10L
                            addressOffset = cursor + 14L
                            dataOffset = cursor + 19L
                        }
                        0x5D -> {
                            // Named block: name is the 12-byte field beginning at offset +24.
                            require(cursor + 50L <= fileLength) { "FPSX named block header is truncated." }
                            input.seek(cursor + 24L)
                            val rawName = ByteArray(12)
                            input.readFully(rawName)
                            val name = rawName
                                .takeWhile { it.toInt() != 0 }
                                .toByteArray()
                                .toString(Charsets.US_ASCII)
                                .trim()
                            if (name.isNotEmpty()) names += name

                            lengthOffset = cursor + 41L
                            addressOffset = cursor + 45L
                            dataOffset = cursor + 50L
                        }
                        else -> throw IllegalArgumentException(
                            "Unsupported FPSX block type 0x${type.toString(16)} at offset 0x${cursor.toString(16)}."
                        )
                    }

                    require(dataOffset <= fileLength) { "FPSX data block header is truncated." }
                    input.seek(lengthOffset)
                    val dataLength = readUInt32(input)
                    require(dataLength <= MAX_FILE_BYTES) { "FPSX data block is too large." }
                    input.seek(addressOffset)
                    // Validate and consume the 32-bit address field, but do not reject
                    // a region because address + length crosses 0xFFFFFFFF. FPSX stores
                    // the start address as u32; the upstream c3emu parser accepts that
                    // field as-is and maps named MCUSW/PPM regions in its boot layer.
                    // Container safety is enforced by the file-boundary check below.
                    readUInt32(input)
                    require(dataOffset + dataLength <= fileLength) {
                        "FPSX data block extends beyond the file."
                    }

                    payloadBytes += dataLength
                    require(payloadBytes <= MAX_FILE_BYTES) { "FPSX expanded payload is too large." }
                    cursor = dataOffset + dataLength
                }

                require(blockCount > 0) { "FPSX contains no firmware data blocks." }
                if (kind == FirmwareImageKind.CORE) {
                    require("MCUSW" in names) {
                        "The selected .mcusw file does not contain the required MCUSW operating-system image."
                    }
                }

                return FirmwareImageMetadata(
                    originalFileName = originalFileName,
                    kind = kind,
                    format = "Nokia BB5 FPSX",
                    namedRegions = names.toList(),
                    payloadBytes = payloadBytes,
                    blockCount = blockCount
                )
            }
        } catch (error: IOException) {
            throw IllegalArgumentException("The firmware container is damaged or unreadable.", error)
        }
    }

    fun classify(fileName: String): FirmwareImageKind {
        val name = fileName.lowercase()
        return when {
            name.endsWith(".mcusw") -> FirmwareImageKind.CORE
            name.substringAfterLast('.', "").startsWith("ppm_") &&
                name.substringAfterLast('.', "").length > "ppm_".length -> FirmwareImageKind.LANGUAGE_PACK
            name.substringAfterLast('.', "").startsWith("image_") &&
                name.substringAfterLast('.', "").length > "image_".length -> FirmwareImageKind.CONTENT_PACK
            name.endsWith(".fpsimage") -> FirmwareImageKind.CONTENT_PACK
            else -> throw IllegalArgumentException(
                "Choose an extracted RM-217 .mcusw, .ppm_* or .image_* firmware component."
            )
        }
    }

    private fun readUInt32(input: RandomAccessFile): Long =
        input.readInt().toLong() and 0xFFFF_FFFFL

    private const val UNMAPPED_FLASH_ADDRESS = 0xFFFF_FFFFL
}

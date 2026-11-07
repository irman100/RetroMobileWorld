package org.retromobile.world

import java.io.File
import java.io.FileOutputStream
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LegacyPackageInspectorTest {
    @Test
    fun readsMidletMetadataFromJarManifest() {
        val file = createJar(
            "MIDlet-Name" to "Retro Demo",
            "MIDlet-Vendor" to "Example Studio",
            "MIDlet-Version" to "1.2.3",
            "MIDlet-1" to "Retro Demo, /icon.png, com.example.RetroDemo",
            "MIDlet-2" to "Second App, , com.example.SecondApp"
        )

        try {
            val metadata = LegacyPackageInspector.inspect(file)

            assertEquals(LegacyPackageKind.JAR, metadata.kind)
            assertEquals("Retro Demo", metadata.title)
            assertEquals("Example Studio", metadata.vendor)
            assertEquals("1.2.3", metadata.version)
            assertEquals(2, metadata.midlets.size)
            assertEquals("com.example.RetroDemo", metadata.midlets[0].className)
            assertEquals(null, metadata.midlets[1].icon)
        } finally {
            file.delete()
        }
    }

    @Test
    fun readsJarLocationFromJadDescriptor() {
        val file = File.createTempFile("retro-mobile-", ".jad")
        file.writeText(
            """
            MIDlet-Name: JAD Demo
            MIDlet-Vendor: Example Studio
            MIDlet-Version: 2.0
            MIDlet-1: JAD Demo, /demo.png, com.example.JadDemo
            MIDlet-Jar-URL: https://example.invalid/demo.jar
            """.trimIndent()
        )

        try {
            val metadata = LegacyPackageInspector.inspect(file)

            assertEquals(LegacyPackageKind.JAD, metadata.kind)
            assertEquals("JAD Demo", metadata.title)
            assertEquals("https://example.invalid/demo.jar", metadata.jarUrl)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsJarWithoutMidletEntry() {
        val file = createJar("MIDlet-Name" to "Not an app")

        try {
            try {
                LegacyPackageInspector.inspect(file)
                fail("A JAR without a MIDlet entry must be rejected.")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("MIDlet"))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsMalformedJadDescriptor() {
        val file = File.createTempFile("retro-mobile-bad-", ".jad")
        file.writeText("This line has no attribute separator")

        try {
            try {
                LegacyPackageInspector.inspect(file)
                fail("Malformed JAD data must be rejected.")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("malformed"))
            }
        } finally {
            file.delete()
        }
    }

    private fun createJar(vararg attributes: Pair<String, String>): File {
        val file = File.createTempFile("retro-mobile-", ".jar")
        val manifest = Manifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        attributes.forEach { (key, value) ->
            manifest.mainAttributes.putValue(key, value)
        }
        JarOutputStream(FileOutputStream(file), manifest).use { archive ->
            archive.putNextEntry(JarEntry("payload.txt"))
            archive.write("test".toByteArray())
            archive.closeEntry()
        }
        return file
    }
}

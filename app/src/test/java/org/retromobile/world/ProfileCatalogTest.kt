package org.retromobile.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCatalogTest {
    @Test
    fun builtInProfilesHaveUniqueIds() {
        val ids = ProfileCatalog.builtIn.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun symbianSmartphoneCanExposeBothRuntimeFamilies() {
        val n73 = ProfileCatalog.builtIn.single { it.id == "nokia-n73-s60" }
        assertEquals(setOf(RuntimeFamily.JAVA_ME, RuntimeFamily.SYMBIAN), n73.supportedRuntimes)
        assertEquals(RuntimeFamily.SYMBIAN, n73.defaultRuntime)
    }

    @Test
    fun symbianRuntimeRequiresUserSystemImage() {
        val targets = ProfileCatalog.builtIn.flatMap { it.runtimeTargets }
            .filter { it.family == RuntimeFamily.SYMBIAN }
        assertTrue(targets.isNotEmpty())
        assertTrue(targets.all { it.systemImageRequired })
    }

    @Test
    fun javaMeTargetsDoNotRequireSystemImage() {
        val targets = ProfileCatalog.builtIn.flatMap { it.runtimeTargets }
            .filter { it.family == RuntimeFamily.JAVA_ME }
        assertTrue(targets.isNotEmpty())
        assertTrue(targets.all { !it.systemImageRequired })
    }

    @Test
    fun allProfilesHaveCompleteCanonicalKeypads() {
        val required = setOf(
            CanonicalKey.UP, CanonicalKey.DOWN, CanonicalKey.LEFT, CanonicalKey.RIGHT,
            CanonicalKey.SELECT, CanonicalKey.SOFT_LEFT, CanonicalKey.SOFT_RIGHT,
            CanonicalKey.BACK, CanonicalKey.CALL, CanonicalKey.END,
            CanonicalKey.DIGIT_0, CanonicalKey.DIGIT_1, CanonicalKey.DIGIT_2,
            CanonicalKey.DIGIT_3, CanonicalKey.DIGIT_4, CanonicalKey.DIGIT_5,
            CanonicalKey.DIGIT_6, CanonicalKey.DIGIT_7, CanonicalKey.DIGIT_8,
            CanonicalKey.DIGIT_9, CanonicalKey.STAR, CanonicalKey.HASH
        )
        ProfileCatalog.builtIn.forEach { profile ->
            assertTrue(profile.keypad.controls.map { it.key }.containsAll(required))
            assertEquals(profile.keypad.controls.size, profile.keypad.controls.map { it.key }.toSet().size)
        }
    }

    @Test
    fun everyProfileHasMatchingDisplayApertureAndLogicalResolution() {
        ProfileCatalog.builtIn.forEach { profile ->
            val aperture = profile.shell.displayAperture
            val apertureRatio = aperture.width * profile.shell.layoutWidth /
                (aperture.height * profile.shell.layoutHeight)
            val displayRatio = profile.display.width.toFloat() / profile.display.height
            assertTrue(kotlin.math.abs(apertureRatio - displayRatio) < 0.02f)
        }
    }

    @Test
    fun profileHasEveryBackendItAdvertises() {
        val n73 = ProfileCatalog.builtIn.single { it.id == "nokia-n73-s60" }
        assertNotNull(n73.targetFor(RuntimeFamily.JAVA_ME))
        assertNotNull(n73.targetFor(RuntimeFamily.SYMBIAN))
    }
}

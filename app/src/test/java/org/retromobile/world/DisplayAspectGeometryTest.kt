package org.retromobile.world

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayAspectGeometryTest {
    @Test
    fun nokia6300DisplayKeepsPortraitThreeToFourRatioInStretchedHost() {
        assertNativeRatio(
            displayWidth = 240,
            displayHeight = 320,
            hostScaleX = 1.08f,
            hostScaleY = 0.96f
        )
    }

    @Test
    fun sonyEricssonDisplayKeepsItsOwnNativeRatio() {
        assertNativeRatio(
            displayWidth = 176,
            displayHeight = 220,
            hostScaleX = 1.08f,
            hostScaleY = 0.96f
        )
    }

    private fun assertNativeRatio(
        displayWidth: Int,
        displayHeight: Int,
        hostScaleX: Float,
        hostScaleY: Float
    ) {
        val designHeight = 467f
        val designWidth = DisplayAspectGeometry.designWidthForNativeAspect(
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            designHeight = designHeight,
            canvasScaleX = hostScaleX,
            canvasScaleY = hostScaleY
        )

        val actualPhysicalRatio =
            (designWidth * hostScaleX) / (designHeight * hostScaleY)
        val expectedPhysicalRatio = displayWidth.toFloat() / displayHeight.toFloat()

        assertEquals(expectedPhysicalRatio, actualPhysicalRatio, 0.00001f)
    }
}

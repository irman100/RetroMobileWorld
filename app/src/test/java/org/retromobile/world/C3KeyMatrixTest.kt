package org.retromobile.world

import org.junit.Assert.assertEquals
import org.junit.Test

class C3KeyMatrixTest {
    @Test
    fun navigationAndSoftKeysMatchPinnedUpstreamKeymap() {
        assertEquals(56, C3EmuEngine.matrixIndexFor(CanonicalKey.UP))
        assertEquals(57, C3EmuEngine.matrixIndexFor(CanonicalKey.DOWN))
        assertEquals(58, C3EmuEngine.matrixIndexFor(CanonicalKey.LEFT))
        assertEquals(59, C3EmuEngine.matrixIndexFor(CanonicalKey.RIGHT))
        assertEquals(60, C3EmuEngine.matrixIndexFor(CanonicalKey.SELECT))
        assertEquals(29, C3EmuEngine.matrixIndexFor(CanonicalKey.SOFT_LEFT))
        assertEquals(43, C3EmuEngine.matrixIndexFor(CanonicalKey.SOFT_RIGHT))
        assertEquals(30, C3EmuEngine.matrixIndexFor(CanonicalKey.CALL))
        assertEquals(255, C3EmuEngine.matrixIndexFor(CanonicalKey.END))
    }

    @Test
    fun dedicatedPhysicalHotspotsUseTheCorrectMatrixIndices() {
        assertEquals(38, C3KeyMatrix.CONTACTS)
        assertEquals(52, C3KeyMatrix.MESSAGING)
        assertEquals(50, C3KeyMatrix.FUNCTION)
        assertEquals(35, C3KeyMatrix.SHIFT)
        assertEquals(48, C3KeyMatrix.SYMBOL)
        assertEquals(49, C3KeyMatrix.SPACE)
        assertEquals(40, C3KeyMatrix.PUNCTUATION)
        assertEquals(41, C3KeyMatrix.COMMA_PERIOD)
        assertEquals(27, C3KeyMatrix.CTRL)
    }
}

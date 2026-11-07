package org.retromobile.world

import org.junit.Assert.assertEquals
import org.junit.Test

class C3EmuEngineTest {
    @Test
    fun mapsC3DpadAndSoftKeysToUpstreamMatrix() {
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
    fun mapsC3NumericKeysToUpstreamQwertyMatrix() {
        assertEquals(3, C3EmuEngine.matrixIndexFor(CanonicalKey.DIGIT_1))
        assertEquals(4, C3EmuEngine.matrixIndexFor(CanonicalKey.DIGIT_2))
        assertEquals(5, C3EmuEngine.matrixIndexFor(CanonicalKey.DIGIT_3))
        assertEquals(11, C3EmuEngine.matrixIndexFor(CanonicalKey.DIGIT_4))
        assertEquals(12, C3EmuEngine.matrixIndexFor(CanonicalKey.DIGIT_5))
        assertEquals(13, C3EmuEngine.matrixIndexFor(CanonicalKey.DIGIT_6))
        assertEquals(19, C3EmuEngine.matrixIndexFor(CanonicalKey.DIGIT_7))
        assertEquals(20, C3EmuEngine.matrixIndexFor(CanonicalKey.DIGIT_8))
        assertEquals(21, C3EmuEngine.matrixIndexFor(CanonicalKey.DIGIT_9))
        assertEquals(22, C3EmuEngine.matrixIndexFor(CanonicalKey.DIGIT_0))
    }

    @Test
    fun mapsQwertyCharactersToUpstreamMatrixPositions() {
        assertEquals(0, C3EmuEngine.matrixIndexForCharacter('q'))
        assertEquals(26, C3EmuEngine.matrixIndexForCharacter('P'))
        assertEquals(8, C3EmuEngine.matrixIndexForCharacter('a'))
        assertEquals(33, C3EmuEngine.matrixIndexForCharacter('L'))
        assertEquals(16, C3EmuEngine.matrixIndexForCharacter('z'))
        assertEquals(49, C3EmuEngine.matrixIndexForCharacter(' '))
    }

    @Test
    fun exposesTheRm614DisplayGeometry() {
        assertEquals(320, C3EmuEngine.LCD_WIDTH)
        assertEquals(240, C3EmuEngine.LCD_HEIGHT)
        assertEquals(44_100, C3EmuEngine.AUDIO_SAMPLE_RATE)
        assertEquals(2, C3EmuEngine.AUDIO_CHANNELS)
    }
}

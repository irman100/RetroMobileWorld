package org.retromobile.world

/**
 * Physical C3-00 keypad matrix indices, checked against the pinned upstream
 * c3emu web KEYMAP at revision 9b0d3ae2da326e109428f64d8760ea823ee1b648.
 * Matrix keys are row * 8 + column; 255 is the PMU power/end key.
 */
internal object C3KeyMatrix {
    const val SOFT_LEFT = 29
    const val SOFT_RIGHT = 43
    const val CONTACTS = 38
    const val MESSAGING = 52
    const val CALL = 30
    const val END = 255

    const val UP = 56
    const val DOWN = 57
    const val LEFT = 58
    const val RIGHT = 59
    const val SELECT = 60

    const val FUNCTION = 50
    const val SHIFT = 35
    const val SYMBOL = 48
    const val SPACE = 49
    const val PUNCTUATION = 40
    const val COMMA_PERIOD = 41
    const val CTRL = 27
}

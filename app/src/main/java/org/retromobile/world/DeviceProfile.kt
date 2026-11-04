package org.retromobile.world

enum class RuntimeFamily { JAVA_ME, SYMBIAN }

enum class DeviceOsFamily { NOKIA_S40, SONY_ERICSSON_CLASSIC, SYMBIAN_S60 }

enum class ScalingPolicy { PIXEL_PRESERVING_FIT, INTEGER_SCALE, FIT_LETTERBOX }

enum class CanonicalKey {
    UP, DOWN, LEFT, RIGHT, SELECT,
    SOFT_LEFT, SOFT_RIGHT, BACK, CALL, END,
    DIGIT_0, DIGIT_1, DIGIT_2, DIGIT_3, DIGIT_4,
    DIGIT_5, DIGIT_6, DIGIT_7, DIGIT_8, DIGIT_9,
    STAR, HASH
}

data class UnitRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    init {
        require(x >= 0f && y >= 0f) { "Rectangle origin must be non-negative" }
        require(width > 0f && height > 0f) { "Rectangle dimensions must be positive" }
        require(x + width <= 1.0001f && y + height <= 1.0001f) {
            "Rectangle must fit inside the normalized device canvas"
        }
    }

    fun contains(normalizedX: Float, normalizedY: Float): Boolean =
        normalizedX >= x && normalizedX <= x + width &&
            normalizedY >= y && normalizedY <= y + height
}

data class DisplaySpec(
    val width: Int,
    val height: Int,
    val scaling: ScalingPolicy = ScalingPolicy.PIXEL_PRESERVING_FIT
) {
    init {
        require(width in 48..2048) { "Display width is outside supported bounds" }
        require(height in 48..2048) { "Display height is outside supported bounds" }
    }
}

data class ShellSpec(
    val style: String,
    val skinAsset: String,
    val displayAperture: UnitRect,
    val layoutWidth: Int = 400,
    val layoutHeight: Int = 900
) {
    init {
        require(style.isNotBlank())
        require(skinAsset.isNotBlank())
        require(layoutWidth > 0 && layoutHeight > 0)
    }
}

enum class NavigationStyle { CROSS_DPAD, JOYSTICK, S60_RING }

enum class ControlVisual { HIT_AREA, SOFT_KEY, BACK_KEY, NUMERIC_KEY, CALL_KEY, END_KEY }

data class KeyControlSpec(
    val key: CanonicalKey,
    val bounds: UnitRect,
    val label: String,
    val visual: ControlVisual
) {
    init { require(label.isNotBlank()) }
}

data class KeypadSpec(
    val layout: String,
    val mappingAsset: String,
    val navigationStyle: NavigationStyle,
    val navigationBounds: UnitRect,
    val controls: List<KeyControlSpec>
) {
    init {
        require(layout.isNotBlank())
        require(mappingAsset.isNotBlank())
        require(controls.isNotEmpty())
        require(controls.map { it.key }.distinct().size == controls.size) {
            "A keypad cannot map the same canonical key more than once"
        }
        val requiredKeys = setOf(
            CanonicalKey.UP, CanonicalKey.DOWN, CanonicalKey.LEFT, CanonicalKey.RIGHT,
            CanonicalKey.SELECT, CanonicalKey.SOFT_LEFT, CanonicalKey.SOFT_RIGHT,
            CanonicalKey.BACK, CanonicalKey.CALL, CanonicalKey.END,
            CanonicalKey.DIGIT_0, CanonicalKey.DIGIT_1, CanonicalKey.DIGIT_2,
            CanonicalKey.DIGIT_3, CanonicalKey.DIGIT_4, CanonicalKey.DIGIT_5,
            CanonicalKey.DIGIT_6, CanonicalKey.DIGIT_7, CanonicalKey.DIGIT_8,
            CanonicalKey.DIGIT_9, CanonicalKey.STAR, CanonicalKey.HASH
        )
        require(controls.map { it.key }.containsAll(requiredKeys)) {
            "Keypad layout is missing one or more required phone controls"
        }
    }
}

data class RuntimeTarget(
    val family: RuntimeFamily,
    val backendId: String,
    val systemImageRequired: Boolean,
    val compatibilityHint: String
) {
    init {
        require(backendId.isNotBlank())
        require(compatibilityHint.isNotBlank())
        require(family != RuntimeFamily.SYMBIAN || systemImageRequired) {
            "A Symbian runtime target must declare its system-image requirement"
        }
    }
}

data class DeviceProfile(
    val id: String,
    val displayName: String,
    val compatibilityLabel: String,
    val osFamily: DeviceOsFamily,
    val display: DisplaySpec,
    val shell: ShellSpec,
    val keypad: KeypadSpec,
    val runtimeTargets: List<RuntimeTarget>,
    val defaultRuntime: RuntimeFamily
) {
    val supportedRuntimes: Set<RuntimeFamily>
        get() = runtimeTargets.map { it.family }.toSet()

    init {
        require(id.matches(Regex("^[a-z0-9][a-z0-9-]+$"))) { "Invalid profile id: $id" }
        require(displayName.isNotBlank())
        require(runtimeTargets.isNotEmpty())
        require(runtimeTargets.map { it.family }.distinct().size == runtimeTargets.size) {
            "A profile cannot declare the same runtime more than once"
        }
        require(defaultRuntime in supportedRuntimes) {
            "Default runtime must be declared in runtimeTargets"
        }
        val apertureRatio = (shell.displayAperture.width * shell.layoutWidth) /
            (shell.displayAperture.height * shell.layoutHeight)
        val logicalRatio = display.width.toFloat() / display.height.toFloat()
        require(kotlin.math.abs(apertureRatio - logicalRatio) < 0.02f) {
            "Display aperture ratio must match the logical display ratio"
        }
    }

    fun targetFor(runtime: RuntimeFamily): RuntimeTarget? = runtimeTargets.firstOrNull { it.family == runtime }
}

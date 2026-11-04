package org.retromobile.world

object ProfileCatalog {
    private fun commonControls(
        numericStartX: Float,
        numericStartY: Float,
        numericGapX: Float,
        numericGapY: Float,
        numericWidth: Float,
        numericHeight: Float,
        softY: Float,
        callY: Float
    ): List<KeyControlSpec> {
        val controls = mutableListOf(
            KeyControlSpec(CanonicalKey.SOFT_LEFT, UnitRect(0.21f, softY, 0.165f, 0.043f), "PROFILE", ControlVisual.SOFT_KEY),
            KeyControlSpec(CanonicalKey.SOFT_RIGHT, UnitRect(0.625f, softY, 0.165f, 0.043f), "OPEN", ControlVisual.SOFT_KEY),
            KeyControlSpec(CanonicalKey.UP, UnitRect(0.4625f, 0.565f, 0.075f, 0.055f), "↑", ControlVisual.HIT_AREA),
            KeyControlSpec(CanonicalKey.DOWN, UnitRect(0.4625f, 0.645f, 0.075f, 0.055f), "↓", ControlVisual.HIT_AREA),
            KeyControlSpec(CanonicalKey.LEFT, UnitRect(0.375f, 0.605f, 0.0875f, 0.045f), "←", ControlVisual.HIT_AREA),
            KeyControlSpec(CanonicalKey.RIGHT, UnitRect(0.5375f, 0.605f, 0.0875f, 0.045f), "→", ControlVisual.HIT_AREA),
            KeyControlSpec(CanonicalKey.SELECT, UnitRect(0.455f, 0.605f, 0.09f, 0.045f), "OK", ControlVisual.HIT_AREA),
            KeyControlSpec(CanonicalKey.BACK, UnitRect(0.205f, 0.605f, 0.075f, 0.045f), "C", ControlVisual.BACK_KEY),
            KeyControlSpec(CanonicalKey.CALL, UnitRect(0.23f, callY, 0.1475f, 0.033f), "CALL", ControlVisual.CALL_KEY),
            KeyControlSpec(CanonicalKey.END, UnitRect(0.6225f, callY, 0.1475f, 0.033f), "END", ControlVisual.END_KEY)
        )
        val digitKeys = listOf(
            CanonicalKey.DIGIT_1, CanonicalKey.DIGIT_2, CanonicalKey.DIGIT_3,
            CanonicalKey.DIGIT_4, CanonicalKey.DIGIT_5, CanonicalKey.DIGIT_6,
            CanonicalKey.DIGIT_7, CanonicalKey.DIGIT_8, CanonicalKey.DIGIT_9,
            CanonicalKey.STAR, CanonicalKey.DIGIT_0, CanonicalKey.HASH
        )
        val labels = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
        digitKeys.forEachIndexed { index, key ->
            val column = index % 3
            val row = index / 3
            controls += KeyControlSpec(
                key = key,
                bounds = UnitRect(
                    x = numericStartX + column * numericGapX,
                    y = numericStartY + row * numericGapY,
                    width = numericWidth,
                    height = numericHeight
                ),
                label = labels[index],
                visual = ControlVisual.NUMERIC_KEY
            )
        }
        return controls
    }

    val builtIn: List<DeviceProfile> = listOf(
        DeviceProfile(
            id = "nokia-6300-s40",
            displayName = "Nokia 6300 style",
            compatibilityLabel = "Nokia 6300 / S40 family",
            osFamily = DeviceOsFamily.NOKIA_S40,
            display = DisplaySpec(240, 320),
            shell = ShellSpec(
                style = "classic-candybar",
                skinAsset = "builtin:nokia-s40-neutral",
                displayAperture = UnitRect(0.22f, 0.098f, 0.56f, 0.3319f)
            ),
            keypad = KeypadSpec(
                layout = "classic-numeric",
                mappingAsset = "keymaps/s40-classic.json",
                navigationStyle = NavigationStyle.CROSS_DPAD,
                navigationBounds = UnitRect(0.375f, 0.565f, 0.25f, 0.145f),
                controls = commonControls(0.245f, 0.733f, 0.1875f, 0.0533f, 0.135f, 0.038f, 0.506f, 0.678f)
            ),
            runtimeTargets = listOf(
                RuntimeTarget(RuntimeFamily.JAVA_ME, "j2me-loader", false, "CLDC 1.1 / MIDP 2.0")
            ),
            defaultRuntime = RuntimeFamily.JAVA_ME
        ),
        DeviceProfile(
            id = "sony-ericsson-k750-style",
            displayName = "Sony Ericsson K750i style",
            compatibilityLabel = "Sony Ericsson K750i family",
            osFamily = DeviceOsFamily.SONY_ERICSSON_CLASSIC,
            display = DisplaySpec(176, 220),
            shell = ShellSpec(
                style = "se-classic-candybar",
                skinAsset = "builtin:sony-ericsson-neutral",
                displayAperture = UnitRect(0.22f, 0.098f, 0.56f, 0.3111f)
            ),
            keypad = KeypadSpec(
                layout = "sony-ericsson-classic",
                mappingAsset = "keymaps/sony-ericsson-classic.json",
                navigationStyle = NavigationStyle.JOYSTICK,
                navigationBounds = UnitRect(0.3675f, 0.565f, 0.265f, 0.125f),
                controls = commonControls(0.2575f, 0.737f, 0.18f, 0.0533f, 0.125f, 0.038f, 0.506f, 0.693f)
            ),
            runtimeTargets = listOf(
                RuntimeTarget(RuntimeFamily.JAVA_ME, "j2me-loader", false, "CLDC 1.1 / MIDP 2.0")
            ),
            defaultRuntime = RuntimeFamily.JAVA_ME
        ),
        DeviceProfile(
            id = "nokia-n73-s60",
            displayName = "Nokia N73 style",
            compatibilityLabel = "Nokia N73 / S60 3rd Edition family",
            osFamily = DeviceOsFamily.SYMBIAN_S60,
            display = DisplaySpec(240, 320),
            shell = ShellSpec(
                style = "s60-smartphone-candybar",
                skinAsset = "builtin:nokia-s60-neutral",
                displayAperture = UnitRect(0.22f, 0.098f, 0.56f, 0.3319f)
            ),
            keypad = KeypadSpec(
                layout = "s60-numeric",
                mappingAsset = "keymaps/s60-classic.json",
                navigationStyle = NavigationStyle.S60_RING,
                navigationBounds = UnitRect(0.38f, 0.56f, 0.24f, 0.15f),
                controls = commonControls(0.245f, 0.733f, 0.1875f, 0.0511f, 0.135f, 0.037f, 0.506f, 0.694f)
            ),
            runtimeTargets = listOf(
                RuntimeTarget(RuntimeFamily.JAVA_ME, "j2me-loader", false, "MIDP 2.0 on an S60-sized display")
            ),
            defaultRuntime = RuntimeFamily.JAVA_ME
        )
    )
}

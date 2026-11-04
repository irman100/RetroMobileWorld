package org.retromobile.world

import org.json.JSONArray
import org.json.JSONObject

/** Parses the repository's versioned device-profile registry using Android's built-in JSON API. */
object ProfileJsonLoader {
    fun parse(rawJson: String): List<DeviceProfile> {
        val root = JSONObject(rawJson)
        require(root.getInt("schemaVersion") == 1) { "Unsupported device profile schema version" }
        val profiles = root.getJSONArray("profiles")
        require(profiles.length() > 0) { "Device profile registry is empty" }
        return buildList(profiles.length()) {
            for (index in 0 until profiles.length()) add(parseProfile(profiles.getJSONObject(index)))
        }.also { list ->
            require(list.map { it.id }.distinct().size == list.size) { "Duplicate device profile IDs" }
        }
    }

    private fun parseProfile(json: JSONObject): DeviceProfile {
        val displayJson = json.getJSONObject("display")
        val shellJson = json.getJSONObject("shell")
        val keypadJson = json.getJSONObject("keypad")
        val navigationJson = keypadJson.getJSONObject("navigation")

        val display = DisplaySpec(
            width = displayJson.getInt("width"),
            height = displayJson.getInt("height"),
            scaling = ScalingPolicy.valueOf(displayJson.getString("scaling").uppercase())
        )
        val shell = ShellSpec(
            style = shellJson.getString("style"),
            skinAsset = shellJson.getString("skinAsset"),
            displayAperture = parseRect(shellJson.getJSONObject("displayAperture")),
            layoutWidth = shellJson.optInt("layoutWidth", 400),
            layoutHeight = shellJson.optInt("layoutHeight", 900)
        )
        val controlsJson = keypadJson.getJSONArray("controls")
        val controls = buildList(controlsJson.length()) {
            for (index in 0 until controlsJson.length()) {
                val item = controlsJson.getJSONObject(index)
                add(
                    KeyControlSpec(
                        key = CanonicalKey.valueOf(item.getString("key")),
                        bounds = parseRect(item.getJSONObject("bounds")),
                        label = item.getString("label"),
                        visual = ControlVisual.valueOf(item.getString("visual").uppercase())
                    )
                )
            }
        }
        val keypad = KeypadSpec(
            layout = keypadJson.getString("layout"),
            mappingAsset = keypadJson.getString("mapping"),
            navigationStyle = NavigationStyle.valueOf(navigationJson.getString("style").uppercase()),
            navigationBounds = parseRect(navigationJson.getJSONObject("bounds")),
            controls = controls
        )

        val targetsJson = json.getJSONArray("runtimeTargets")
        val targets = buildList(targetsJson.length()) {
            for (index in 0 until targetsJson.length()) {
                val item = targetsJson.getJSONObject(index)
                add(
                    RuntimeTarget(
                        family = RuntimeFamily.valueOf(item.getString("family")),
                        backendId = item.getString("backend"),
                        systemImageRequired = item.getBoolean("systemImageRequired"),
                        compatibilityHint = item.getString("compatibilityHint")
                    )
                )
            }
        }

        return DeviceProfile(
            id = json.getString("id"),
            displayName = json.getString("displayName"),
            compatibilityLabel = json.getString("compatibilityLabel"),
            osFamily = DeviceOsFamily.valueOf(json.getString("osFamily")),
            display = display,
            shell = shell,
            keypad = keypad,
            runtimeTargets = targets,
            defaultRuntime = RuntimeFamily.valueOf(json.getString("defaultRuntime"))
        )
    }

    private fun parseRect(json: JSONObject): UnitRect = UnitRect(
        x = json.getDouble("x").toFloat(),
        y = json.getDouble("y").toFloat(),
        width = json.getDouble("width").toFloat(),
        height = json.getDouble("height").toFloat()
    )
}

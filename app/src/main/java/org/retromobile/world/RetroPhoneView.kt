package org.retromobile.world

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * Interactive phone shell. The Java application library is drawn inside the virtual display.
 * Runtime execution is delegated through callbacks so the shell does not depend on an engine API.
 */
class RetroPhoneView(
    context: Context,
    private val profiles: List<DeviceProfile> = ProfileCatalog.builtIn
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var profileIndex = 0
    private var selectedItem = 0
    private var activeRuntime = profile.defaultRuntime
    private var detailMessage: String? = null
    private var showingJavaLibrary = false
    private var javaLibrarySelection = 0
    private var javaPackages: List<LegacyPackageRecord> = emptyList()

    var onImportJavaAppRequested: (() -> Unit)? = null
    var onLaunchJavaApp: ((LegacyPackageRecord) -> Unit)? = null

    private val profile: DeviceProfile
        get() = profiles[profileIndex]

    private val menuItems = listOf("Java applications", "WAP browser", "Games", "Settings")

    init {
        require(profiles.isNotEmpty()) { "At least one device profile is required" }
        contentDescription = "Retro Mobile World virtual phone"
        isFocusable = true
    }

    fun updateJavaPackages(packages: List<LegacyPackageRecord>) {
        javaPackages = packages
        javaLibrarySelection = javaLibrarySelection.coerceIn(
            0,
            (javaPackages.size - 1).coerceAtLeast(0)
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width / DESIGN_WIDTH, height / DESIGN_HEIGHT)
        if (scale <= 0f) return
        val left = (width - DESIGN_WIDTH * scale) / 2f
        val top = (height - DESIGN_HEIGHT * scale) / 2f
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        drawPhone(canvas)
        canvas.restore()
    }

    private fun drawPhone(canvas: Canvas) {
        val bodyColor = when (profile.osFamily) {
            DeviceOsFamily.NOKIA_S40 -> 0xFF66747A.toInt()
            DeviceOsFamily.SONY_ERICSSON_CLASSIC -> 0xFF778187.toInt()
            DeviceOsFamily.SYMBIAN_S60 -> 0xFF344B57.toInt()
        }
        paint.color = 0xFF101820.toInt()
        canvas.drawRoundRect(RectF(28f, 20f, 372f, 880f), 42f, 42f, paint)
        paint.color = bodyColor
        canvas.drawRoundRect(RectF(38f, 30f, 362f, 870f), 36f, 36f, paint)

        val screen = profile.shell.displayAperture.toCanvasRect()
        paint.color = 0xFF0B1115.toInt()
        canvas.drawRoundRect(
            RectF(screen.left - 12f, screen.top - 12f, screen.right + 12f, screen.bottom + 12f),
            14f,
            14f,
            paint
        )
        paint.color = 0xFF101820.toInt()
        canvas.drawRoundRect(RectF(164f, screen.top - 39f, 236f, screen.top - 31f), 4f, 4f, paint)
        canvas.drawCircle(270f, screen.top - 35f, 5f, paint)
        if (profile.osFamily == DeviceOsFamily.SONY_ERICSSON_CLASSIC) {
            paint.color = 0xFF17242A.toInt()
            canvas.drawCircle(306f, screen.top - 35f, 9f, paint)
        }

        drawScreen(canvas, screen)
        drawNavigationAssembly(canvas)
        drawKeyControls(canvas)

        paint.color = 0xFFCED7D9.toInt()
        paint.textSize = 16f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(profile.displayName, 200f, 844f, paint)
        paint.textSize = 10f
        canvas.drawText("JAVA ME · MIDP 2.0", 200f, 866f, paint)
    }

    private data class ScreenPalette(
        val top: Int,
        val bottom: Int,
        val status: Int,
        val accent: Int,
        val text: Int,
        val muted: Int,
        val selected: Int,
        val panel: Int,
        val iconColors: List<Int>
    )

    private fun screenPalette(): ScreenPalette = when (profile.osFamily) {
        DeviceOsFamily.NOKIA_S40 -> ScreenPalette(
            top = 0xFF1D6C9B.toInt(),
            bottom = 0xFF06182E.toInt(),
            status = 0xE610263A.toInt(),
            accent = 0xFF25A8D7.toInt(),
            text = 0xFFF5FAFF.toInt(),
            muted = 0xFFB9D5E7.toInt(),
            selected = 0xFF087FB7.toInt(),
            panel = 0x99304F69.toInt(),
            iconColors = listOf(
                0xFF1599D0.toInt(),
                0xFF16A6A0.toInt(),
                0xFFE29A35.toInt(),
                0xFF6B80D5.toInt()
            )
        )
        DeviceOsFamily.SONY_ERICSSON_CLASSIC -> ScreenPalette(
            top = 0xFF762433.toInt(),
            bottom = 0xFF160D14.toInt(),
            status = 0xE6120A10.toInt(),
            accent = 0xFFE0445C.toInt(),
            text = 0xFFFFF7F7.toInt(),
            muted = 0xFFE2C4CB.toInt(),
            selected = 0xFFB5233D.toInt(),
            panel = 0xA52A111B.toInt(),
            iconColors = listOf(
                0xFFCB324A.toInt(),
                0xFF7A8DA9.toInt(),
                0xFFB77A35.toInt(),
                0xFF687DAA.toInt()
            )
        )
        DeviceOsFamily.SYMBIAN_S60 -> ScreenPalette(
            top = 0xFF3C79B2.toInt(),
            bottom = 0xFF09264D.toInt(),
            status = 0xE6102B4B.toInt(),
            accent = 0xFF3B94D0.toInt(),
            text = 0xFFF5FAFF.toInt(),
            muted = 0xFFBED7EB.toInt(),
            selected = 0xFF267BB8.toInt(),
            panel = 0x99304D6B.toInt(),
            iconColors = listOf(
                0xFF3296C8.toInt(),
                0xFF42A58E.toInt(),
                0xFFD59A45.toInt(),
                0xFF718CD1.toInt()
            )
        )
    }

    private fun drawScreen(canvas: Canvas, screen: RectF) {
        canvas.save()
        canvas.clipRect(screen)
        val logicalHeight = profile.display.height * 240f / profile.display.width
        val scale = screen.width() / 240f
        canvas.translate(screen.left, screen.top)
        canvas.scale(scale, scale)

        val palette = screenPalette()
        drawWallpaper(canvas, logicalHeight, palette)
        drawStatusBar(canvas, palette)

        if (showingJavaLibrary) {
            drawJavaLibraryScreen(canvas, logicalHeight, palette)
        } else {
            drawHomeScreen(canvas, logicalHeight, palette)
        }
        canvas.restore()
    }

    private fun drawWallpaper(canvas: Canvas, logicalHeight: Float, palette: ScreenPalette) {
        paint.shader = LinearGradient(
            0f, 0f, 0f, logicalHeight,
            palette.top, palette.bottom, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, 240f, logicalHeight, paint)
        paint.shader = null

        when (profile.osFamily) {
            DeviceOsFamily.NOKIA_S40 -> {
                val wave = Path().apply {
                    moveTo(0f, logicalHeight * 0.46f)
                    cubicTo(58f, logicalHeight * 0.34f, 142f, logicalHeight * 0.69f, 240f, logicalHeight * 0.43f)
                    lineTo(240f, logicalHeight * 0.68f)
                    cubicTo(158f, logicalHeight * 0.86f, 72f, logicalHeight * 0.59f, 0f, logicalHeight * 0.83f)
                    close()
                }
                paint.color = 0x4425B7E8.toInt()
                canvas.drawPath(wave, paint)
            }
            DeviceOsFamily.SONY_ERICSSON_CLASSIC -> {
                val ribbon = Path().apply {
                    moveTo(0f, logicalHeight * 0.24f)
                    lineTo(240f, logicalHeight * 0.06f)
                    lineTo(240f, logicalHeight * 0.22f)
                    lineTo(0f, logicalHeight * 0.43f)
                    close()
                }
                paint.color = 0x42F0445C.toInt()
                canvas.drawPath(ribbon, paint)
                val secondRibbon = Path().apply {
                    moveTo(0f, logicalHeight * 0.63f)
                    lineTo(240f, logicalHeight * 0.39f)
                    lineTo(240f, logicalHeight * 0.52f)
                    lineTo(0f, logicalHeight * 0.78f)
                    close()
                }
                paint.color = 0x24FF8795.toInt()
                canvas.drawPath(secondRibbon, paint)
            }
            DeviceOsFamily.SYMBIAN_S60 -> {
                val wave = Path().apply {
                    moveTo(0f, logicalHeight * 0.40f)
                    cubicTo(62f, logicalHeight * 0.22f, 155f, logicalHeight * 0.55f, 240f, logicalHeight * 0.34f)
                    lineTo(240f, logicalHeight * 0.49f)
                    cubicTo(152f, logicalHeight * 0.73f, 84f, logicalHeight * 0.42f, 0f, logicalHeight * 0.65f)
                    close()
                }
                paint.color = 0x353FC0F3.toInt()
                canvas.drawPath(wave, paint)
            }
        }
    }

    private fun drawStatusBar(canvas: Canvas, palette: ScreenPalette) {
        paint.color = palette.status
        canvas.drawRect(0f, 0f, 240f, 22f, paint)
        paint.color = palette.text
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 8f
        val deviceLabel = when (profile.osFamily) {
            DeviceOsFamily.NOKIA_S40 -> "NOKIA · JAVA"
            DeviceOsFamily.SONY_ERICSSON_CLASSIC -> "SONY ERICSSON"
            DeviceOsFamily.SYMBIAN_S60 -> "S60 · JAVA"
        }
        canvas.drawText(deviceLabel, 7f, 14f, paint)

        for (bar in 0 until 4) {
            val barHeight = 4f + bar * 2.5f
            val left = 180f + bar * 5f
            canvas.drawRoundRect(RectF(left, 16f - barHeight, left + 3f, 16f), 1f, 1f, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        canvas.drawRoundRect(RectF(205f, 6f, 226f, 16f), 2f, 2f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRect(227f, 9f, 230f, 13f, paint)
        paint.color = 0xFF75D28D.toInt()
        canvas.drawRect(207f, 8f, 222f, 14f, paint)
    }

    private fun drawHomeScreen(canvas: Canvas, logicalHeight: Float, palette: ScreenPalette) {
        val now = Date()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val date = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
            .format(now)
            .uppercase(Locale.getDefault())

        paint.color = palette.text
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 29f
        canvas.drawText(time, 120f, 66f, paint)
        paint.color = palette.muted
        paint.textSize = 9f
        canvas.drawText(date, 120f, 82f, paint)

        paint.color = palette.panel
        canvas.drawRoundRect(RectF(7f, 99f, 233f, logicalHeight - 23f), 7f, 7f, paint)
        paint.color = palette.text
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 10f
        val heading = when (profile.osFamily) {
            DeviceOsFamily.NOKIA_S40 -> "MAIN MENU"
            DeviceOsFamily.SONY_ERICSSON_CLASSIC -> "MENU"
            DeviceOsFamily.SYMBIAN_S60 -> "APPLICATIONS"
        }
        canvas.drawText(heading, 14f, 116f, paint)

        val labels = listOf("Java apps", "Browser", "Games", "Settings")
        val glyphs = listOf("J", "W", "G", "S")
        menuItems.forEachIndexed { index, item ->
            val column = index % 2
            val row = index / 2
            val left = 14f + column * 112f
            val top = 130f + row * 67f
            if (index == selectedItem) {
                paint.color = palette.selected
                canvas.drawRoundRect(RectF(left - 3f, top - 3f, left + 101f, top + 56f), 6f, 6f, paint)
            }
            paint.color = palette.iconColors[index % palette.iconColors.size]
            canvas.drawRoundRect(RectF(left + 4f, top + 3f, left + 34f, top + 33f), 7f, 7f, paint)
            paint.color = 0xFFFFFFFF.toInt()
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 17f
            canvas.drawText(glyphs[index], left + 19f, top + 24f, paint)
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = 9f
            paint.color = palette.text
            canvas.drawText(labels[index], left + 40f, top + 21f, paint)
        }

        detailMessage?.let {
            paint.color = palette.muted
            paint.textSize = 8f
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(it.take(34), 12f, logicalHeight - 29f, paint)
        }
        drawSoftKeyBar(canvas, logicalHeight, palette, "Profiles", "Open")
    }

    private fun drawJavaLibraryScreen(canvas: Canvas, logicalHeight: Float, palette: ScreenPalette) {
        paint.color = palette.text
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 13f
        canvas.drawText("Java applications", 10f, 48f, paint)
        paint.color = palette.muted
        paint.textSize = 8f
        canvas.drawText(javaPackages.size.toString() + " APPS INSTALLED", 11f, 62f, paint)

        if (javaPackages.isEmpty()) {
            paint.color = palette.panel
            canvas.drawRoundRect(RectF(8f, 82f, 232f, logicalHeight - 29f), 7f, 7f, paint)
            paint.color = palette.accent
            canvas.drawCircle(120f, 133f, 18f, paint)
            paint.color = palette.text
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 25f
            canvas.drawText("+", 120f, 142f, paint)
            paint.textSize = 10f
            canvas.drawText("No Java apps yet", 120f, 174f, paint)
            paint.color = palette.muted
            paint.textSize = 8f
            canvas.drawText("Import a JAR or JAD package", 120f, 190f, paint)
        } else {
            val firstVisible = (javaLibrarySelection / MAX_VISIBLE_APPS) * MAX_VISIBLE_APPS
            javaPackages.drop(firstVisible).take(MAX_VISIBLE_APPS).forEachIndexed { row, record ->
                val absoluteIndex = firstVisible + row
                val y = 94f + row * 37f
                val selected = absoluteIndex == javaLibrarySelection
                paint.color = if (selected) palette.selected else palette.panel
                canvas.drawRoundRect(RectF(5f, y - 18f, 235f, y + 13f), 5f, 5f, paint)
                paint.color = palette.iconColors[absoluteIndex % palette.iconColors.size]
                canvas.drawRoundRect(RectF(10f, y - 12f, 31f, y + 8f), 4f, 4f, paint)
                paint.color = 0xFFFFFFFF.toInt()
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = 9f
                canvas.drawText("J", 20.5f, y + 2f, paint)
                paint.textAlign = Paint.Align.LEFT
                paint.color = palette.text
                paint.textSize = 10f
                canvas.drawText(record.metadata.title.take(27), 37f, y - 2f, paint)
                paint.color = palette.muted
                paint.textSize = 7f
                canvas.drawText(
                    record.metadata.vendor.take(27) + " · " + record.metadata.version,
                    37f,
                    y + 9f,
                    paint
                )
            }
        }
        drawSoftKeyBar(canvas, logicalHeight, palette, "Import", "Run")
    }

    private fun drawSoftKeyBar(
        canvas: Canvas,
        logicalHeight: Float,
        palette: ScreenPalette,
        leftLabel: String,
        rightLabel: String
    ) {
        paint.color = 0xD90A1019.toInt()
        canvas.drawRect(0f, logicalHeight - 20f, 240f, logicalHeight, paint)
        paint.color = palette.text
        paint.textSize = 9f
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(leftLabel, 8f, logicalHeight - 7f, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(rightLabel, 232f, logicalHeight - 7f, paint)
    }

    private fun drawNavigationAssembly(canvas: Canvas) {
        val bounds = profile.keypad.navigationBounds.toCanvasRect()
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        when (profile.keypad.navigationStyle) {
            NavigationStyle.CROSS_DPAD -> {
                paint.color = 0xFF1A252B.toInt()
                canvas.drawCircle(cx, cy, bounds.width() * 0.46f, paint)
                paint.color = 0xFF87969C.toInt()
                canvas.drawRoundRect(RectF(cx - 15f, bounds.top, cx + 15f, bounds.bottom), 9f, 9f, paint)
                canvas.drawRoundRect(RectF(bounds.left, cy - 15f, bounds.right, cy + 15f), 9f, 9f, paint)
                paint.color = 0xFF34434A.toInt()
                canvas.drawCircle(cx, cy, 18f, paint)
            }
            NavigationStyle.JOYSTICK -> {
                paint.color = 0xFF1A252B.toInt()
                canvas.drawCircle(cx, cy, bounds.width() * 0.45f, paint)
                paint.color = 0xFF9AA7AC.toInt()
                canvas.drawCircle(cx, cy, bounds.width() * 0.23f, paint)
                paint.color = 0xFF34434A.toInt()
                canvas.drawCircle(cx, cy, bounds.width() * 0.13f, paint)
            }
            NavigationStyle.S60_RING -> {
                paint.color = 0xFF17262D.toInt()
                canvas.drawCircle(cx, cy, bounds.width() * 0.48f, paint)
                paint.color = 0xFF9BA9AE.toInt()
                canvas.drawRoundRect(RectF(cx - 16f, bounds.top, cx + 16f, bounds.bottom), 12f, 12f, paint)
                canvas.drawRoundRect(RectF(bounds.left, cy - 16f, bounds.right, cy + 16f), 12f, 12f, paint)
                paint.color = 0xFF34434A.toInt()
                canvas.drawCircle(cx, cy, 20f, paint)
            }
        }
    }

    private fun drawKeyControls(canvas: Canvas) {
        profile.keypad.controls.filter { it.visual != ControlVisual.HIT_AREA }.forEach { control ->
            val rect = control.bounds.toCanvasRect()
            when (control.visual) {
                ControlVisual.HIT_AREA -> Unit
                ControlVisual.SOFT_KEY, ControlVisual.NUMERIC_KEY, ControlVisual.BACK_KEY -> {
                    paint.color = when (control.visual) {
                        ControlVisual.SOFT_KEY -> 0xFF1A252B.toInt()
                        ControlVisual.BACK_KEY -> 0xFF293940.toInt()
                        else -> 0xFF17232A.toInt()
                    }
                    canvas.drawRoundRect(rect, 10f, 10f, paint)
                }
                ControlVisual.CALL_KEY -> {
                    paint.color = 0xFF244B3D.toInt()
                    canvas.drawRoundRect(rect, 10f, 10f, paint)
                }
                ControlVisual.END_KEY -> {
                    paint.color = 0xFF583B3B.toInt()
                    canvas.drawRoundRect(rect, 10f, 10f, paint)
                }
            }
            paint.color = 0xFFDCE4E6.toInt()
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = if (control.visual == ControlVisual.NUMERIC_KEY) 18f else 11f
            val label = when {
                showingJavaLibrary && control.key == CanonicalKey.SOFT_LEFT -> "IMPORT"
                showingJavaLibrary && control.key == CanonicalKey.SOFT_RIGHT -> "RUN"
                else -> control.label
            }
            canvas.drawText(label, rect.centerX(), rect.centerY() + 4f, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val scale = min(width / DESIGN_WIDTH, height / DESIGN_HEIGHT)
        if (scale <= 0f) return true
        val x = (event.x - (width - DESIGN_WIDTH * scale) / 2f) / scale
        val y = (event.y - (height - DESIGN_HEIGHT * scale) / 2f) / scale
        handleTouch(x, y)
        performClick()
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun handleTouch(x: Float, y: Float) {
        val normalizedX = x / DESIGN_WIDTH
        val normalizedY = y / DESIGN_HEIGHT
        val control = profile.keypad.controls.firstOrNull { it.bounds.contains(normalizedX, normalizedY) }
        if (control != null) onKey(control.key)
    }

    private fun nextProfile() {
        showingJavaLibrary = false
        profileIndex = (profileIndex + 1) % profiles.size
        selectedItem = 0
        activeRuntime = profile.defaultRuntime
        detailMessage = runtimeMessage()
    }

    private fun openSelected() {
        when (selectedItem) {
            0 -> if (activeRuntime == RuntimeFamily.JAVA_ME) {
                showingJavaLibrary = true
                javaLibrarySelection = 0
                detailMessage = null
            } else {
                detailMessage = "SIS/SISX Symbian integration pending"
            }
            1 -> detailMessage = "WAP/catalogue adapter pending"
            2 -> detailMessage = "No game packages installed"
            else -> detailMessage = "Profile settings prototype"
        }
    }

    private fun switchRuntime() {
        val available = profile.supportedRuntimes.toList()
        if (available.size < 2) {
            detailMessage = "Only one runtime is defined for this profile"
            return
        }
        showingJavaLibrary = false
        val currentIndex = available.indexOf(activeRuntime).coerceAtLeast(0)
        activeRuntime = available[(currentIndex + 1) % available.size]
        detailMessage = runtimeMessage()
    }

    private fun runtimeMessage(): String = when (activeRuntime) {
        RuntimeFamily.JAVA_ME -> "Java ME selected · JAR/JAD"
        RuntimeFamily.SYMBIAN -> "Symbian selected · system image required"
    }

    private fun onKey(key: CanonicalKey) {
        if (showingJavaLibrary) {
            when (key) {
                CanonicalKey.UP -> javaLibrarySelection =
                    (javaLibrarySelection - 1).coerceAtLeast(0)
                CanonicalKey.DOWN -> javaLibrarySelection =
                    (javaLibrarySelection + 1).coerceAtMost((javaPackages.size - 1).coerceAtLeast(0))
                CanonicalKey.SELECT, CanonicalKey.SOFT_RIGHT -> {
                    javaPackages.getOrNull(javaLibrarySelection)?.let { record ->
                        onLaunchJavaApp?.invoke(record)
                    }
                }
                CanonicalKey.SOFT_LEFT -> onImportJavaAppRequested?.invoke()
                CanonicalKey.BACK -> showingJavaLibrary = false
                else -> Unit
            }
            invalidate()
            return
        }

        when (key) {
            CanonicalKey.UP -> selectedItem = (selectedItem - 1).coerceAtLeast(0)
            CanonicalKey.DOWN -> selectedItem = (selectedItem + 1).coerceAtMost(menuItems.lastIndex)
            CanonicalKey.SELECT, CanonicalKey.SOFT_RIGHT -> openSelected()
            CanonicalKey.SOFT_LEFT -> nextProfile()
            CanonicalKey.BACK -> detailMessage = null
            else -> detailMessage = "Key: " + key.name
        }
    }

    fun handleBack(): Boolean {
        if (showingJavaLibrary) {
            showingJavaLibrary = false
            invalidate()
            return true
        }
        if (detailMessage != null) {
            detailMessage = null
            invalidate()
            return true
        }
        if (selectedItem != 0) {
            selectedItem = 0
            invalidate()
            return true
        }
        return false
    }

    private fun UnitRect.toCanvasRect(): RectF = RectF(
        x * DESIGN_WIDTH,
        y * DESIGN_HEIGHT,
        (x + width) * DESIGN_WIDTH,
        (y + height) * DESIGN_HEIGHT
    )

    companion object {
        private const val DESIGN_WIDTH = 400f
        private const val DESIGN_HEIGHT = 900f
        private const val MAX_VISIBLE_APPS = 5
    }
}

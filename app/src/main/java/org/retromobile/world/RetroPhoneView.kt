package org.retromobile.world

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
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

        paint.color = 0xFFB9D0B0.toInt()
        canvas.drawRect(screen, paint)
        drawScreen(canvas, screen)
        drawNavigationAssembly(canvas)
        drawKeyControls(canvas)

        paint.color = 0xFFCED7D9.toInt()
        paint.textSize = 16f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(profile.displayName, 200f, 844f, paint)
        paint.textSize = 10f
        val runtimeName = if (activeRuntime == RuntimeFamily.JAVA_ME) "JAVA ME" else "SYMBIAN"
        canvas.drawText("$runtimeName · TAP TO SWITCH", 200f, 866f, paint)
    }

    private fun drawScreen(canvas: Canvas, screen: RectF) {
        canvas.save()
        canvas.clipRect(screen)
        val logicalHeight = profile.display.height * 240f / profile.display.width
        val scale = screen.width() / 240f
        canvas.translate(screen.left, screen.top)
        canvas.scale(scale, scale)

        paint.color = 0xFF78916F.toInt()
        canvas.drawRect(0f, 0f, 240f, 22f, paint)
        paint.color = 0xFF14251B.toInt()
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 10f
        val deviceLabel = when (profile.osFamily) {
            DeviceOsFamily.NOKIA_S40 -> "S40"
            DeviceOsFamily.SONY_ERICSSON_CLASSIC -> "SONY ERICSSON"
            DeviceOsFamily.SYMBIAN_S60 -> "S60"
        }
        val runtimeLabel = if (activeRuntime == RuntimeFamily.JAVA_ME) "JAVA" else "SYMBIAN"
        canvas.drawText("$deviceLabel / $runtimeLabel", 7f, 15f, paint)

        if (showingJavaLibrary) {
            drawJavaLibraryScreen(canvas, logicalHeight)
            canvas.restore()
            return
        }

        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("▮▮  ▰", 233f, 15f, paint)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 23f
        paint.color = 0xFF1B3022.toInt()
        canvas.drawText("15:42", 120f, 68f, paint)
        paint.textSize = 10f
        canvas.drawText("WEDNESDAY · 12 AUG", 120f, 85f, paint)

        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 12f
        paint.color = 0xFF183121.toInt()
        canvas.drawText("Main menu", 9f, 115f, paint)
        menuItems.forEachIndexed { index, item ->
            val y = 140f + index * 31f
            if (index == selectedItem) {
                paint.color = 0xFF526C4D.toInt()
                canvas.drawRoundRect(RectF(4f, y - 16f, 236f, y + 7f), 4f, 4f, paint)
                paint.color = 0xFFF0F4E9.toInt()
            } else {
                paint.color = 0xFF1B3022.toInt()
            }
            canvas.drawText(item, 12f, y, paint)
        }
        detailMessage?.let {
            paint.color = 0xFF1B3022.toInt()
            paint.textSize = 10f
            canvas.drawText(it.take(32), 8f, logicalHeight - 24f, paint)
        }

        paint.color = 0xFF1B3022.toInt()
        paint.textSize = 10f
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText("PROFILE", 6f, logicalHeight - 7f, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("OPEN", 234f, logicalHeight - 7f, paint)
        canvas.restore()
    }

    private fun drawJavaLibraryScreen(canvas: Canvas, logicalHeight: Float) {
        paint.color = 0xFF183121.toInt()
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 13f
        canvas.drawText("Java applications", 9f, 54f, paint)

        if (javaPackages.isEmpty()) {
            paint.textSize = 11f
            canvas.drawText("No imported applications", 10f, 112f, paint)
            canvas.drawText("Choose IMPORT to add a JAR or JAD", 10f, 133f, paint)
        } else {
            val firstVisible = (javaLibrarySelection / MAX_VISIBLE_APPS) * MAX_VISIBLE_APPS
            javaPackages.drop(firstVisible).take(MAX_VISIBLE_APPS).forEachIndexed { row, record ->
                val absoluteIndex = firstVisible + row
                val y = 91f + row * 34f
                if (absoluteIndex == javaLibrarySelection) {
                    paint.color = 0xFF526C4D.toInt()
                    canvas.drawRoundRect(RectF(4f, y - 17f, 236f, y + 8f), 4f, 4f, paint)
                    paint.color = 0xFFF0F4E9.toInt()
                } else {
                    paint.color = 0xFF1B3022.toInt()
                }
                paint.textAlign = Paint.Align.LEFT
                paint.textSize = 12f
                canvas.drawText(record.metadata.title.take(27), 10f, y, paint)
                paint.textSize = 8f
                canvas.drawText(
                    record.metadata.vendor.take(32) + " · " + record.metadata.version,
                    10f,
                    y + 11f,
                    paint
                )
            }
        }

        paint.color = 0xFF1B3022.toInt()
        paint.textSize = 10f
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText("IMPORT", 6f, logicalHeight - 7f, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("RUN", 234f, logicalHeight - 7f, paint)
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
        if (y in 852f..880f && x in 70f..330f) {
            switchRuntime()
            return
        }
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

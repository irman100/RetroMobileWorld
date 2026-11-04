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
    private var detailMessage: String? = null
    private var screen = PhoneScreen.STANDBY
    private var submenuTitle = ""
    private var submenuEntries: List<String> = emptyList()
    private var submenuSelection = 0
    private var quickAccessSelection = 0
    private var javaLibrarySelection = 0
    private var javaPackages: List<LegacyPackageRecord> = emptyList()
    private var galleryFolder = "Images"
    private var gallerySelection = 0
    private var galleryItems: List<GalleryMediaItem> = emptyList()

    var onImportJavaAppRequested: (() -> Unit)? = null
    var onLaunchJavaApp: ((LegacyPackageRecord) -> Unit)? = null
    var onGalleryFolderSelected: ((String) -> Unit)? = null
    var onOpenGalleryItem: ((GalleryMediaItem) -> Unit)? = null

    private val profile: DeviceProfile
        get() = profiles[profileIndex]

    private enum class PhoneScreen { STANDBY, MAIN_MENU, SUBMENU, JAVA_LIBRARY, QUICK_ACCESS, CONTACTS, GALLERY_ITEMS }

    data class GalleryMediaItem(val name: String, val uri: String, val mimeType: String)

    private data class MenuItem(val title: String, val glyph: String)

    // Main-menu sequence follows the Nokia 6300 (RM-217) user guide.
    private val menuItems = listOf(
        MenuItem("Messaging", "message"),
        MenuItem("Contacts", "contacts"),
        MenuItem("Call log", "calls"),
        MenuItem("Settings", "settings"),
        MenuItem("Operator menu", "operator"),
        MenuItem("Gallery", "gallery"),
        MenuItem("Media", "media"),
        MenuItem("Push to talk", "ptt"),
        MenuItem("Organizer", "organizer"),
        MenuItem("Applications", "apps"),
        MenuItem("Web", "web"),
        MenuItem("SIM services", "sim")
    )

    private val quickAccessItems = listOf("Profiles", "Java applications", "Gallery", "Settings")

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
        if (width <= 0 || height <= 0) return
        // Fill the host viewport. Independent axes avoid shrinking the handset
        // silhouette and leaving unused side margins on modern tall displays.
        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val contentHeight = (height - paddingTop - paddingBottom).coerceAtLeast(1)
        val scaleX = contentWidth / DESIGN_WIDTH
        val scaleY = contentHeight / DESIGN_HEIGHT
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        canvas.scale(scaleX, scaleY)
        drawPhone(canvas, scaleX, scaleY)
        canvas.restore()
    }

    /**
     * Returns the virtual LCD aperture in design coordinates while compensating for
     * the host canvas' independent X/Y scaling. This keeps the device's native pixel
     * aspect ratio even when the surrounding shell fills a tall Android viewport.
     */
    private fun displayScreenRect(scaleX: Float, scaleY: Float): RectF {
        val top = 72f
        val bottom = 539f
        val screenHeight = bottom - top
        val logicalHeight = profile.display.height * 240f / profile.display.width
        val screenWidth = DisplayAspectGeometry.designWidthForNativeAspect(
            displayWidth = profile.display.width,
            displayHeight = profile.display.height,
            designHeight = screenHeight,
            canvasScaleX = scaleX,
            canvasScaleY = scaleY
        )
        val left = (DESIGN_WIDTH - screenWidth) / 2f
        return RectF(left, top, left + screenWidth, bottom)
    }

    private fun drawPhone(canvas: Canvas, scaleX: Float, scaleY: Float) {
        paint.shader = LinearGradient(
            0f, 0f, DESIGN_WIDTH, DESIGN_HEIGHT,
            0xFF111D29.toInt(), 0xFF050B11.toInt(), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, DESIGN_WIDTH, DESIGN_HEIGHT, paint)
        paint.shader = null
        drawTopToolbar(canvas)

        val screenRect = displayScreenRect(scaleX, scaleY)
        val displayFrame = RectF(
            screenRect.left - 2f,
            70f,
            screenRect.right + 2f,
            541f
        )
        paint.color = 0xFF02070B.toInt()
        canvas.drawRoundRect(displayFrame, 11f, 11f, paint)
        paint.color = 0xFF263744.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.1f
        canvas.drawRoundRect(displayFrame, 11f, 11f, paint)
        paint.style = Paint.Style.FILL
        drawScreen(canvas, screenRect)

        paint.color = 0xFF0D1721.toInt()
        canvas.drawRect(0f, 548f, DESIGN_WIDTH, DESIGN_HEIGHT, paint)
        paint.color = 0xFF334554.toInt()
        canvas.drawRect(0f, 548f, DESIGN_WIDTH, 550f, paint)
        drawNavigationAssembly(canvas)
        drawKeyControls(canvas)
    }

    private fun drawTopToolbar(canvas: Canvas) {
        paint.color = 0xFF101A24.toInt()
        canvas.drawRect(0f, 0f, DESIGN_WIDTH, 64f, paint)
        paint.color = 0xFF263744.toInt()
        canvas.drawRect(0f, 63f, DESIGN_WIDTH, 65f, paint)
        paint.color = 0xFFEAF2F7.toInt()
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 15f
        paint.isFakeBoldText = true
        canvas.drawText("Retro Mobile World", 16f, 26f, paint)
        paint.isFakeBoldText = false
        paint.color = 0xFF9EB1BF.toInt()
        paint.textSize = 8.5f
        val family = when (profile.osFamily) {
            DeviceOsFamily.NOKIA_S40 -> "SERIES 40  ·  JAVA ME"
            DeviceOsFamily.SONY_ERICSSON_CLASSIC -> "SONY ERICSSON  ·  JAVA ME"
            DeviceOsFamily.SYMBIAN_S60 -> "SYMBIAN S60  ·  JAVA ME"
        }
        canvas.drawText(profile.displayName + "  /  " + family, 16f, 46f, paint)
        paint.color = 0xFF1B2A37.toInt()
        canvas.drawRoundRect(RectF(307f, 10f, 390f, 54f), 8f, 8f, paint)
        paint.color = 0xFFE6EDF2.toInt()
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 8.5f
        canvas.drawText("DEVICE", 348f, 27f, paint)
        canvas.drawText("CHANGE  ›", 348f, 42f, paint)
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

    private fun drawScreen(canvas: Canvas, screenRect: RectF) {
        canvas.save()
        canvas.clipRect(screenRect)
        val logicalHeight = profile.display.height * 240f / profile.display.width
        val scaleX = screenRect.width() / 240f
        val scaleY = screenRect.height() / logicalHeight
        canvas.translate(screenRect.left, screenRect.top)
        canvas.scale(scaleX, scaleY)

        val palette = screenPalette()
        drawWallpaper(canvas, logicalHeight, palette)
        drawStatusBar(canvas, palette)

        when (screen) {
            PhoneScreen.STANDBY -> drawStandbyScreen(canvas, logicalHeight, palette)
            PhoneScreen.MAIN_MENU -> drawMainMenuScreen(canvas, logicalHeight, palette)
            PhoneScreen.SUBMENU -> drawSubmenuScreen(canvas, logicalHeight, palette)
            PhoneScreen.JAVA_LIBRARY -> drawJavaLibraryScreen(canvas, logicalHeight, palette)
            PhoneScreen.QUICK_ACCESS -> drawQuickAccessScreen(canvas, logicalHeight, palette)
            PhoneScreen.CONTACTS -> drawContactsScreen(canvas, logicalHeight, palette)
            PhoneScreen.GALLERY_ITEMS -> drawGalleryItemsScreen(canvas, logicalHeight, palette)
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

    private fun drawStandbyScreen(canvas: Canvas, logicalHeight: Float, palette: ScreenPalette) {
        val now = Date()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val date = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())
            .format(now)
            .replaceFirstChar { it.uppercase(Locale.getDefault()) }

        paint.color = palette.text
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 9f
        val operatorName = when (profile.osFamily) {
            DeviceOsFamily.NOKIA_S40 -> "Network operator"
            DeviceOsFamily.SONY_ERICSSON_CLASSIC -> "Network"
            DeviceOsFamily.SYMBIAN_S60 -> "Operator"
        }
        canvas.drawText(operatorName, 120f, 47f, paint)
        paint.textSize = 34f
        canvas.drawText(time, 120f, 111f, paint)
        paint.color = palette.muted
        paint.textSize = 9f
        canvas.drawText(date, 120f, 131f, paint)
        detailMessage?.let {
            paint.color = palette.text
            paint.textSize = 8f
            canvas.drawText(it.take(30), 120f, 246f, paint)
        }
        drawSoftKeyBar(canvas, logicalHeight, palette, "Go to", "Names", "Menu")
    }

    private fun drawMainMenuScreen(canvas: Canvas, logicalHeight: Float, palette: ScreenPalette) {
        paint.color = palette.text
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 12f
        canvas.drawText("Menu", 9f, 43f, paint)
        val cellWidth = 80f
        val cellHeight = 59f
        menuItems.forEachIndexed { index, item ->
            val column = index % 3
            val row = index / 3
            val left = column * cellWidth
            val top = 48f + row * cellHeight
            val selected = index == selectedItem
            if (selected) {
                paint.color = palette.selected
                canvas.drawRoundRect(RectF(left + 3f, top + 1f, left + cellWidth - 3f, top + cellHeight - 2f), 5f, 5f, paint)
            }
            paint.color = palette.iconColors[index % palette.iconColors.size]
            canvas.drawRoundRect(RectF(left + 28f, top + 3f, left + 52f, top + 27f), 6f, 6f, paint)
            drawMenuIcon(canvas, index, left + 40f, top + 15f)
            paint.color = if (selected) 0xFFFFFFFF.toInt() else palette.text
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 7.4f
            canvas.drawText(item.title, left + 40f, top + 43f, paint)
        }
        drawSoftKeyBar(canvas, logicalHeight, palette, "Options", "Select")
    }

    /** Original vector symbols remain crisp at all virtual display sizes. */
    private fun drawMenuIcon(canvas: Canvas, index: Int, cx: Float, cy: Float) {
        paint.color = 0xFFFFFFFF.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        when (index) {
            0 -> {
                val path = Path().apply {
                    moveTo(cx - 8f, cy - 6f); quadTo(cx - 10f, cy - 8f, cx - 7f, cy - 8f)
                    lineTo(cx + 7f, cy - 8f); quadTo(cx + 9f, cy - 8f, cx + 9f, cy - 5f)
                    lineTo(cx + 9f, cy + 4f); quadTo(cx + 9f, cy + 6f, cx + 6f, cy + 6f)
                    lineTo(cx - 2f, cy + 6f); lineTo(cx - 7f, cy + 10f)
                    lineTo(cx - 6f, cy + 6f); quadTo(cx - 9f, cy + 5f, cx - 9f, cy + 2f); close()
                }
                canvas.drawPath(path, paint)
                canvas.drawLine(cx - 4f, cy - 2f, cx + 4f, cy - 2f, paint)
                canvas.drawLine(cx - 4f, cy + 2f, cx + 2f, cy + 2f, paint)
            }
            1 -> {
                canvas.drawCircle(cx, cy - 4f, 4f, paint)
                canvas.drawRoundRect(RectF(cx - 8f, cy + 2f, cx + 8f, cy + 9f), 5f, 5f, paint)
            }
            2 -> {
                val path = Path().apply {
                    moveTo(cx - 7f, cy - 8f); quadTo(cx - 10f, cy - 6f, cx - 7f, cy + 1f)
                    quadTo(cx - 2f, cy + 8f, cx + 6f, cy + 8f)
                    quadTo(cx + 10f, cy + 7f, cx + 8f, cy + 3f)
                    lineTo(cx + 4f, cy); lineTo(cx + 1f, cy + 3f)
                    quadTo(cx - 3f, cy + 1f, cx - 4f, cy - 3f)
                    lineTo(cx - 1f, cy - 6f); close()
                }
                canvas.drawPath(path, paint)
            }
            3 -> {
                canvas.drawCircle(cx, cy, 6.5f, paint)
                canvas.drawCircle(cx, cy, 2.5f, paint)
                for (angle in 0 until 8) {
                    val radians = angle * Math.PI / 4.0
                    val x1 = cx + kotlin.math.cos(radians).toFloat() * 7f
                    val y1 = cy + kotlin.math.sin(radians).toFloat() * 7f
                    val x2 = cx + kotlin.math.cos(radians).toFloat() * 10f
                    val y2 = cy + kotlin.math.sin(radians).toFloat() * 10f
                    canvas.drawLine(x1, y1, x2, y2, paint)
                }
            }
            4, 10 -> {
                canvas.drawCircle(cx, cy, 8f, paint)
                canvas.drawOval(RectF(cx - 4f, cy - 8f, cx + 4f, cy + 8f), paint)
                canvas.drawLine(cx - 7f, cy, cx + 7f, cy, paint)
                canvas.drawLine(cx - 6f, cy - 4f, cx + 6f, cy - 4f, paint)
                canvas.drawLine(cx - 6f, cy + 4f, cx + 6f, cy + 4f, paint)
            }
            5 -> {
                canvas.drawRoundRect(RectF(cx - 9f, cy - 7f, cx + 9f, cy + 8f), 2f, 2f, paint)
                val mountain = Path().apply {
                    moveTo(cx - 7f, cy + 5f); lineTo(cx - 2f, cy - 1f)
                    lineTo(cx + 2f, cy + 3f); lineTo(cx + 5f, cy); lineTo(cx + 8f, cy + 5f)
                }
                canvas.drawPath(mountain, paint)
                canvas.drawCircle(cx + 5f, cy - 4f, 1.4f, paint)
            }
            6 -> {
                val triangle = Path().apply {
                    moveTo(cx - 5f, cy - 8f); lineTo(cx + 8f, cy); lineTo(cx - 5f, cy + 8f); close()
                }
                canvas.drawPath(triangle, paint)
            }
            7 -> {
                canvas.drawRoundRect(RectF(cx - 6f, cy - 9f, cx + 6f, cy + 9f), 2f, 2f, paint)
                canvas.drawLine(cx - 3f, cy - 4f, cx + 3f, cy - 4f, paint)
                canvas.drawLine(cx - 3f, cy, cx + 3f, cy, paint)
                canvas.drawCircle(cx, cy + 5f, 1f, paint)
            }
            8 -> {
                canvas.drawRoundRect(RectF(cx - 8f, cy - 8f, cx + 8f, cy + 8f), 2f, 2f, paint)
                canvas.drawLine(cx - 8f, cy - 3f, cx + 8f, cy - 3f, paint)
                canvas.drawLine(cx - 4f, cy - 10f, cx - 4f, cy - 5f, paint)
                canvas.drawLine(cx + 4f, cy - 10f, cx + 4f, cy - 5f, paint)
            }
            9 -> {
                canvas.drawRoundRect(RectF(cx - 8f, cy - 7f, cx + 8f, cy + 7f), 2f, 2f, paint)
                canvas.drawLine(cx - 8f, cy - 2f, cx + 8f, cy - 2f, paint)
                canvas.drawLine(cx - 3f, cy - 2f, cx - 3f, cy + 7f, paint)
                canvas.drawLine(cx + 3f, cy - 2f, cx + 3f, cy + 7f, paint)
            }
            else -> {
                val sim = Path().apply {
                    moveTo(cx - 7f, cy - 8f); lineTo(cx + 3f, cy - 8f); lineTo(cx + 8f, cy - 3f)
                    lineTo(cx + 8f, cy + 8f); lineTo(cx - 7f, cy + 8f); close()
                }
                canvas.drawPath(sim, paint)
                canvas.drawRect(RectF(cx - 4f, cy - 3f, cx + 5f, cy + 5f), paint)
            }
        }
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawSubmenuScreen(canvas: Canvas, logicalHeight: Float, palette: ScreenPalette) {
        paint.color = palette.text
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 13f
        canvas.drawText(submenuTitle, 10f, 48f, paint)

        submenuEntries.forEachIndexed { index, entry ->
            val y = 78f + index * 27f
            if (index == submenuSelection) {
                paint.color = palette.selected
                canvas.drawRoundRect(RectF(5f, y - 17f, 235f, y + 6f), 4f, 4f, paint)
            }
            paint.color = if (index == submenuSelection) 0xFFFFFFFF.toInt() else palette.text
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = 10f
            canvas.drawText(entry, 13f, y, paint)
            if (index == submenuSelection) {
                paint.textAlign = Paint.Align.RIGHT
                canvas.drawText("›", 225f, y, paint)
            }
        }

        detailMessage?.let {
            paint.color = palette.muted
            paint.textSize = 7f
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(it.take(38), 10f, logicalHeight - 28f, paint)
        }
        drawSoftKeyBar(canvas, logicalHeight, palette, "Options", "Open")
    }

    private fun drawQuickAccessScreen(canvas: Canvas, logicalHeight: Float, palette: ScreenPalette) {
        paint.color = palette.text
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 13f
        canvas.drawText("Go to", 10f, 48f, paint)

        quickAccessItems.forEachIndexed { index, item ->
            val y = 82f + index * 35f
            if (index == quickAccessSelection) {
                paint.color = palette.selected
                canvas.drawRoundRect(RectF(5f, y - 19f, 235f, y + 8f), 4f, 4f, paint)
            }
            paint.color = if (index == quickAccessSelection) 0xFFFFFFFF.toInt() else palette.text
            paint.textSize = 10f
            canvas.drawText(item, 13f, y, paint)
        }
        drawSoftKeyBar(canvas, logicalHeight, palette, "Options", "Open")
    }

    private fun drawContactsScreen(canvas: Canvas, logicalHeight: Float, palette: ScreenPalette) {
        paint.color = palette.text
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 13f
        canvas.drawText("Contacts", 10f, 48f, paint)
        paint.color = palette.panel
        canvas.drawRoundRect(RectF(8f, 72f, 232f, logicalHeight - 28f), 6f, 6f, paint)
        paint.color = palette.text
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 10f
        canvas.drawText("No contacts saved", 120f, 139f, paint)
        paint.color = palette.muted
        paint.textSize = 8f
        canvas.drawText("Names will appear here", 120f, 156f, paint)
        drawSoftKeyBar(canvas, logicalHeight, palette, "Options", "Add")
    }

    private fun drawGalleryItemsScreen(canvas: Canvas, logicalHeight: Float, palette: ScreenPalette) {
        paint.color = palette.text
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 12f
        canvas.drawText("Gallery", 9f, 44f, paint)
        paint.color = palette.muted
        paint.textSize = 8f
        canvas.drawText(galleryFolder.uppercase(Locale.getDefault()), 10f, 59f, paint)
        if (galleryItems.isEmpty()) {
            paint.color = palette.panel
            canvas.drawRoundRect(RectF(8f, 76f, 232f, logicalHeight - 29f), 6f, 6f, paint)
            paint.color = palette.text
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 10f
            canvas.drawText("No files selected", 120f, 145f, paint)
            paint.color = palette.muted
            paint.textSize = 8f
            canvas.drawText("Choose files from this device", 120f, 162f, paint)
        } else {
            val firstVisible = (gallerySelection / MAX_VISIBLE_GALLERY_ITEMS) * MAX_VISIBLE_GALLERY_ITEMS
            galleryItems.drop(firstVisible).take(MAX_VISIBLE_GALLERY_ITEMS).forEachIndexed { row, item ->
                val absoluteIndex = firstVisible + row
                val y = 84f + row * 29f
                val selected = absoluteIndex == gallerySelection
                paint.color = if (selected) palette.selected else palette.panel
                canvas.drawRoundRect(RectF(5f, y - 17f, 235f, y + 8f), 4f, 4f, paint)
                paint.color = palette.iconColors[absoluteIndex % palette.iconColors.size]
                canvas.drawRoundRect(RectF(10f, y - 12f, 27f, y + 4f), 3f, 3f, paint)
                paint.color = palette.text
                paint.textAlign = Paint.Align.LEFT
                paint.textSize = 8f
                canvas.drawText(item.name.take(30), 34f, y - 2f, paint)
                paint.color = palette.muted
                paint.textSize = 6.5f
                canvas.drawText(item.mimeType.take(30), 34f, y + 7f, paint)
            }
        }
        drawSoftKeyBar(canvas, logicalHeight, palette, "Back", "Open")
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
        rightLabel: String,
        centerLabel: String? = null
    ) {
        paint.color = 0xD90A1019.toInt()
        canvas.drawRect(0f, logicalHeight - 20f, 240f, logicalHeight, paint)
        paint.color = palette.text
        paint.textSize = 9f
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(leftLabel, 8f, logicalHeight - 7f, paint)
        if (centerLabel != null) {
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(centerLabel, 120f, logicalHeight - 7f, paint)
        }
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(rightLabel, 232f, logicalHeight - 7f, paint)
    }

    private fun drawNavigationAssembly(canvas: Canvas) {
        val bounds = profile.keypad.navigationBounds.toControlCanvasRect()
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
            val rect = control.bounds.toControlCanvasRect()
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
                screen == PhoneScreen.JAVA_LIBRARY && control.key == CanonicalKey.SOFT_LEFT -> "IMPORT"
                screen == PhoneScreen.JAVA_LIBRARY && control.key == CanonicalKey.SOFT_RIGHT -> "RUN"
                screen == PhoneScreen.STANDBY && control.key == CanonicalKey.SOFT_LEFT -> "GO TO"
                screen == PhoneScreen.STANDBY && control.key == CanonicalKey.SOFT_RIGHT -> "NAMES"
                screen == PhoneScreen.GALLERY_ITEMS && control.key == CanonicalKey.SOFT_LEFT -> "BACK"
                screen == PhoneScreen.GALLERY_ITEMS && control.key == CanonicalKey.SOFT_RIGHT -> "OPEN"
                screen != PhoneScreen.STANDBY && control.key == CanonicalKey.SOFT_LEFT -> "OPTIONS"
                screen != PhoneScreen.STANDBY && control.key == CanonicalKey.SOFT_RIGHT -> "OPEN"
                else -> control.label
            }
            canvas.drawText(label, rect.centerX(), rect.centerY() + 4f, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val contentWidth = width - paddingLeft - paddingRight
        val contentHeight = height - paddingTop - paddingBottom
        if (event.action != MotionEvent.ACTION_UP || contentWidth <= 0 || contentHeight <= 0) return true
        val scaleX = contentWidth / DESIGN_WIDTH
        val scaleY = contentHeight / DESIGN_HEIGHT
        val x = (event.x - paddingLeft) * DESIGN_WIDTH / contentWidth
        val y = (event.y - paddingTop) * DESIGN_HEIGHT / contentHeight
        when {
            y <= 62f && x >= 300f -> nextProfile()
            y <= 62f -> {
                screen = PhoneScreen.MAIN_MENU
                selectedItem = 0
                detailMessage = null
            }
            else -> handleTouch(x, y, displayScreenRect(scaleX, scaleY))
        }
        performClick()
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun handleTouch(x: Float, y: Float, displayRect: RectF) {
        val control = profile.keypad.controls.firstOrNull {
            it.bounds.toControlCanvasRect().contains(x, y)
        }
        if (control != null) {
            onKey(control.key)
            return
        }
        if (displayRect.contains(x, y)) {
            when (screen) {
                PhoneScreen.STANDBY -> onKey(CanonicalKey.SELECT)
                PhoneScreen.MAIN_MENU -> {
                    val logicalHeight = profile.display.height * 240f / profile.display.width
                    val displayX = (x - displayRect.left) * 240f / displayRect.width()
                    val displayY = (y - displayRect.top) * logicalHeight / displayRect.height()
                    val row = ((displayY - 48f) / 59f).toInt()
                    val column = (displayX / 80f).toInt()
                    val tapped = row * 3 + column
                    if (tapped in menuItems.indices) {
                        selectedItem = tapped
                        openSelected()
                    }
                }
                else -> Unit
            }
        }
    }

    private fun nextProfile() {
        screen = PhoneScreen.STANDBY
        profileIndex = (profileIndex + 1) % profiles.size
        selectedItem = 0
        detailMessage = profile.displayName
    }

    private fun entriesFor(title: String): List<String> = when (title) {
        "Messaging" -> listOf("Create message", "Inbox", "Sent items", "Drafts", "Archive", "Templates")
        "Contacts" -> listOf("Names", "Add new contact", "Search", "Groups", "Speed dials")
        "Call log" -> listOf("Missed calls", "Received calls", "Dialled numbers", "Call duration")
        "Settings" -> listOf("Profiles", "Tones", "Display", "Themes", "Time and date", "My shortcuts", "Bluetooth", "Phone settings")
        "Operator menu" -> listOf("Operator services", "Service messages")
        "Gallery" -> listOf("Images", "Video clips", "Music files", "Themes", "Graphics", "Tones", "Recordings", "Received files", "Memory card")
        "Media" -> listOf("Camera", "Music player", "Radio", "Voice recorder", "Equalizer")
        "Push to talk" -> listOf("PTT channels", "Switch PTT on or off", "PTT settings")
        "Organizer" -> listOf("Alarm clock", "Calendar", "To-do list", "Notes", "Calculator", "Timers", "Stopwatch")
        "Applications" -> listOf("Games", "Collection", "Java applications")
        "Web" -> listOf("Home", "Bookmarks", "Go to address", "Last web address", "Settings")
        else -> listOf("SIM services")
    }

    private fun openSelected() {
        submenuTitle = menuItems.getOrNull(selectedItem)?.title ?: return
        submenuEntries = entriesFor(submenuTitle)
        submenuSelection = 0
        detailMessage = null
        screen = PhoneScreen.SUBMENU
    }

    private fun openSubmenuSelection() {
        val entry = submenuEntries.getOrNull(submenuSelection) ?: return
        if (submenuTitle == "Applications" && entry == "Java applications") {
            javaLibrarySelection = 0
            screen = PhoneScreen.JAVA_LIBRARY
            detailMessage = null
        } else if (submenuTitle == "Gallery") {
            galleryFolder = entry
            gallerySelection = 0
            galleryItems = emptyList()
            screen = PhoneScreen.GALLERY_ITEMS
            onGalleryFolderSelected?.invoke(entry)
        } else if (submenuTitle == "Settings" && entry == "Profiles") {
            nextProfile()
        } else {
            detailMessage = "$entry selected"
        }
    }

    fun updateGalleryItems(folder: String, items: List<GalleryMediaItem>) {
        galleryFolder = folder
        galleryItems = items
        gallerySelection = 0
        screen = PhoneScreen.GALLERY_ITEMS
        invalidate()
    }

    private fun openQuickAccessSelection() {
        when (quickAccessSelection) {
            0 -> nextProfile()
            1 -> {
                submenuTitle = "Applications"
                submenuEntries = entriesFor(submenuTitle)
                submenuSelection = submenuEntries.indexOf("Java applications").coerceAtLeast(0)
                screen = PhoneScreen.SUBMENU
            }
            2 -> {
                submenuTitle = "Gallery"
                submenuEntries = entriesFor(submenuTitle)
                submenuSelection = 0
                screen = PhoneScreen.SUBMENU
            }
            else -> {
                submenuTitle = "Settings"
                submenuEntries = entriesFor(submenuTitle)
                submenuSelection = 0
                screen = PhoneScreen.SUBMENU
            }
        }
    }

    private fun onKey(key: CanonicalKey) {
        when (screen) {
            PhoneScreen.STANDBY -> when (key) {
                CanonicalKey.SELECT -> {
                    screen = PhoneScreen.MAIN_MENU
                    selectedItem = 0
                    detailMessage = null
                }
                CanonicalKey.SOFT_LEFT -> {
                    screen = PhoneScreen.QUICK_ACCESS
                    quickAccessSelection = 0
                }
                CanonicalKey.SOFT_RIGHT -> screen = PhoneScreen.CONTACTS
                CanonicalKey.CALL -> {
                    submenuTitle = "Call log"
                    submenuEntries = entriesFor(submenuTitle)
                    submenuSelection = 0
                    screen = PhoneScreen.SUBMENU
                }
                else -> Unit
            }
            PhoneScreen.MAIN_MENU -> when (key) {
                CanonicalKey.UP -> selectedItem = (selectedItem - 3).coerceAtLeast(0)
                CanonicalKey.DOWN -> selectedItem = (selectedItem + 3).coerceAtMost(menuItems.lastIndex)
                CanonicalKey.LEFT -> selectedItem = (selectedItem - 1).coerceAtLeast(0)
                CanonicalKey.RIGHT -> selectedItem = (selectedItem + 1).coerceAtMost(menuItems.lastIndex)
                CanonicalKey.SELECT, CanonicalKey.SOFT_RIGHT -> openSelected()
                CanonicalKey.BACK, CanonicalKey.SOFT_LEFT -> screen = PhoneScreen.STANDBY
                else -> Unit
            }
            PhoneScreen.SUBMENU -> when (key) {
                CanonicalKey.UP -> submenuSelection = (submenuSelection - 1).coerceAtLeast(0)
                CanonicalKey.DOWN -> submenuSelection = (submenuSelection + 1).coerceAtMost((submenuEntries.size - 1).coerceAtLeast(0))
                CanonicalKey.SELECT, CanonicalKey.SOFT_RIGHT -> openSubmenuSelection()
                CanonicalKey.BACK -> {
                    screen = PhoneScreen.MAIN_MENU
                    detailMessage = null
                }
                CanonicalKey.SOFT_LEFT -> detailMessage = "Options"
                else -> Unit
            }
            PhoneScreen.JAVA_LIBRARY -> when (key) {
                CanonicalKey.UP -> javaLibrarySelection = (javaLibrarySelection - 1).coerceAtLeast(0)
                CanonicalKey.DOWN -> javaLibrarySelection = (javaLibrarySelection + 1).coerceAtMost((javaPackages.size - 1).coerceAtLeast(0))
                CanonicalKey.SELECT, CanonicalKey.SOFT_RIGHT -> {
                    javaPackages.getOrNull(javaLibrarySelection)?.let { onLaunchJavaApp?.invoke(it) }
                }
                CanonicalKey.SOFT_LEFT -> onImportJavaAppRequested?.invoke()
                CanonicalKey.BACK -> screen = PhoneScreen.SUBMENU
                else -> Unit
            }
            PhoneScreen.QUICK_ACCESS -> when (key) {
                CanonicalKey.UP -> quickAccessSelection = (quickAccessSelection - 1).coerceAtLeast(0)
                CanonicalKey.DOWN -> quickAccessSelection = (quickAccessSelection + 1).coerceAtMost(quickAccessItems.lastIndex)
                CanonicalKey.SELECT, CanonicalKey.SOFT_RIGHT -> openQuickAccessSelection()
                CanonicalKey.BACK, CanonicalKey.SOFT_LEFT -> screen = PhoneScreen.STANDBY
                else -> Unit
            }
            PhoneScreen.CONTACTS -> when (key) {
                CanonicalKey.BACK, CanonicalKey.SOFT_LEFT -> screen = PhoneScreen.STANDBY
                CanonicalKey.SOFT_RIGHT, CanonicalKey.SELECT -> detailMessage = "Add contact"
                else -> Unit
            }
            PhoneScreen.GALLERY_ITEMS -> when (key) {
                CanonicalKey.UP -> gallerySelection = (gallerySelection - 1).coerceAtLeast(0)
                CanonicalKey.DOWN -> gallerySelection = (gallerySelection + 1).coerceAtMost((galleryItems.size - 1).coerceAtLeast(0))
                CanonicalKey.SELECT, CanonicalKey.SOFT_RIGHT -> galleryItems.getOrNull(gallerySelection)?.let { onOpenGalleryItem?.invoke(it) }
                CanonicalKey.BACK, CanonicalKey.SOFT_LEFT -> {
                    screen = PhoneScreen.SUBMENU
                    submenuTitle = "Gallery"
                    submenuEntries = entriesFor("Gallery")
                    submenuSelection = submenuEntries.indexOf(galleryFolder).coerceAtLeast(0)
                }
                else -> Unit
            }
        }
        invalidate()
    }

    fun handleBack(): Boolean {
        when (screen) {
            PhoneScreen.STANDBY -> return false
            PhoneScreen.MAIN_MENU, PhoneScreen.QUICK_ACCESS, PhoneScreen.CONTACTS -> screen = PhoneScreen.STANDBY
            PhoneScreen.SUBMENU -> screen = PhoneScreen.MAIN_MENU
            PhoneScreen.JAVA_LIBRARY, PhoneScreen.GALLERY_ITEMS -> screen = PhoneScreen.SUBMENU
        }
        detailMessage = null
        invalidate()
        return true
    }

    private fun UnitRect.toCanvasRect(): RectF = RectF(
        x * DESIGN_WIDTH,
        y * DESIGN_HEIGHT,
        (x + width) * DESIGN_WIDTH,
        (y + height) * DESIGN_HEIGHT
    )

    private fun UnitRect.toControlCanvasRect(): RectF {
        val source = toCanvasRect()
        val top = 565f + (source.top - 455f) * 0.82f
        val bottom = 565f + (source.bottom - 455f) * 0.82f
        return RectF(source.left, top, source.right, bottom)
    }

    companion object {
        private const val DESIGN_WIDTH = 400f
        private const val DESIGN_HEIGHT = 900f
        private const val MAX_VISIBLE_APPS = 5
        private const val MAX_VISIBLE_GALLERY_ITEMS = 7
    }
}

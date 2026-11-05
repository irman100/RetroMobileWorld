package org.retromobile.world

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/**
 * Minimal RM-217 firmware intake shell.
 *
 * The original phone aperture and physical keypad remain, but the temporary
 * hand-drawn S40 standby/menu hierarchy is intentionally not shown. The real
 * Series 40 display will replace this screen after the RM-217 machine boots.
 */
class RetroPhoneView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val profile = ProfileCatalog.builtIn.first { it.id == "nokia-6300-s40" }

    private var firmwareImages: List<FirmwareImageRecord> = emptyList()
    private var importSummary: FirmwareImportSummary? = null
    private var importing = false

    var onImportFirmwareRequested: (() -> Unit)? = null
    var onFirmwareInfoRequested: (() -> Unit)? = null

    init {
        contentDescription = "Retro Mobile World RM-217 firmware loader"
        isFocusable = true
    }

    fun setImporting(value: Boolean) {
        importing = value
        invalidate()
    }

    fun updateFirmwareState(
        images: List<FirmwareImageRecord>,
        summary: FirmwareImportSummary?
    ) {
        firmwareImages = images
        importSummary = summary
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

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

    private fun drawPhone(canvas: Canvas, scaleX: Float, scaleY: Float) {
        paint.shader = LinearGradient(
            0f, 0f, DESIGN_WIDTH, DESIGN_HEIGHT,
            0xFF182631.toInt(), 0xFF050A0E.toInt(), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(RectF(7f, 5f, 393f, 895f), 34f, 34f, paint)
        paint.shader = null

        paint.color = 0xFF344754.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f
        canvas.drawRoundRect(RectF(7f, 5f, 393f, 895f), 34f, 34f, paint)
        paint.style = Paint.Style.FILL

        drawTopLabel(canvas)
        val screenRect = displayScreenRect(scaleX, scaleY)
        val displayFrame = RectF(
            screenRect.left - 2f,
            70f,
            screenRect.right + 2f,
            541f
        )
        paint.color = 0xFF020609.toInt()
        canvas.drawRoundRect(displayFrame, 11f, 11f, paint)
        paint.color = 0xFF52616B.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.1f
        canvas.drawRoundRect(displayFrame, 11f, 11f, paint)
        paint.style = Paint.Style.FILL

        drawFirmwareDisplay(canvas, screenRect)

        paint.color = 0xFF0D1721.toInt()
        canvas.drawRect(0f, 548f, DESIGN_WIDTH, DESIGN_HEIGHT, paint)
        paint.color = 0xFF334554.toInt()
        canvas.drawRect(0f, 548f, DESIGN_WIDTH, 550f, paint)
        drawNavigationAssembly(canvas)
        drawKeyControls(canvas)
    }

    private fun drawTopLabel(canvas: Canvas) {
        paint.color = 0xFFEAF2F7.toInt()
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 14f
        paint.isFakeBoldText = true
        canvas.drawText("RETRO MOBILE WORLD", DESIGN_WIDTH / 2f, 27f, paint)
        paint.isFakeBoldText = false
        paint.color = 0xFF9EB1BF.toInt()
        paint.textSize = 8.5f
        canvas.drawText("NOKIA 6300  ·  RM-217", DESIGN_WIDTH / 2f, 45f, paint)

        paint.color = 0xFF05090D.toInt()
        canvas.drawRoundRect(RectF(172f, 555f, 228f, 560f), 2f, 2f, paint)
    }

    private fun displayScreenRect(scaleX: Float, scaleY: Float): RectF {
        val top = 72f
        val bottom = 539f
        val displayHeight = bottom - top
        val screenWidth = DisplayAspectGeometry.designWidthForNativeAspect(
            displayWidth = profile.display.width,
            displayHeight = profile.display.height,
            designHeight = displayHeight,
            canvasScaleX = scaleX,
            canvasScaleY = scaleY
        )
        val left = (DESIGN_WIDTH - screenWidth) / 2f
        return RectF(left, top, left + screenWidth, bottom)
    }

    private fun drawFirmwareDisplay(canvas: Canvas, screenRect: RectF) {
        canvas.save()
        canvas.clipRect(screenRect)
        canvas.translate(screenRect.left, screenRect.top)
        canvas.scale(screenRect.width() / 240f, screenRect.height() / 320f)

        paint.shader = LinearGradient(
            0f, 0f, 0f, 320f,
            0xFF164E70.toInt(), 0xFF061827.toInt(), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, 240f, 320f, paint)
        paint.shader = null

        drawStatusBar(canvas)
        drawFirmwareContent(canvas)
        drawSoftKeyBar(canvas)

        canvas.restore()
    }

    private fun drawStatusBar(canvas: Canvas) {
        paint.color = 0xE610263A.toInt()
        canvas.drawRect(0f, 0f, 240f, 22f, paint)
        paint.color = 0xFFF2F7FA.toInt()
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 8f
        paint.isFakeBoldText = true
        canvas.drawText("RM-217  SYSTEM SOFTWARE", 7f, 14f, paint)
        paint.isFakeBoldText = false

        for (bar in 0 until 4) {
            val barHeight = 4f + bar * 2.5f
            val left = 178f + bar * 5f
            canvas.drawRoundRect(
                RectF(left, 16f - barHeight, left + 3f, 16f),
                1f, 1f, paint
            )
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        canvas.drawRoundRect(RectF(207f, 6f, 229f, 16f), 2f, 2f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRect(230f, 9f, 233f, 13f, paint)
        paint.color = 0xFF75D28D.toInt()
        canvas.drawRect(209f, 8f, 224f, 14f, paint)
    }

    private fun drawFirmwareContent(canvas: Canvas) {
        val hasFiles = firmwareImages.isNotEmpty()
        val summary = importSummary

        paint.textAlign = Paint.Align.CENTER
        paint.color = 0xFFF4F8FB.toInt()
        paint.textSize = 13f
        paint.isFakeBoldText = true
        canvas.drawText(
            if (hasFiles) "RM-217  ·  VERSION ${summary?.version ?: "COMPONENTS"}"
            else "NOKIA RM-217",
            120f, 45f, paint
        )
        paint.isFakeBoldText = false

        if (!hasFiles) {
            drawStatusCard(
                canvas,
                RectF(12f, 60f, 228f, 119f),
                "ПРОШИВКА НЕ ЗАГРУЖЕНА",
                "Выберите оригинальный Phoenix ZIP"
            )
            drawImportButton(canvas, RectF(13f, 143f, 227f, 196f))
            paint.color = 0xFFC1D8E6.toInt()
            paint.textSize = 8f
            canvas.drawText("Нужен комплект RM-217:", 120f, 220f, paint)
            canvas.drawText("MCUSW  +  PPM  +  IMAGE", 120f, 235f, paint)
            canvas.drawText("Файлы останутся на этом устройстве", 120f, 253f, paint)
            return
        }

        paint.color = 0xFFBDE8CB.toInt()
        paint.textSize = 8.2f
        canvas.drawText(
            if (summary != null) "ФАЙЛЫ ДОБАВЛЕНЫ В ХРАНИЛИЩЕ"
            else "КОМПОНЕНТ ДОБАВЛЕН В ХРАНИЛИЩЕ",
            120f, 61f, paint
        )

        val version = summary?.version
        val rows = listOf(
            Triple("MCU", FirmwareImageKind.CORE, "mcusw"),
            Triple("PPM", FirmwareImageKind.LANGUAGE_PACK, "ppm_"),
            Triple("IMAGE", FirmwareImageKind.CONTENT_PACK, "image_")
        )
        rows.forEachIndexed { index, row ->
            val y = 88f + index * 22f
            val record = firmwareImages.firstOrNull { item ->
                item.metadata.kind == row.second &&
                    (version == null || item.metadata.originalFileName.contains(version))
            }
            val check = summary?.checks?.firstOrNull {
                it.fileName.equals(record?.metadata?.originalFileName, ignoreCase = true)
            }
            paint.color = 0x99304F69.toInt()
            canvas.drawRoundRect(RectF(12f, y - 14f, 228f, y + 5f), 4f, 4f, paint)
            paint.textAlign = Paint.Align.LEFT
            paint.color = 0xFFEAF2F7.toInt()
            paint.textSize = 8f
            paint.isFakeBoldText = true
            canvas.drawText(row.first, 19f, y - 1f, paint)
            paint.isFakeBoldText = false
            paint.textAlign = Paint.Align.RIGHT
            paint.color = when {
                record == null -> 0xFFFFC2A8.toInt()
                check?.matches == false -> 0xFFFFC078.toInt()
                check?.matches == true -> 0xFF9BE0AF.toInt()
                else -> 0xFFC1D8E6.toInt()
            }
            canvas.drawText(
                when {
                    record == null -> "НЕТ"
                    check?.matches == false -> "CRC НЕ СОВПАЛ"
                    check?.matches == true -> "CRC OK"
                    else -> "СТРУКТУРА OK"
                },
                220f, y - 1f, paint
            )
        }

        val mismatches = summary?.mismatchingChecks.orEmpty()
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 7.6f
        paint.color = when {
            summary == null || !summary.manifestFound -> 0xFFFFD18F.toInt()
            mismatches.isNotEmpty() -> 0xFFFFC078.toInt()
            summary.allChecksMatch -> 0xFF9BE0AF.toInt()
            else -> 0xFFFFD18F.toInt()
        }
        val checksumText = when {
            summary == null -> "CRC из VPL не проверялись"
            !summary.manifestFound -> "VPL для выбранного набора не найден"
            mismatches.isNotEmpty() -> "VPL CRC: ${summary.matchingChecks}/3 совпали"
            summary.allChecksMatch -> "VPL CRC: 3/3 совпали"
            else -> "VPL CRC: проверка неполная"
        }
        canvas.drawText(checksumText, 120f, 164f, paint)

        drawImportButton(canvas, RectF(13f, 184f, 227f, 232f))

        paint.color = 0xFFC1D8E6.toInt()
        paint.textSize = 7.5f
        if (mismatches.isNotEmpty()) {
            val mismatch = mismatches.first()
            canvas.drawText(
                "${mismatch.fileName.substringAfterLast('.') .uppercase()} CRC отличается от VPL",
                120f, 247f, paint
            )
            canvas.drawText(
                "VPL ${mismatch.expectedCrc32}  /  FILE ${mismatch.actualCrc32}",
                120f, 260f, paint
            )
        } else {
            canvas.drawText("Файлы сохранены только локально", 120f, 251f, paint)
        }
        paint.color = 0xFF91AFC0.toInt()
        paint.textSize = 7f
        canvas.drawText("Эмулятор ОС RM-217 пока не подключён", 120f, 276f, paint)
    }

    private fun drawStatusCard(
        canvas: Canvas,
        rect: RectF,
        title: String,
        subtitle: String
    ) {
        paint.color = 0x99304F69.toInt()
        canvas.drawRoundRect(rect, 7f, 7f, paint)
        paint.color = 0xFFF4F8FB.toInt()
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 9f
        paint.isFakeBoldText = true
        canvas.drawText(title, rect.centerX(), rect.centerY() - 5f, paint)
        paint.isFakeBoldText = false
        paint.color = 0xFFC1D8E6.toInt()
        paint.textSize = 7.6f
        canvas.drawText(subtitle, rect.centerX(), rect.centerY() + 12f, paint)
    }

    private fun drawImportButton(canvas: Canvas, rect: RectF) {
        paint.color = if (importing) 0xFF41616F.toInt() else 0xFF087FB7.toInt()
        canvas.drawRoundRect(rect, 7f, 7f, paint)
        paint.color = 0xFFDAF3FF.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        canvas.drawRoundRect(rect, 7f, 7f, paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = if (importing) 9f else 10f
        paint.isFakeBoldText = true
        canvas.drawText(
            when {
                importing -> "ПРОВЕРКА АРХИВА..."
                firmwareImages.isNotEmpty() -> "ЗАГРУЗИТЬ / ЗАМЕНИТЬ"
                else -> "ЗАГРУЗИТЬ ПРОШИВКУ"
            },
            rect.centerX(), rect.centerY() + 3.5f, paint
        )
        paint.isFakeBoldText = false
    }

    private fun drawSoftKeyBar(canvas: Canvas) {
        paint.color = 0xD90A1019.toInt()
        canvas.drawRect(0f, 296f, 240f, 320f, paint)
        paint.color = 0xFFF4F8FB.toInt()
        paint.textSize = 8f
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText("ЗАГРУЗИТЬ", 8f, 311f, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("ИНФО", 232f, 311f, paint)
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
        profile.keypad.controls
            .filter { it.visual != ControlVisual.HIT_AREA }
            .forEach { control ->
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
                paint.textSize = if (control.visual == ControlVisual.NUMERIC_KEY) 18f else 10f
                val label = when (control.key) {
                    CanonicalKey.SOFT_LEFT -> "LOAD"
                    CanonicalKey.SOFT_RIGHT -> "INFO"
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
        val control = profile.keypad.controls.firstOrNull {
            it.bounds.toControlCanvasRect().contains(x, y)
        }

        if (control != null) {
            handleKey(control.key)
        } else {
            val displayRect = displayScreenRect(scaleX, scaleY)
            if (displayRect.contains(x, y)) {
                val displayX = (x - displayRect.left) * 240f / displayRect.width()
                val displayY = (y - displayRect.top) * 320f / displayRect.height()
                if (importButtonRect().contains(displayX, displayY) && !importing) {
                    onImportFirmwareRequested?.invoke()
                }
            }
        }

        performClick()
        invalidate()
        return true
    }

    private fun importButtonRect(): RectF =
        if (firmwareImages.isEmpty()) RectF(13f, 143f, 227f, 196f)
        else RectF(13f, 184f, 227f, 232f)

    private fun handleKey(key: CanonicalKey) {
        when (key) {
            CanonicalKey.SELECT, CanonicalKey.SOFT_LEFT -> {
                if (!importing) onImportFirmwareRequested?.invoke()
            }
            CanonicalKey.SOFT_RIGHT -> onFirmwareInfoRequested?.invoke()
            else -> Unit
        }
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun handleBack(): Boolean = false

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
    }
}

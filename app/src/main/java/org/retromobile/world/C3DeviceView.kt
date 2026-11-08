package org.retromobile.world

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Photographic Nokia C3-00 shell with the guest LCD perspective-mapped into
 * the real display opening. Touch targets follow the photographed keycaps.
 */
class C3DeviceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private data class Hotspot(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val matrixIndex: Int
    ) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
        val radius: Float get() = minOf(right - left, bottom - top) * 0.23f

        // Each visible circle is also the actual touch target; no large invisible
        // rectangles extend into neighboring keys.
        fun contains(x: Float, y: Float): Boolean {
            val dx = x - centerX
            val dy = y - centerY
            return dx * dx + dy * dy <= radius * radius
        }
    }

    private val photo: Bitmap? = decodePhoto(context)
    private val lcd = Bitmap.createBitmap(
        C3EmuEngine.LCD_WIDTH,
        C3EmuEngine.LCD_HEIGHT,
        Bitmap.Config.ARGB_8888
    )
    private val rgbaPixels = IntArray(C3EmuEngine.LCD_WIDTH * C3EmuEngine.LCD_HEIGHT)
    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val lcdPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val hotspotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(24, 255, 255, 255)
    }
    private val hotspotStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        color = Color.argb(82, 255, 255, 255)
    }
    private val lcdMatrix = Matrix()
    private val displayPath = Path()
    private val activePointers = mutableMapOf<Int, Int>()
    private var imageScale = 1f
    private var imageLeft = 0f
    private var imageTop = 0f

    var onMatrixKey: ((Int, Boolean) -> Unit)? = null

    private val hotspots: List<Hotspot> = buildList {
        // Left / right soft keys and their adjacent contacts / message shortcuts.
        add(Hotspot(216f, 542f, 322f, 588f, 29))
        add(Hotspot(313f, 550f, 354f, 592f, 38))
        add(Hotspot(535f, 542f, 629f, 590f, 43))
        add(Hotspot(495f, 558f, 533f, 596f, 52))

        // Green call and red end keys.
        add(Hotspot(230f, 594f, 291f, 637f, 30))
        add(Hotspot(535f, 594f, 590f, 638f, 255))

        // Four-way navigation ring and centre select key.
        add(Hotspot(389f, 500f, 451f, 545f, 56))
        add(Hotspot(389f, 607f, 452f, 651f, 57))
        add(Hotspot(342f, 543f, 390f, 610f, 58))
        add(Hotspot(450f, 543f, 496f, 610f, 59))
        add(Hotspot(390f, 545f, 451f, 608f, 60))

        val firstRow = intArrayOf(0, 1, 2, 3, 4, 5, 6, 24, 25, 26)
        val secondRow = intArrayOf(8, 9, 10, 11, 12, 13, 14, 32, 33, 34)
        val thirdRow = intArrayOf(16, 17, 18, 19, 20, 21, 22, 45, 28, 42)
        addKeyboardRow(642f, 700f, firstRow)
        addKeyboardRow(701f, 756f, secondRow)
        addKeyboardRow(757f, 824f, thirdRow)

        // Modifier keys and the long space bar along the bottom edge.
        add(Hotspot(184f, 821f, 254f, 895f, 35)) // Shift
        add(Hotspot(254f, 831f, 330f, 905f, 48)) // Sym
        add(Hotspot(329f, 847f, 485f, 913f, 49)) // Space
        add(Hotspot(555f, 830f, 650f, 907f, 50)) // Fn / Ctrl
    }

    private fun MutableList<Hotspot>.addKeyboardRow(
        top: Float,
        bottom: Float,
        indices: IntArray
    ) {
        val left = 184f
        val right = 650f
        val width = (right - left) / indices.size
        indices.forEachIndexed { index, matrixIndex ->
            add(
                Hotspot(
                    left + index * width,
                    top,
                    left + (index + 1) * width,
                    bottom,
                    matrixIndex
                )
            )
        }
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isClickable = true
        contentDescription = "Nokia C3-00 with interactive physical keyboard"
    }

    fun showFrame(frame: PhoneMachineFrame) {
        if (frame.width != C3EmuEngine.LCD_WIDTH ||
            frame.height != C3EmuEngine.LCD_HEIGHT ||
            frame.pixelFormat != FramePixelFormat.RGBA_8888
        ) return

        val source = frame.pixels
        val rowStride = frame.rowStrideBytes
        var pixelIndex = 0
        for (y in 0 until frame.height) {
            var offset = y * rowStride
            for (x in 0 until frame.width) {
                val red = source[offset].toInt() and 0xFF
                val green = source[offset + 1].toInt() and 0xFF
                val blue = source[offset + 2].toInt() and 0xFF
                val alpha = source[offset + 3].toInt() and 0xFF
                rgbaPixels[pixelIndex++] =
                    (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                offset += 4
            }
        }
        lcd.setPixels(
            rgbaPixels,
            0,
            C3EmuEngine.LCD_WIDTH,
            0,
            0,
            C3EmuEngine.LCD_WIDTH,
            C3EmuEngine.LCD_HEIGHT
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(13, 16, 19))
        val image = photo ?: return

        imageScale = minOf(
            width.toFloat() / image.width.coerceAtLeast(1),
            height.toFloat() / image.height.coerceAtLeast(1)
        )
        imageLeft = (width - image.width * imageScale) / 2f
        imageTop = (height - image.height * imageScale) / 2f

        canvas.save()
        canvas.translate(imageLeft, imageTop)
        canvas.scale(imageScale, imageScale)
        canvas.drawBitmap(image, 0f, 0f, photoPaint)

        // Coordinates are measured on the 750 x 1000 Commons preview.
        val photoUnits = image.width / 750f
        val points = floatArrayOf(
            228f * photoUnits, 120f * photoUnits,
            652f * photoUnits, 153f * photoUnits,
            625f * photoUnits, 443f * photoUnits,
            209f * photoUnits, 444f * photoUnits
        )
        val source = floatArrayOf(
            0f, 0f,
            C3EmuEngine.LCD_WIDTH.toFloat(), 0f,
            C3EmuEngine.LCD_WIDTH.toFloat(), C3EmuEngine.LCD_HEIGHT.toFloat(),
            0f, C3EmuEngine.LCD_HEIGHT.toFloat()
        )
        lcdMatrix.setPolyToPoly(source, 0, points, 0, 4)

        displayPath.reset()
        displayPath.moveTo(points[0], points[1])
        displayPath.lineTo(points[2], points[3])
        displayPath.lineTo(points[4], points[5])
        displayPath.lineTo(points[6], points[7])
        displayPath.close()

        canvas.save()
        canvas.clipPath(displayPath)
        canvas.drawBitmap(lcd, lcdMatrix, lcdPaint)
        canvas.restore()

        // Subtle calibration circles are centred on the current key estimates.
        // Their geometry is shared with hit-testing so the visible and active
        // touch areas are identical. Positions remain easy to tune after photo review.
        hotspots.forEach { hotspot ->
            val x = hotspot.centerX * photoUnits
            val y = hotspot.centerY * photoUnits
            val radius = hotspot.radius * photoUnits
            canvas.drawCircle(x, y, radius, hotspotFillPaint)
            canvas.drawCircle(x, y, radius, hotspotStrokePaint)
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = event.actionIndex
                updatePointer(
                    event.getPointerId(pointerIndex),
                    event.getX(pointerIndex),
                    event.getY(pointerIndex)
                )
            }

            MotionEvent.ACTION_MOVE -> {
                for (pointerIndex in 0 until event.pointerCount) {
                    updatePointer(
                        event.getPointerId(pointerIndex),
                        event.getX(pointerIndex),
                        event.getY(pointerIndex)
                    )
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                releasePointer(event.getPointerId(event.actionIndex))
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }

            MotionEvent.ACTION_CANCEL -> {
                activePointers.values.forEach { onMatrixKey?.invoke(it, false) }
                activePointers.clear()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updatePointer(pointerId: Int, x: Float, y: Float) {
        val nextKey = matrixKeyAt(x, y)
        val previousKey = activePointers[pointerId]
        if (previousKey == nextKey) return

        if (previousKey != null) {
            onMatrixKey?.invoke(previousKey, false)
            activePointers.remove(pointerId)
        }
        if (nextKey != null) {
            activePointers[pointerId] = nextKey
            onMatrixKey?.invoke(nextKey, true)
        }
    }

    private fun releasePointer(pointerId: Int) {
        val key = activePointers.remove(pointerId) ?: return
        onMatrixKey?.invoke(key, false)
    }

    private fun matrixKeyAt(x: Float, y: Float): Int? {
        val image = photo ?: return null
        if (imageScale <= 0f) return null

        val localX = (x - imageLeft) / imageScale
        val localY = (y - imageTop) / imageScale
        if (localX !in 0f..image.width.toFloat() ||
            localY !in 0f..image.height.toFloat()
        ) return null

        val referenceX = localX / image.width * 750f
        val referenceY = localY / image.height * 1000f
        return hotspots.firstOrNull { it.contains(referenceX, referenceY) }?.matrixIndex
    }

    private fun decodePhoto(context: Context): Bitmap? = runCatching {
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        context.assets.open("c3emu/75-205-263-02.png").use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    }.getOrNull()
}

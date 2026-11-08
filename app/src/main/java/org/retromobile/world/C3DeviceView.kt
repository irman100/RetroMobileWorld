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
 * Nokia C3-00 shell using the exact front-facing 968 x 1920 reference photo.
 *
 * LCD geometry and touch coordinates are expressed in that photo's native
 * coordinate system, so display placement and hit testing cannot drift apart
 * when the bitmap is sampled or the view is resized.
 */
class C3DeviceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Hotspot(
        val centerX: Float,
        val centerY: Float,
        val radius: Float,
        val matrixIndex: Int
    ) {
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
    private val lcdMatrix = Matrix()
    private val displayPath = Path()
    private val activePointers = mutableMapOf<Int, Int>()

    private var imageScale = 1f
    private var imageLeft = 0f
    private var imageTop = 0f

    var onMatrixKey: ((Int, Boolean) -> Unit)? = null

    /**
     * First-pass touch calibration, centred on the visible physical keycaps.
     * These are intentionally small circular regions. We can fine-tune their
     * centres/radii independently after the user's on-device/photo review.
     */
    private val hotspots = listOf(
        // Selection keys: the two short horizontal bars above Contacts/Messaging.
        Hotspot(194f, 1050f, 14f, 29), // Left: Names / Options
        Hotspot(775f, 1050f, 14f, 43), // Right: Alarm / Exit

        // Dedicated chrome Contacts and Messaging buttons; not the printed icons.
        Hotspot(140f, 1129f, 17f, 38),
        Hotspot(824f, 1129f, 17f, 52),

        // Green call and red end keys.
        Hotspot(197f, 1207f, 17f, 30),
        Hotspot(755f, 1207f, 17f, 255),

        // Navigation ring: directional targets sit on the chrome bezel,
        // while OK stays centred on the inner select button.
        Hotspot(480f, 1022f, 16f, 56),
        Hotspot(480f, 1236f, 16f, 57),
        Hotspot(362f, 1127f, 16f, 58),
        Hotspot(598f, 1127f, 16f, 59),
        Hotspot(480f, 1127f, 20f, 60),

        // Physical QWERTY / number row. Key centres follow the photographed caps.
        Hotspot(95.5f, 1333f, 14f, 0),
        Hotspot(180f, 1339f, 14f, 1),
        Hotspot(266f, 1342f, 14f, 2),
        Hotspot(351.5f, 1344f, 14f, 3),
        Hotspot(437f, 1345f, 14f, 4),
        Hotspot(523f, 1345f, 14f, 5),
        Hotspot(608f, 1344f, 14f, 6),
        Hotspot(694.5f, 1342f, 14f, 24),
        Hotspot(780f, 1339f, 14f, 25),
        Hotspot(865f, 1333f, 14f, 26),

        Hotspot(95.5f, 1460f, 14f, 8),
        Hotspot(180f, 1466f, 14f, 9),
        Hotspot(266f, 1469f, 14f, 10),
        Hotspot(351.5f, 1471f, 14f, 11),
        Hotspot(437f, 1472f, 14f, 12),
        Hotspot(523f, 1472f, 14f, 13),
        Hotspot(608f, 1471f, 14f, 14),
        Hotspot(694.5f, 1469f, 14f, 32),
        Hotspot(780f, 1466f, 14f, 33),
        Hotspot(865f, 1460f, 14f, 34),

        // Lowest QWERTY row: centres follow the curved dark separators
        // between the actual keycaps rather than a flat keyboard baseline.
        Hotspot(95.5f, 1553f, 14f, 16),
        Hotspot(180f, 1571f, 14f, 17),
        Hotspot(266f, 1586f, 14f, 18),
        Hotspot(351.5f, 1598f, 14f, 19),
        Hotspot(437f, 1605f, 14f, 20),
        Hotspot(523f, 1605f, 14f, 21),
        Hotspot(608f, 1598f, 14f, 22),
        Hotspot(694.5f, 1586f, 14f, 45),
        Hotspot(780f, 1571f, 14f, 28),
        Hotspot(865f, 1553f, 14f, 42),

        // Bottom modifier row: centres of each keycap, measured between
        // its dark neighbouring dividers. The curved row is not evenly spaced.
        Hotspot(95f, 1677f, 14f, 39),   // Diagonal-arrow Function key
        Hotspot(181f, 1709f, 14f, 35),  // Shift
        Hotspot(266f, 1730f, 14f, 48),  // Symbol / Car-labelled key
        Hotspot(479f, 1740f, 20f, 49),  // Space
        Hotspot(690f, 1722f, 14f, 40),  // Punctuation / symbol key (: and slash)
        Hotspot(775f, 1698f, 14f, 41),  // Comma / period key
        Hotspot(869f, 1666f, 14f, 50)   // Ctrl
    )

    private val hotspotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(8, 20, 24, 28)
    }
    private val hotspotStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.25f
        color = Color.argb(52, 20, 24, 28)
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

        val photoScaleX = image.width / PHOTO_REFERENCE_WIDTH
        val photoScaleY = image.height / PHOTO_REFERENCE_HEIGHT

        canvas.save()
        canvas.translate(imageLeft, imageTop)
        canvas.scale(imageScale, imageScale)
        canvas.drawBitmap(image, 0f, 0f, photoPaint)

        // Exact transparent LCD aperture in docs/75-205-263-02.png (968 x 1920).
        val points = floatArrayOf(
            LCD_LEFT * photoScaleX, LCD_TOP * photoScaleY,
            LCD_RIGHT * photoScaleX, LCD_TOP * photoScaleY,
            LCD_RIGHT * photoScaleX, LCD_BOTTOM * photoScaleY,
            LCD_LEFT * photoScaleX, LCD_BOTTOM * photoScaleY
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

        // The drawn marker and its touch target use the same native-photo centre.
        hotspots.forEach { hotspot ->
            val x = hotspot.centerX * photoScaleX
            val y = hotspot.centerY * photoScaleY
            val radius = hotspot.radius * minOf(photoScaleX, photoScaleY)
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

        val referenceX = localX / image.width * PHOTO_REFERENCE_WIDTH
        val referenceY = localY / image.height * PHOTO_REFERENCE_HEIGHT
        return hotspots.firstOrNull { it.contains(referenceX, referenceY) }?.matrixIndex
    }

    private fun decodePhoto(context: Context): Bitmap? = runCatching {
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        context.assets.open("c3emu/75-205-263-02.png").use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    }.getOrNull()

    companion object {
        private const val PHOTO_REFERENCE_WIDTH = 968f
        private const val PHOTO_REFERENCE_HEIGHT = 1920f

        // Edge coordinates of the transparent screen window in the original photo.
        private const val LCD_LEFT = 82f
        private const val LCD_TOP = 289f
        private const val LCD_RIGHT = 884f
        private const val LCD_BOTTOM = 898f
    }
}

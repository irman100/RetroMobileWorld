package org.retromobile.world

/**
 * Converts a logical screen aspect ratio into design-space width when the parent
 * canvas uses independent X/Y scale factors.
 */
internal object DisplayAspectGeometry {
    fun designWidthForNativeAspect(
        displayWidth: Int,
        displayHeight: Int,
        designHeight: Float,
        canvasScaleX: Float,
        canvasScaleY: Float
    ): Float {
        require(displayWidth > 0) { "Display width must be positive." }
        require(displayHeight > 0) { "Display height must be positive." }
        require(designHeight > 0f) { "Design height must be positive." }
        require(canvasScaleX > 0f && canvasScaleY > 0f) { "Canvas scales must be positive." }

        return designHeight *
            (displayWidth.toFloat() / displayHeight.toFloat()) *
            (canvasScaleY / canvasScaleX)
    }
}

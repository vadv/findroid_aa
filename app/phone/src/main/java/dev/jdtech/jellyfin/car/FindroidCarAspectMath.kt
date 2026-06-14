package dev.jdtech.jellyfin.car

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure math for picking how to display a video frame inside the AA surface.
 *
 * Replicates the algorithm used by `androidx.media3.ui.AspectRatioFrameLayout` so we can
 * unit-test the decision (which resize mode + the resulting on-screen rectangle) without
 * pulling in Android view code. The presentation forwards the chosen [FindroidCarAspectResize]
 * to the actual `AspectRatioFrameLayout` and relies on `Gravity.CENTER` to keep the rectangle
 * symmetric inside the surface.
 */
internal enum class FindroidCarAspectResize {
    FIT,
    ZOOM,
}

internal data class FindroidCarAspectDecision(
    val sourceDar: Float,
    val surfaceDar: Float,
    /**
     * Difference between source DAR and surface DAR expressed as a fraction of the *larger*
     * axis: how much of the longer dimension would need to be cropped if we filled the surface.
     * Always >= 0; 0 means source and surface match exactly.
     */
    val fillCropFraction: Float,
    val resize: FindroidCarAspectResize,
    /** Public-facing label, matches the strings logged today (`FIT`, `AUTO_FIT`, ...). */
    val appliedLabel: String,
    /** Width of the rendered video rectangle inside the surface, in pixels. */
    val destWidth: Int,
    /** Height of the rendered video rectangle inside the surface, in pixels. */
    val destHeight: Int,
)

internal object FindroidCarAspectMath {
    const val SMART_CROP_MAX_FRACTION = 0.08f

    fun decide(
        videoWidth: Int,
        videoHeight: Int,
        pixelWidthHeightRatio: Float,
        surfaceWidth: Int,
        surfaceHeight: Int,
        mode: FindroidCarVideoAspectMode,
    ): FindroidCarAspectDecision? {
        if (videoWidth <= 0 || videoHeight <= 0) return null
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return null
        val par = if (pixelWidthHeightRatio > 0f) pixelWidthHeightRatio else 1f
        val sourceDar = (videoWidth * par) / videoHeight
        val surfaceDar = surfaceWidth.toFloat() / surfaceHeight
        val fillCropFraction = computeFillCropFraction(sourceDar, surfaceDar)
        val useZoom =
            when (mode) {
                FindroidCarVideoAspectMode.AUTO -> fillCropFraction <= SMART_CROP_MAX_FRACTION
                FindroidCarVideoAspectMode.FIT -> false
                FindroidCarVideoAspectMode.CROP -> true
            }
        val resize = if (useZoom) FindroidCarAspectResize.ZOOM else FindroidCarAspectResize.FIT
        val (destW, destH) =
            computeDestSize(
                sourceDar = sourceDar,
                surfaceDar = surfaceDar,
                surfaceWidth = surfaceWidth,
                surfaceHeight = surfaceHeight,
                resize = resize,
            )
        val label =
            when {
                mode == FindroidCarVideoAspectMode.AUTO && useZoom -> "AUTO_SMART_ZOOM"
                mode == FindroidCarVideoAspectMode.AUTO -> "AUTO_FIT"
                mode == FindroidCarVideoAspectMode.CROP -> "CROP_FULLSCREEN"
                else -> "FIT"
            }
        return FindroidCarAspectDecision(
            sourceDar = sourceDar,
            surfaceDar = surfaceDar,
            fillCropFraction = fillCropFraction,
            resize = resize,
            appliedLabel = label,
            destWidth = destW,
            destHeight = destH,
        )
    }

    /**
     * Fraction of the longer surface axis that would be cropped if we ZOOM-filled, relative
     * to the smaller axis. 0 when source DAR == surface DAR.
     */
    private fun computeFillCropFraction(sourceDar: Float, surfaceDar: Float): Float =
        if (sourceDar > surfaceDar) 1f - (surfaceDar / sourceDar)
        else 1f - (sourceDar / surfaceDar)

    /**
     * Replicates the `AspectRatioFrameLayout.onMeasure` shrink-or-grow step. With FIT the
     * destination is at most the surface; with ZOOM it is at least the surface (the overflow
     * is what gets cropped). The destination is centered inside the surface by `Gravity.CENTER`
     * on the parent layout — this function only reports the dimensions, not the offset.
     */
    private fun computeDestSize(
        sourceDar: Float,
        surfaceDar: Float,
        surfaceWidth: Int,
        surfaceHeight: Int,
        resize: FindroidCarAspectResize,
    ): Pair<Int, Int> {
        val aspectDeformation = (sourceDar / surfaceDar) - 1f
        if (abs(aspectDeformation) < 1e-4f) return surfaceWidth to surfaceHeight
        return when (resize) {
            FindroidCarAspectResize.FIT ->
                if (aspectDeformation > 0f) {
                    surfaceWidth to (surfaceWidth / sourceDar).toInt()
                } else {
                    (surfaceHeight * sourceDar).toInt() to surfaceHeight
                }
            FindroidCarAspectResize.ZOOM ->
                if (aspectDeformation > 0f) {
                    (surfaceHeight * sourceDar).toInt() to surfaceHeight
                } else {
                    surfaceWidth to (surfaceWidth / sourceDar).toInt()
                }
        }
    }

    /** Top-left offset that centers a [destWidth] × [destHeight] rectangle inside the surface. */
    fun centerOffsetX(surfaceWidth: Int, destWidth: Int): Int = (surfaceWidth - destWidth) / 2

    fun centerOffsetY(surfaceHeight: Int, destHeight: Int): Int = (surfaceHeight - destHeight) / 2

    /**
     * Returns the per-side symmetric letterbox or pillarbox thickness once the destination is
     * centered. Positive when FIT leaves a black border, negative when ZOOM overflows.
     */
    fun symmetricInset(surfaceMain: Int, destMain: Int): Int = (surfaceMain - destMain) / 2

    /**
     * Sanity helper used by tests: ensures the asymmetric drift (left vs right, or top vs
     * bottom) is at most 1 pixel due to integer rounding.
     */
    fun asymmetryDrift(surfaceMain: Int, destMain: Int): Int =
        abs((surfaceMain - destMain) - 2 * symmetricInset(surfaceMain, destMain))

    fun maxAsymmetryAfterCentering(surfaceWidth: Int, surfaceHeight: Int, destWidth: Int, destHeight: Int): Int =
        max(asymmetryDrift(surfaceWidth, destWidth), asymmetryDrift(surfaceHeight, destHeight))

    /** Minimum side of the actually-visible rectangle inside the surface, for tests. */
    fun visibleSide(surfaceWidth: Int, surfaceHeight: Int, destWidth: Int, destHeight: Int): Int =
        min(min(destWidth, surfaceWidth), min(destHeight, surfaceHeight))
}

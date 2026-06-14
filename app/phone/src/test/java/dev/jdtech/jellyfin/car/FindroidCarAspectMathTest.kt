package dev.jdtech.jellyfin.car

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CS95 ultra-wide example: a 1920×720 surface (DAR ≈ 2.667). All tests below use that
 * surface, varying the source DAR to cover the standard 4:3, 16:9, 21:9 cases plus a near-fit
 * 2.5:1 case that should activate SMART/AUTO's "almost matches" zoom.
 */
class FindroidCarAspectMathTest {
    private val surfaceWidth = 1920
    private val surfaceHeight = 720

    @Test
    fun returns_null_when_video_or_surface_dimensions_are_zero() {
        assertNull(decide(0, 720, mode = FindroidCarVideoAspectMode.AUTO))
        assertNull(decide(1920, 0, mode = FindroidCarVideoAspectMode.AUTO))
        assertNull(
            FindroidCarAspectMath.decide(
                videoWidth = 1920,
                videoHeight = 1080,
                pixelWidthHeightRatio = 1f,
                surfaceWidth = 0,
                surfaceHeight = 720,
                mode = FindroidCarVideoAspectMode.AUTO,
            )
        )
    }

    @Test
    fun four_three_source_auto_falls_to_fit_and_centers_pillarbox_equally() {
        val decision = requireDecide(640, 480, mode = FindroidCarVideoAspectMode.AUTO)

        assertCloseTo(4f / 3f, decision.sourceDar)
        assertCloseTo(1920f / 720f, decision.surfaceDar)
        // Difference between 1.333 and 2.667 expressed via fillCropFraction:
        // 1 - (1.333 / 2.667) = 0.5
        assertCloseTo(0.5f, decision.fillCropFraction)
        // 0.5 >> SMART threshold 0.08 → AUTO picks FIT.
        assertEquals(FindroidCarAspectResize.FIT, decision.resize)
        assertEquals("AUTO_FIT", decision.appliedLabel)
        // height = 720, width = 720 * 4/3 = 960. Equal 480-px black bars on both sides.
        assertEquals(960, decision.destWidth)
        assertEquals(720, decision.destHeight)
        assertEquals(480, FindroidCarAspectMath.symmetricInset(surfaceWidth, decision.destWidth))
        assertEquals(0, FindroidCarAspectMath.symmetricInset(surfaceHeight, decision.destHeight))
        assertTrue(
            "horizontal asymmetry after centering must be < 2 px",
            FindroidCarAspectMath.maxAsymmetryAfterCentering(
                surfaceWidth,
                surfaceHeight,
                decision.destWidth,
                decision.destHeight,
            ) <= 1,
        )
    }

    @Test
    fun four_three_source_crop_zooms_and_overflows_top_bottom_symmetrically() {
        val decision = requireDecide(640, 480, mode = FindroidCarVideoAspectMode.CROP)

        assertEquals(FindroidCarAspectResize.ZOOM, decision.resize)
        assertEquals("CROP_FULLSCREEN", decision.appliedLabel)
        // ZOOM grows the smaller axis to fill the larger. Source narrower → grow height:
        // height = width / sourceDar = 1920 / (4/3) = 1440.
        assertEquals(1920, decision.destWidth)
        assertEquals(1440, decision.destHeight)
        // Surface is 720; with destHeight=1440 the overflow is 720 px split top/bottom → 360 each.
        assertEquals(-360, FindroidCarAspectMath.symmetricInset(surfaceHeight, decision.destHeight))
        assertEquals(0, FindroidCarAspectMath.symmetricInset(surfaceWidth, decision.destWidth))
    }

    @Test
    fun four_three_source_explicit_fit_matches_auto_fit_geometry() {
        val auto = requireDecide(640, 480, mode = FindroidCarVideoAspectMode.AUTO)
        val fit = requireDecide(640, 480, mode = FindroidCarVideoAspectMode.FIT)

        assertEquals(auto.resize, fit.resize)
        assertEquals(auto.destWidth, fit.destWidth)
        assertEquals(auto.destHeight, fit.destHeight)
        assertEquals("FIT", fit.appliedLabel)
    }

    @Test
    fun sixteen_nine_source_auto_picks_fit_with_smaller_pillarbox() {
        val decision = requireDecide(1920, 1080, mode = FindroidCarVideoAspectMode.AUTO)

        assertCloseTo(16f / 9f, decision.sourceDar)
        // 1 - (16/9) / (1920/720) = 1 - 1.778/2.667 ≈ 0.333
        assertCloseTo(0.333f, decision.fillCropFraction)
        assertEquals(FindroidCarAspectResize.FIT, decision.resize)
        // height = 720, width = 720 * 16/9 = 1280. Pillarbox = (1920-1280)/2 = 320.
        assertEquals(1280, decision.destWidth)
        assertEquals(720, decision.destHeight)
        assertEquals(320, FindroidCarAspectMath.symmetricInset(surfaceWidth, decision.destWidth))
    }

    @Test
    fun twenty_one_nine_source_auto_picks_fit_with_thin_pillarbox() {
        val decision = requireDecide(2560, 1080, mode = FindroidCarVideoAspectMode.AUTO)

        // 2560/1080 ≈ 2.370. fillCropFraction = 1 - 2.370/2.667 ≈ 0.111. Still > 0.08 → FIT.
        assertCloseTo(2.37f, decision.sourceDar, eps = 0.01f)
        assertEquals(FindroidCarAspectResize.FIT, decision.resize)
        assertEquals("AUTO_FIT", decision.appliedLabel)
        // width = 720 * 2.370 = 1706 (integer truncation), height = 720.
        // Surface 1920 − 1706 = 214 → 107 px pillarbox each side; centered.
        val expectedWidth = (720f * (2560f / 1080f)).toInt()
        assertEquals(expectedWidth, decision.destWidth)
        assertEquals(720, decision.destHeight)
        val pillarbox = FindroidCarAspectMath.symmetricInset(surfaceWidth, decision.destWidth)
        assertTrue(
            "pillarbox should be small but symmetric, got $pillarbox",
            pillarbox in 100..120,
        )
    }

    @Test
    fun near_matching_source_auto_engages_smart_zoom_to_avoid_thin_bars() {
        // 2400/960 = 2.5 → fillCropFraction = 1 - 2.5/2.667 ≈ 0.063 < 0.08 → zoom.
        val decision = requireDecide(2400, 960, mode = FindroidCarVideoAspectMode.AUTO)

        assertCloseTo(2.5f, decision.sourceDar)
        assertTrue(
            "fillCropFraction must be below smart threshold (was ${decision.fillCropFraction})",
            decision.fillCropFraction < FindroidCarAspectMath.SMART_CROP_MAX_FRACTION,
        )
        assertEquals(FindroidCarAspectResize.ZOOM, decision.resize)
        assertEquals("AUTO_SMART_ZOOM", decision.appliedLabel)
        // Source wider than surface DAR → zoom grows the width and overflows horizontally.
        // height = 720, width = 720 * 2.5 = 1800; surface 1920 → 60 px on each side actually
        // *empty* because zoom for a wider-than-surface source means width = surfaceWidth?
        // Actually the algorithm grows the SHORTER side: aspectDeformation > 0 means source
        // wider, so width grows. Verify against the implementation:
        // aspectDeformation = 2.5/2.667 - 1 = -0.063 (source NARROWER than surface), so
        // FIT path would shrink width to 720*2.5=1800; ZOOM path GROWS height instead:
        // height = 1920/2.5 = 768. So destHeight = 768 → 24 px crop top/bottom each.
        assertEquals(1920, decision.destWidth)
        assertEquals((1920f / 2.5f).toInt(), decision.destHeight)
        assertEquals(
            -24,
            FindroidCarAspectMath.symmetricInset(surfaceHeight, decision.destHeight),
        )
    }

    @Test
    fun source_wider_than_surface_zoom_grows_width_for_symmetric_horizontal_crop() {
        // Cinemascope-ish: 4.0:1 source on 2.67:1 surface → source wider than surface.
        val decision = requireDecide(4000, 1000, mode = FindroidCarVideoAspectMode.CROP)

        assertEquals(FindroidCarAspectResize.ZOOM, decision.resize)
        assertEquals(4f, decision.sourceDar)
        // aspectDeformation > 0 → grow width: width = height * 4 = 2880. height = 720.
        assertEquals(2880, decision.destWidth)
        assertEquals(720, decision.destHeight)
        // Horizontal overflow = 2880 - 1920 = 960; split equally → 480 each side.
        assertEquals(-480, FindroidCarAspectMath.symmetricInset(surfaceWidth, decision.destWidth))
    }

    @Test
    fun source_wider_than_surface_fit_shrinks_height_for_symmetric_letterbox() {
        // 21:9 ultra-wide source (2.333:1) on standard 16:9 surface to exercise the
        // "source wider than surface" + FIT branch (letterbox top/bottom).
        val decision =
            FindroidCarAspectMath.decide(
                videoWidth = 2560,
                videoHeight = 1080,
                pixelWidthHeightRatio = 1f,
                surfaceWidth = 1920,
                surfaceHeight = 1080,
                mode = FindroidCarVideoAspectMode.FIT,
            )!!

        assertEquals(FindroidCarAspectResize.FIT, decision.resize)
        // height = 1920 / (2560/1080) = 810
        val expectedHeight = (1920f / (2560f / 1080f)).toInt()
        assertEquals(1920, decision.destWidth)
        assertEquals(expectedHeight, decision.destHeight)
        // Letterbox each side = (1080 - 810) / 2 = 135.
        val letterbox = FindroidCarAspectMath.symmetricInset(1080, decision.destHeight)
        assertEquals(135, letterbox)
    }

    @Test
    fun anamorphic_par_is_applied_to_source_dar_calculation() {
        // 720×576 PAL with 1.422 PAR → effective DAR ≈ 1.778 (16:9 widescreen).
        val decision =
            FindroidCarAspectMath.decide(
                videoWidth = 720,
                videoHeight = 576,
                pixelWidthHeightRatio = 1.422f,
                surfaceWidth = surfaceWidth,
                surfaceHeight = surfaceHeight,
                mode = FindroidCarVideoAspectMode.AUTO,
            )!!

        assertCloseTo(720f * 1.422f / 576f, decision.sourceDar)
        // ≈ 16:9 — same code path as the 1920×1080 case → FIT with width 1280.
        assertEquals(FindroidCarAspectResize.FIT, decision.resize)
        val expectedWidth = (720f * (720f * 1.422f / 576f)).toInt()
        assertEquals(expectedWidth, decision.destWidth)
    }

    @Test
    fun non_positive_par_falls_back_to_square_pixels() {
        val decisionZeroPar = requireDecide(1920, 1080, par = 0f, mode = FindroidCarVideoAspectMode.AUTO)
        val decisionUnitPar = requireDecide(1920, 1080, par = 1f, mode = FindroidCarVideoAspectMode.AUTO)
        assertCloseTo(decisionUnitPar.sourceDar, decisionZeroPar.sourceDar)
        assertEquals(decisionUnitPar.destWidth, decisionZeroPar.destWidth)
        assertEquals(decisionUnitPar.destHeight, decisionZeroPar.destHeight)
    }

    @Test
    fun exact_match_source_yields_full_surface_with_zero_inset() {
        val decision = requireDecide(1920, 720, mode = FindroidCarVideoAspectMode.AUTO)

        assertNotNull(decision)
        assertEquals(surfaceWidth, decision.destWidth)
        assertEquals(surfaceHeight, decision.destHeight)
        assertEquals(0, FindroidCarAspectMath.symmetricInset(surfaceWidth, decision.destWidth))
        assertEquals(0, FindroidCarAspectMath.symmetricInset(surfaceHeight, decision.destHeight))
        assertTrue(decision.fillCropFraction < FindroidCarAspectMath.SMART_CROP_MAX_FRACTION)
    }

    private fun decide(
        videoWidth: Int,
        videoHeight: Int,
        par: Float = 1f,
        mode: FindroidCarVideoAspectMode,
    ): FindroidCarAspectDecision? =
        FindroidCarAspectMath.decide(
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            pixelWidthHeightRatio = par,
            surfaceWidth = surfaceWidth,
            surfaceHeight = surfaceHeight,
            mode = mode,
        )

    private fun requireDecide(
        videoWidth: Int,
        videoHeight: Int,
        par: Float = 1f,
        mode: FindroidCarVideoAspectMode,
    ): FindroidCarAspectDecision =
        checkNotNull(decide(videoWidth, videoHeight, par, mode))

    private fun assertCloseTo(expected: Float, actual: Float, eps: Float = 0.001f) {
        assertTrue(
            "expected $expected ± $eps but was $actual",
            abs(expected - actual) <= eps,
        )
    }
}

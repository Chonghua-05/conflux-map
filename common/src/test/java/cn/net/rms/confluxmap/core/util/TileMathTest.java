package cn.net.rms.confluxmap.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TileMathTest {
    @Test
    void zoomStepsKeepFullResolutionUntilTheNextPowerOfTwo() {
        assertEquals(0, TileMath.lodForScale(0.25));
        assertEquals(0, TileMath.lodForScale(1.0));
        assertEquals(0, TileMath.lodForScale(1.26));
        assertEquals(1, TileMath.lodForScale(2.0));
        assertEquals(1, TileMath.lodForScale(2.52));
        assertEquals(2, TileMath.lodForScale(4.0));
        assertEquals(2, TileMath.lodForScale(6.0));
        assertEquals(3, TileMath.lodForScale(8.0));
        assertEquals(4, TileMath.lodForScale(16.0));
    }

    @Test
    void selectedTexelStaysWithinAConstantScreenDensity() {
        for (double scale = 1.0; scale <= 16.0; scale *= 1.01) {
            final int lod = TileMath.lodForScale(scale);
            final double screenPixelsPerTexel = TileMath.blocksPerPixel(lod) / scale;
            assertTrue(
                screenPixelsPerTexel <= 1.0 + 1.0e-9,
                "LOD " + lod + " expands one texel over " + screenPixelsPerTexel + " screen pixels"
            );
            assertTrue(
                screenPixelsPerTexel >= 0.5 - 1.0e-9,
                "LOD " + lod + " drops below half-pixel detail at " + screenPixelsPerTexel
            );
        }
    }

    @Test
    void overlapUsesFloorAlignedLodCoverageAtNegativeCoordinates() {
        assertTrue(TileMath.overlaps(1, -1, -1, 0, -2, -2));
        assertTrue(TileMath.overlaps(1, -1, -1, 0, -1, -1));
        assertFalse(TileMath.overlaps(1, -1, -1, 0, 0, -1));
        assertFalse(TileMath.overlaps(0, -3, -2, 0, -2, -2));
    }

    @Test
    void lodForScaleStrictlyAnchorsToLodZeroWhenScaleAtOrBelowOne() {
        for (final cn.net.rms.confluxmap.core.config.ConfluxConfig.MapQuality q :
            cn.net.rms.confluxmap.core.config.ConfluxConfig.MapQuality.values()) {
            assertEquals(0, TileMath.lodForScale(0.0625, q));
            assertEquals(0, TileMath.lodForScale(0.25, q));
            assertEquals(0, TileMath.lodForScale(0.5, q));
            assertEquals(0, TileMath.lodForScale(1.0, q));
            assertEquals(0, TileMath.lodForScale(Double.NaN, q));
            assertEquals(0, TileMath.lodForScale(Double.NEGATIVE_INFINITY, q));
            assertEquals(0, TileMath.lodForScale(-5.0, q));
        }
    }

    @Test
    void mapQualityShiftsLodTransitionThresholds() {
        // At scale 2.5:
        // BALANCED (slope 1.0): exact = log2(2.5) ~ 1.32 -> lod 1
        // ULTRA_PERFORMANCE (slope 1.6): exact = 1.6 * log2(2.5) ~ 2.11 -> lod 2 (transitions earlier!)
        // ULTRA_QUALITY (slope 0.55): exact = 0.55 * log2(2.5) ~ 0.72 -> lod 0 (defers transition, keeps LOD 0!)
        assertEquals(2, TileMath.lodForScale(2.5, cn.net.rms.confluxmap.core.config.ConfluxConfig.MapQuality.ULTRA_PERFORMANCE));
        assertEquals(1, TileMath.lodForScale(2.5, cn.net.rms.confluxmap.core.config.ConfluxConfig.MapQuality.BALANCED));
        assertEquals(0, TileMath.lodForScale(2.5, cn.net.rms.confluxmap.core.config.ConfluxConfig.MapQuality.ULTRA_QUALITY));
    }
}

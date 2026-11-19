package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PackedSurfacePlane}'s short form is what a later storage change persists, so its contract
 * is exact: every height in the 12-bit signed range must survive a pack/unpack for every kind, the
 * int-level no-surface sentinel must never sneak in as a height, and VOID must round-trip as
 * {@link BaselineGrid#NO_SURFACE} no matter what height the caller passed.
 */
class PackedSurfacePlaneTest {
    @Test
    void roundTripsEveryHeightInTheTwelveBitRangeAcrossAllKinds() {
        for (int height = -2048; height <= 2047; height += 7) {
            for (int ordinal = 0; ordinal < SurfaceKind.values().length; ordinal++) {
                final short packed = PackedSurfacePlane.pack(height, ordinal);
                assertEquals(ordinal, PackedSurfacePlane.unpackKind(packed));
                if (ordinal == SurfaceKind.VOID.ordinal()) {
                    assertEquals(BaselineGrid.NO_SURFACE, PackedSurfacePlane.unpackHeight(packed),
                        "VOID is the no-surface encoding, whatever height was passed");
                } else {
                    assertEquals(height, PackedSurfacePlane.unpackHeight(packed));
                }
            }
        }
    }

    @Test
    void voidPackingIgnoresThePassedHeightEntirely() {
        assertEquals(
            PackedSurfacePlane.pack(BaselineGrid.NO_SURFACE, SurfaceKind.VOID.ordinal()),
            PackedSurfacePlane.pack(12345, SurfaceKind.VOID.ordinal()),
            "a void cell's packed form must not depend on the caller's height"
        );
        assertEquals(BaselineGrid.NO_SURFACE,
            PackedSurfacePlane.unpackHeight(PackedSurfacePlane.pack(12345, SurfaceKind.VOID.ordinal())));
    }

    @Test
    void rejectsHeightsOutsideTheTwelveBitRange() {
        assertThrows(IllegalArgumentException.class,
            () -> PackedSurfacePlane.pack(2048, SurfaceKind.LAND.ordinal()));
        assertThrows(IllegalArgumentException.class,
            () -> PackedSurfacePlane.pack(-2049, SurfaceKind.LAND.ordinal()));
        assertThrows(IllegalArgumentException.class,
            () -> PackedSurfacePlane.pack(BaselineGrid.NO_SURFACE, SurfaceKind.LAND.ordinal()),
            "the int no-surface sentinel is not a packable height");
    }

    @Test
    void rejectsKindOrdinalsOutsideTheEnum() {
        assertThrows(IllegalArgumentException.class,
            () -> PackedSurfacePlane.pack(0, SurfaceKind.values().length));
        assertThrows(IllegalArgumentException.class,
            () -> PackedSurfacePlane.pack(0, -1));
    }

    @Test
    void vanillaHeightExtremesRoundTripExactly() {
        assertEquals(-64, PackedSurfacePlane.unpackHeight(PackedSurfacePlane.pack(-64, SurfaceKind.LAND.ordinal())));
        assertEquals(319, PackedSurfacePlane.unpackHeight(PackedSurfacePlane.pack(319, SurfaceKind.LAND.ordinal())));
    }

    @Test
    void planeRoundTripPreservesEveryCellIncludingTheMargin() {
        final int[] surfaceY = new int[PackedSurfacePlane.CELLS];
        final byte[] kind = new byte[PackedSurfacePlane.CELLS];
        final SurfaceKind[] kinds = SurfaceKind.values();
        for (int z = -BaselineGrid.MARGIN; z < BaselineGrid.PIXELS + BaselineGrid.MARGIN; z++) {
            for (int x = -BaselineGrid.MARGIN; x < BaselineGrid.PIXELS + BaselineGrid.MARGIN; x++) {
                final int i = BaselineGrid.index(x, z);
                final SurfaceKind cellKind = kinds[(x + 1) * (BaselineGrid.SIZE + z + 1) % kinds.length];
                kind[i] = (byte) cellKind.ordinal();
                // Void cells carry the derived grid's own no-surface sentinel, so exact equality
                // below also proves the packed form maps them back to it.
                surfaceY[i] = cellKind == SurfaceKind.VOID
                    ? BaselineGrid.NO_SURFACE
                    : ((x + 1) * 31 + (z + 1) * 17) % 4096 - 2048;
            }
        }
        // Distinct values on the outer ring and inner corner prove margin cells are addressed.
        surfaceY[BaselineGrid.index(-1, -1)] = -2048;
        kind[BaselineGrid.index(-1, -1)] = (byte) SurfaceKind.LAND.ordinal();
        surfaceY[BaselineGrid.index(0, 0)] = 62;
        kind[BaselineGrid.index(0, 0)] = (byte) SurfaceKind.WATER.ordinal();
        surfaceY[BaselineGrid.index(255, 255)] = 2047;
        kind[BaselineGrid.index(255, 255)] = (byte) SurfaceKind.SNOW.ordinal();
        surfaceY[BaselineGrid.index(256, 256)] = BaselineGrid.NO_SURFACE;
        kind[BaselineGrid.index(256, 256)] = (byte) SurfaceKind.VOID.ordinal();

        final short[] packed = PackedSurfacePlane.packPlane(surfaceY, kind);
        assertEquals(PackedSurfacePlane.CELLS, packed.length);

        final int[] outSurfaceY = new int[PackedSurfacePlane.CELLS];
        final byte[] outKind = new byte[PackedSurfacePlane.CELLS];
        PackedSurfacePlane.unpackPlane(packed, outSurfaceY, outKind);

        for (int i = 0; i < PackedSurfacePlane.CELLS; i++) {
            assertEquals(kind[i] & 0xFF, PackedSurfacePlane.unpackKind(packed[i]), "kind at cell " + i);
            assertEquals(surfaceY[i], outSurfaceY[i], "surface height at cell " + i);
            assertEquals(kind[i], outKind[i], "kind byte at cell " + i);
        }
    }
}

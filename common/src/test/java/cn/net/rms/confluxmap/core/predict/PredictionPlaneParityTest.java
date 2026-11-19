package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import cn.net.rms.confluxmap.core.color.MapColorStyle;
import cn.net.rms.confluxmap.core.color.XaeroMapStyle;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.CorrectionProfile;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the planar mip cache's parity contract: packing the derived planes and rebuilding the
 * grids from them - the exact {@link PredictionTileService#reconstructGrids} seam the cache-hit
 * path runs - must re-shade bit-identical pixels through the same composer calls the fresh path
 * makes, with and without live corrections.
 */
class PredictionPlaneParityTest {
    private static final int LOD = 0;
    private static final int ORIGIN_X = 3376;
    private static final int ORIGIN_Z = -5248;
    private static final long SEED = 6189700196426903231L;
    private static final PredictionPalette PALETTE = PredictionPalette.defaults();

    @Test
    void planarRoundtripReShadesIdenticalPixelsWithoutCorrections() {
        assertPlanarRoundtripParity(null);
    }

    @Test
    void planarRoundtripReShadesIdenticalPixelsWithCorrections() {
        assertPlanarRoundtripParity(corrections());
    }

    @Test
    void aggregatingFourIdenticalPlanarChildrenReproducesTheChildPlanes() {
        // A uniform child keeps every 2x2 aggregate block uniform, so the majority/average
        // operators are the identity and every plane - margins included, since the aggregate's
        // ring duplication then copies equal values - must survive cell-for-cell.
        final short[] packed = new short[PackedSurfacePlane.CELLS];
        Arrays.fill(packed, PackedSurfacePlane.pack(64, SurfaceKind.WATER.ordinal()));
        final byte[] fluids = new byte[PackedSurfacePlane.CELLS];
        Arrays.fill(fluids, (byte) 7);
        final byte[] biomes = new byte[BaselineGrid.PIXELS * BaselineGrid.PIXELS];
        Arrays.fill(biomes, (byte) 4);
        final byte[] light = new byte[BaselineGrid.PIXELS * BaselineGrid.PIXELS];
        Arrays.fill(light, (byte) 3);
        final PredictionMipCache.Tile child = new PredictionMipCache.Tile(
            LOD, packed, fluids, biomes, light, new byte[PatchCodec.MASK_BYTES], null,
            PredictionViewMode.EVERYWHERE, false, 0L, 0L, CorrectionProfile.LEGACY_V1
        );
        final PredictionMipCache.Tile aggregate = PredictionMipCache.aggregate(
            new PredictionMipCache.Tile[] {child, child, child, child},
            PredictionViewMode.EVERYWHERE, LOD + 1
        );

        assertArrayEquals(child.packedSurfaceKind(), aggregate.packedSurfaceKind(),
            "the packed surface plane must survive the aggregate");
        assertArrayEquals(child.fluidDepth(), aggregate.fluidDepth(),
            "the fluid depth plane must survive the aggregate");
        assertArrayEquals(child.biomes(), aggregate.biomes(),
            "the biome plane must survive the aggregate");
        assertArrayEquals(child.blockLight(), aggregate.blockLight(),
            "the block-light plane must survive the aggregate");
    }

    private static void assertPlanarRoundtripParity(final CorrectionTile corrections) {
        final BaselineGrid grid = baselineGrid();
        final DerivedGrid derived = stylized(grid);
        final PredictionMipCache.Tile tile = packTile(derived, grid);

        final BaselineGrid rebuiltGrid = new BaselineGrid(LOD, ORIGIN_X, ORIGIN_Z);
        final DerivedGrid rebuiltDerived = new DerivedGrid();
        PredictionTileService.reconstructGrids(tile, rebuiltGrid, rebuiltDerived);

        // The rebuilt grids equal the pre-pack originals cell for cell. This is the exact data
        // the service's metadata helpers (biomeIds/surfaceYs) read, so cursor metadata is
        // parity-exact too.
        assertArrayEquals(derived.surfaceY, rebuiltDerived.surfaceY);
        assertArrayEquals(derived.kind, rebuiltDerived.kind);
        assertArrayEquals(derived.fluidDepth, rebuiltDerived.fluidDepth);
        assertArrayEquals(innerBiomes(grid), innerBiomes(rebuiltGrid));

        // The composer argument sets below replicate PredictionTileService#shade for a
        // non-superflat Overworld tile: MAP_COLOR_NONE baseline, absolute-height wash on,
        // neutral ambient tint, CONFLUX style with the Overworld shadow, no quadrant mask.
        assertArrayEquals(
            shadeTerrain(derived, grid, corrections),
            shadeTerrain(rebuiltDerived, rebuiltGrid, corrections),
            "the terrain composer must re-shade a round-tripped plane bit-for-bit"
        );
        assertArrayEquals(
            shadeBiome(derived, grid, corrections),
            shadeBiome(rebuiltDerived, rebuiltGrid, corrections),
            "the biome composer must re-shade a round-tripped plane bit-for-bit"
        );
    }

    private static int[] shadeTerrain(
        final DerivedGrid derived,
        final BaselineGrid grid,
        final CorrectionTile corrections
    ) {
        return PredictedTileComposer.compose(
            derived, grid, PALETTE, corrections, PredictionViewMode.EVERYWHERE, LOD,
            Proto.MAP_COLOR_NONE, derived, grid, Proto.MAP_COLOR_NONE,
            true,
            0xFFFFFFFF,
            new SyncedMaterialPalette(), MapColorStyle.CONFLUX, XaeroMapStyle.Shadow.OVERWORLD,
            null, null
        );
    }

    private static int[] shadeBiome(
        final DerivedGrid derived,
        final BaselineGrid grid,
        final CorrectionTile corrections
    ) {
        return PredictedBiomeComposer.compose(
            derived, grid, corrections, PredictionViewMode.EVERYWHERE, LOD, derived, grid
        );
    }

    /**
     * A synthetic non-supersampled baseline: table-known land biomes on varied heights, a
     * below-sea-level water region (compatibility fluid gives it real depth), and a void corner
     * spanning into the margin, so the packed planes carry every encoding the composer reads.
     */
    private static BaselineGrid baselineGrid() {
        final BaselineGrid grid = new BaselineGrid(LOD, ORIGIN_X, ORIGIN_Z);
        for (int z = -BaselineGrid.MARGIN; z < BaselineGrid.PIXELS + BaselineGrid.MARGIN; z++) {
            for (int x = -BaselineGrid.MARGIN; x < BaselineGrid.PIXELS + BaselineGrid.MARGIN; x++) {
                final int i = BaselineGrid.index(x, z);
                if (x < 24 && z < 24) {
                    grid.biomeId[i] = 1;
                    grid.terrainY[i] = BaselineGrid.NO_SURFACE;
                    continue;
                }
                if (x >= 96 && x < 160 && z >= 128 && z < 192) {
                    grid.biomeId[i] = 0;
                    grid.terrainY[i] = 55;
                    continue;
                }
                grid.biomeId[i] = ((x >> 5) + (z >> 5)) % 2 == 0 ? 1 : 4;
                grid.terrainY[i] = 64 + ((x * 7 + z * 13) & 31);
            }
        }
        return grid;
    }

    private static DerivedGrid stylized(final BaselineGrid grid) {
        final DerivedGrid derived = BaselineDeriver.derive(grid);
        CanopyStylizer.apply(derived, grid, SEED, LOD, ORIGIN_X, ORIGIN_Z);
        return derived;
    }

    /** Compacts the derived planes exactly like the service's cacheable-tile snapshot. */
    private static PredictionMipCache.Tile packTile(final DerivedGrid derived, final BaselineGrid grid) {
        final short[] packedSurfaceKind = PackedSurfacePlane.packPlane(derived.surfaceY, derived.kind);
        final byte[] fluidDepth = new byte[PackedSurfacePlane.CELLS];
        for (int i = 0; i < fluidDepth.length; i++) {
            fluidDepth[i] = (byte) derived.fluidDepth[i];
        }
        final byte[] biomes = new byte[BaselineGrid.PIXELS * BaselineGrid.PIXELS];
        for (int z = 0; z < BaselineGrid.PIXELS; z++) {
            for (int x = 0; x < BaselineGrid.PIXELS; x++) {
                biomes[z * BaselineGrid.PIXELS + x] =
                    (byte) grid.biomeId[BaselineGrid.index(x, z)];
            }
        }
        return new PredictionMipCache.Tile(
            LOD, packedSurfaceKind, fluidDepth, biomes,
            new byte[BaselineGrid.PIXELS * BaselineGrid.PIXELS],
            new byte[PatchCodec.MASK_BYTES], null,
            PredictionViewMode.EVERYWHERE, false, 0L, 0L,
            CorrectionProfile.LEGACY_V1
        );
    }

    private static byte[] innerBiomes(final BaselineGrid grid) {
        final byte[] biomes = new byte[BaselineGrid.PIXELS * BaselineGrid.PIXELS];
        for (int z = 0; z < BaselineGrid.PIXELS; z++) {
            for (int x = 0; x < BaselineGrid.PIXELS; x++) {
                biomes[z * BaselineGrid.PIXELS + x] =
                    (byte) grid.biomeId[BaselineGrid.index(x, z)];
            }
        }
        return biomes;
    }

    /** A few absolute samples, built like TileServiceRealCoverageMaskTest's correction helper. */
    private static CorrectionTile corrections() {
        final CorrectionTile tile = new CorrectionTile(LOD);
        tile.applyPatch(
            1L, new byte[Proto.PATCH_PRESENCE_BYTES],
            new PatchCodec.Patch(List.of(
                new PatchCodec.Sample(0, 4, 90, SurfaceKind.LAND.ordinal(), 12, 0),
                new PatchCodec.Sample(300, 0, 62, SurfaceKind.WATER.ordinal(), 23, 5),
                // The wire's void sentinel is the short-domain NO_SURFACE, not the int one.
                new PatchCodec.Sample(8192, 1, ChunkSnapshot.NO_SURFACE,
                    SurfaceKind.VOID.ordinal(), Proto.MAP_COLOR_NONE, 0)
            ))
        );
        return tile;
    }
}

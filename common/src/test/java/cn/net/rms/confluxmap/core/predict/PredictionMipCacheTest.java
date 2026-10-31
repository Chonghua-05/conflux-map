package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.CorrectionProfile;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PredictionMipCacheTest {
    private static final int PIXELS = BaselineGrid.PIXELS * BaselineGrid.PIXELS;
    private static final int CELLS = PackedSurfacePlane.CELLS;
    /** LOD0 children aggregate into a LOD1 parent: 32 chunk cells per side. */
    private static final int PARENT_LOD = 1;
    private static final int PARENT_CHUNK_CELLS = 32 * 32;

    @Test
    void aggregateUsesTheMostConservativeCorrectionProfile() {
        final PredictionMipCache.Tile[] children = new PredictionMipCache.Tile[] {
            tile(CorrectionProfile.SOURCE_LIGHT_V2),
            tile(CorrectionProfile.SOURCE_LIGHT_V2),
            tile(CorrectionProfile.LEGACY_V1),
            tile(CorrectionProfile.SOURCE_LIGHT_V2)
        };

        final PredictionMipCache.Tile aggregate = PredictionMipCache.aggregate(
            children, PredictionViewMode.EVERYWHERE, PARENT_LOD
        );

        assertEquals(CorrectionProfile.LEGACY_V1, aggregate.correctionProfile());
    }

    @Test
    void dynamicLimitAdjustmentTrimsExcessTiles() {
        final PredictionMipCache cache = new PredictionMipCache(4);
        final cn.net.rms.confluxmap.core.model.DimensionId dim = cn.net.rms.confluxmap.core.model.DimensionId.OVERWORLD;
        final cn.net.rms.confluxmap.core.model.WorldIdentity world = cn.net.rms.confluxmap.core.model.WorldIdentity.singleplayer("test");
        for (int i = 0; i < 4; i++) {
            cache.put(new cn.net.rms.confluxmap.core.model.TileKey(world, dim, "surface!pred", 0, i, 0), tile(CorrectionProfile.SOURCE_LIGHT_V2));
        }
        assertEquals(4, cache.size());
        assertEquals(4, cache.limit());

        cache.setLimit(2);
        assertEquals(2, cache.size());
        assertEquals(2, cache.limit());
    }

    /** A tile built without a server differential must not carry a revision plane. */
    @Test
    void nullRevisionsAreAcceptedByTheTileInvariant() {
        final PredictionMipCache.Tile tile = new PredictionMipCache.Tile(
            0, new short[CELLS], new byte[CELLS], new byte[PIXELS],
            new byte[PIXELS], new byte[PatchCodec.MASK_BYTES], null,
            PredictionViewMode.EVERYWHERE, false, 0L, 0L,
            CorrectionProfile.LEGACY_V1
        );

        assertNull(tile.syncChunkSourceRevisions());
    }

    /** The revision plane is chunk-granular: a per-pixel-sized array must be rejected at LOD0. */
    @Test
    void tileRejectsPerPixelRevisionPlanes() {
        assertThrows(IllegalArgumentException.class, () -> new PredictionMipCache.Tile(
            0, new short[CELLS], new byte[CELLS], new byte[PIXELS],
            new byte[PIXELS], new byte[PatchCodec.MASK_BYTES], new long[PIXELS],
            PredictionViewMode.EVERYWHERE, false, 0L, 0L,
            CorrectionProfile.LEGACY_V1
        ));
    }

    /** The surface plane is margin-inclusive: a per-pixel-sized plane must be rejected. */
    @Test
    void tileRejectsPerPixelSurfacePlanes() {
        assertThrows(IllegalArgumentException.class, () -> new PredictionMipCache.Tile(
            0, new short[PIXELS], new byte[CELLS], new byte[PIXELS],
            new byte[PIXELS], new byte[PatchCodec.MASK_BYTES], null,
            PredictionViewMode.EVERYWHERE, false, 0L, 0L,
            CorrectionProfile.LEGACY_V1
        ));
    }

    /** All four parents null: the aggregate stays null instead of allocating unknown cells. */
    @Test
    void purePredictionAggregateKeepsRevisionsUnallocated() {
        final PredictionMipCache.Tile[] children = new PredictionMipCache.Tile[] {
            pureTile(), pureTile(), pureTile(), pureTile()
        };

        final PredictionMipCache.Tile aggregate = PredictionMipCache.aggregate(
            children, PredictionViewMode.EVERYWHERE, PARENT_LOD
        );

        assertNull(aggregate.syncChunkSourceRevisions());
        for (int byteIndex = 0; byteIndex < PatchCodec.MASK_BYTES; byteIndex++) {
            assertEquals(0, aggregate.syncEvaluated()[byteIndex],
                "pure prediction must not mark any pixel evaluated");
        }
    }

    /** Mixed children: the corrected child's revisions survive, the null siblings are ignored. */
    @Test
    void aggregateCarriesRevisionsFromTheOnlyCorrectedChild() {
        final PredictionMipCache.Tile corrected = correctedTile(0, 0, 17L);
        final PredictionMipCache.Tile aggregate = PredictionMipCache.aggregate(
            new PredictionMipCache.Tile[] {corrected, pureTile(), pureTile(), pureTile()},
            PredictionViewMode.EVERYWHERE, PARENT_LOD
        );

        assertNotNull(aggregate.syncChunkSourceRevisions(),
            "a parent holding an evaluated sync pixel needs a revision plane");
        assertTrue(isEvaluated(aggregate.syncEvaluated(), 0));
        assertEquals(17L, aggregate.syncChunkSourceRevisions()[0]);
        assertEquals(PredictionMipCache.MISSING, aggregate.syncChunkSourceRevisions()[1]);
    }

    /** The parent chunk cell must track the child chunk offset, not collapse onto cell 0. */
    @Test
    void aggregateMapsChildChunkRevisionsToTheMatchingParentCell() {
        final PredictionMipCache.Tile corrected = correctedTile(2, 0, 42L);
        final PredictionMipCache.Tile aggregate = PredictionMipCache.aggregate(
            new PredictionMipCache.Tile[] {corrected, pureTile(), pureTile(), pureTile()},
            PredictionViewMode.EVERYWHERE, PARENT_LOD
        );

        assertEquals(PARENT_CHUNK_CELLS, aggregate.syncChunkSourceRevisions().length);
        assertEquals(42L, aggregate.syncChunkSourceRevisions()[2]);
        assertEquals(PredictionMipCache.MISSING, aggregate.syncChunkSourceRevisions()[0]);
    }

    /**
     * The parent cell's kind is the deterministic majority of the 2x2 child kinds, while the
     * height averages only the non-void samples - and a kind tie keeps the north-west cell.
     */
    @Test
    void aggregateMajorityKindWinsAndHeightAveragesNonVoidSamples() {
        final short land = PackedSurfacePlane.pack(70, SurfaceKind.LAND.ordinal());
        final short water0 = PackedSurfacePlane.pack(72, SurfaceKind.WATER.ordinal());
        final short water1 = PackedSurfacePlane.pack(74, SurfaceKind.WATER.ordinal());
        final short water2 = PackedSurfacePlane.pack(76, SurfaceKind.WATER.ordinal());
        final PredictionMipCache.Tile aggregate = aggregateNorthWestBlock(land, water0, water1, water2);

        assertEquals(SurfaceKind.WATER.ordinal(), PackedSurfacePlane.unpackKind(
            aggregate.packedSurfaceKind()[BaselineGrid.index(0, 0)]),
            "three water samples must outvote the one land sample");
        assertEquals(73, PackedSurfacePlane.unpackHeight(aggregate.packedSurfaceKind()[BaselineGrid.index(0, 0)]),
            "the height must average every non-void sample, whatever its kind");
    }

    @Test
    void aggregateKindTiesKeepTheNorthWestSample() {
        final short land = PackedSurfacePlane.pack(70, SurfaceKind.LAND.ordinal());
        final short water0 = PackedSurfacePlane.pack(80, SurfaceKind.WATER.ordinal());
        final short water1 = PackedSurfacePlane.pack(82, SurfaceKind.WATER.ordinal());
        final short land1 = PackedSurfacePlane.pack(84, SurfaceKind.LAND.ordinal());
        final PredictionMipCache.Tile aggregate = aggregateNorthWestBlock(land, water0, land1, water1);

        assertEquals(SurfaceKind.LAND.ordinal(), PackedSurfacePlane.unpackKind(
            aggregate.packedSurfaceKind()[BaselineGrid.index(0, 0)]),
            "a 2-2 tie must keep the north-west sample's kind");
        assertEquals(79, PackedSurfacePlane.unpackHeight(aggregate.packedSurfaceKind()[BaselineGrid.index(0, 0)]),
            "the height averages every non-void sample regardless of its kind");
    }

    /** A void majority aggregates to "known, no surface", never to an averaged height. */
    @Test
    void voidMajorityStaysVoidThroughAggregation() {
        final short voidCell = PackedSurfacePlane.pack(0, SurfaceKind.VOID.ordinal());
        final short land0 = PackedSurfacePlane.pack(80, SurfaceKind.LAND.ordinal());
        final short land1 = PackedSurfacePlane.pack(82, SurfaceKind.LAND.ordinal());
        final PredictionMipCache.Tile aggregate = aggregateNorthWestBlock(voidCell, voidCell, land0, land1);

        assertEquals(SurfaceKind.VOID.ordinal(), PackedSurfacePlane.unpackKind(
            aggregate.packedSurfaceKind()[BaselineGrid.index(0, 0)]));
        assertEquals(BaselineGrid.NO_SURFACE,
            PackedSurfacePlane.unpackHeight(aggregate.packedSurfaceKind()[BaselineGrid.index(0, 0)]),
            "a void-majority cell must not surface a fabricated average height");
    }

    /** A single void sample in the block must not drag the average of the land samples down. */
    @Test
    void voidMinorityDoesNotDragTheHeightAverage() {
        final short voidCell = PackedSurfacePlane.pack(0, SurfaceKind.VOID.ordinal());
        final short land0 = PackedSurfacePlane.pack(80, SurfaceKind.LAND.ordinal());
        final short land1 = PackedSurfacePlane.pack(82, SurfaceKind.LAND.ordinal());
        final short land2 = PackedSurfacePlane.pack(84, SurfaceKind.LAND.ordinal());
        final PredictionMipCache.Tile aggregate = aggregateNorthWestBlock(voidCell, land0, land1, land2);

        assertEquals(SurfaceKind.LAND.ordinal(), PackedSurfacePlane.unpackKind(
            aggregate.packedSurfaceKind()[BaselineGrid.index(0, 0)]));
        assertEquals(82, PackedSurfacePlane.unpackHeight(aggregate.packedSurfaceKind()[BaselineGrid.index(0, 0)]));
    }

    /**
     * Aggregate tiles have no true margin data; the ring must duplicate the adjacent inner cell
     * of the same parent plane (corners diagonally inward) so edge relief stays deterministic.
     */
    @Test
    void marginRingDuplicatesTheAdjacentInnerCells() {
        final byte[] fluids = new byte[CELLS];
        Arrays.fill(fluids, (byte) 7);
        final PredictionMipCache.Tile[] children = new PredictionMipCache.Tile[4];
        for (int i = 0; i < 4; i++) {
            final short[] packed = new short[CELLS];
            Arrays.fill(packed, PackedSurfacePlane.pack(64 + i, SurfaceKind.LAND.ordinal()));
            children[i] = tile(packed, fluids);
        }
        final PredictionMipCache.Tile aggregate = PredictionMipCache.aggregate(
            children, PredictionViewMode.EVERYWHERE, PARENT_LOD
        );
        final short[] packed = aggregate.packedSurfaceKind();

        assertEquals(packed[BaselineGrid.index(0, 0)], packed[BaselineGrid.index(-1, -1)],
            "the north-west corner copies diagonally inward");
        assertEquals(packed[BaselineGrid.index(BaselineGrid.PIXELS - 1, 0)],
            packed[BaselineGrid.index(BaselineGrid.PIXELS, -1)]);
        assertEquals(packed[BaselineGrid.index(0, BaselineGrid.PIXELS - 1)],
            packed[BaselineGrid.index(-1, BaselineGrid.PIXELS)]);
        assertEquals(packed[BaselineGrid.index(BaselineGrid.PIXELS - 1, BaselineGrid.PIXELS - 1)],
            packed[BaselineGrid.index(BaselineGrid.PIXELS, BaselineGrid.PIXELS)]);
        for (int i = 0; i < BaselineGrid.PIXELS; i++) {
            assertEquals(packed[BaselineGrid.index(0, i)], packed[BaselineGrid.index(-1, i)],
                "the x=-1 ring cell copies x=0 of the same row");
            assertEquals(packed[BaselineGrid.index(BaselineGrid.PIXELS - 1, i)],
                packed[BaselineGrid.index(BaselineGrid.PIXELS, i)]);
        }
        assertEquals(aggregate.fluidDepth()[BaselineGrid.index(-1, 128)],
            aggregate.fluidDepth()[BaselineGrid.index(0, 128)],
            "the fluid plane's ring duplicates the same way");
    }

    @Test
    void blockLightAveragesTheFourChildCells() {
        final byte[] light = new byte[PIXELS];
        light[0] = 0;
        light[1] = 4;
        light[256] = 8;
        light[257] = 12;
        final PredictionMipCache.Tile aggregate = PredictionMipCache.aggregate(
            new PredictionMipCache.Tile[] {tile(light), pureTile(), pureTile(), pureTile()},
            PredictionViewMode.EVERYWHERE, PARENT_LOD
        );

        assertEquals(6, aggregate.blockLight()[0] & 0xFF,
            "the parent cell is the plain average of the 2x2 child block");
    }

    @Test
    void fluidDepthAveragesTheFourChildCells() {
        final byte[] fluids = new byte[CELLS];
        Arrays.fill(fluids, (byte) 10);
        fluids[BaselineGrid.index(1, 0)] = 20;
        fluids[BaselineGrid.index(0, 1)] = 30;
        fluids[BaselineGrid.index(1, 1)] = 40;
        final short[] packed = new short[CELLS];
        Arrays.fill(packed, PackedSurfacePlane.pack(64, SurfaceKind.WATER.ordinal()));
        final PredictionMipCache.Tile aggregate = PredictionMipCache.aggregate(
            new PredictionMipCache.Tile[] {
                tile(packed, fluids), pureTile(), pureTile(), pureTile()},
            PredictionViewMode.EVERYWHERE, PARENT_LOD
        );

        assertEquals(25, aggregate.fluidDepth()[BaselineGrid.index(0, 0)] & 0xFF,
            "the parent cell is the plain average of the 2x2 child block");
    }

    /**
     * Builds four LOD0 children whose north-west block's 2x2 cells are the given packed samples
     * (order: north-west, north-east, south-west, south-east) and aggregates them into a LOD1
     * parent.
     */
    private static PredictionMipCache.Tile aggregateNorthWestBlock(
        final short c0, final short c1, final short c2, final short c3
    ) {
        final short[] packed = new short[CELLS];
        Arrays.fill(packed, PackedSurfacePlane.pack(64, SurfaceKind.LAND.ordinal()));
        packed[BaselineGrid.index(0, 0)] = c0;
        packed[BaselineGrid.index(1, 0)] = c1;
        packed[BaselineGrid.index(0, 1)] = c2;
        packed[BaselineGrid.index(1, 1)] = c3;
        final PredictionMipCache.Tile child = tile(packed, new byte[CELLS]);
        return PredictionMipCache.aggregate(
            new PredictionMipCache.Tile[] {child, tile(new short[CELLS], new byte[CELLS]),
                tile(new short[CELLS], new byte[CELLS]), tile(new short[CELLS], new byte[CELLS])},
            PredictionViewMode.EVERYWHERE, PARENT_LOD
        );
    }

    private static boolean isEvaluated(final byte[] evaluated, final int pixel) {
        return (evaluated[pixel >>> 3] & (1 << (pixel & 7))) != 0;
    }

    private static PredictionMipCache.Tile pureTile() {
        return tile(new short[CELLS], new byte[CELLS]);
    }

    /** LOD0 tile with one fully evaluated 2x2 block inside chunk ({@code chunkX}, {@code chunkZ}). */
    private static PredictionMipCache.Tile correctedTile(
        final int chunkX,
        final int chunkZ,
        final long value
    ) {
        final byte[] evaluated = new byte[PatchCodec.MASK_BYTES];
        final long[] chunkRevisions = new long[16 * 16];
        Arrays.fill(chunkRevisions, PredictionMipCache.MISSING);
        final int pixelX = chunkX * 16;
        final int pixelZ = chunkZ * 16;
        for (final int pixel : new int[] {
            pixelZ * 256 + pixelX, pixelZ * 256 + pixelX + 1,
            (pixelZ + 1) * 256 + pixelX, (pixelZ + 1) * 256 + pixelX + 1
        }) {
            evaluated[pixel >>> 3] |= (byte) (1 << (pixel & 7));
        }
        chunkRevisions[chunkZ * 16 + chunkX] = value;
        return new PredictionMipCache.Tile(
            0, new short[CELLS], new byte[CELLS], new byte[PIXELS],
            new byte[PIXELS], evaluated, chunkRevisions,
            PredictionViewMode.EVERYWHERE, false, 0L, 0L,
            CorrectionProfile.LEGACY_V1
        );
    }

    private static PredictionMipCache.Tile tile(final short[] packedSurfaceKind, final byte[] fluidDepth) {
        return new PredictionMipCache.Tile(
            0, packedSurfaceKind, fluidDepth, new byte[PIXELS],
            new byte[PIXELS], new byte[PatchCodec.MASK_BYTES], null,
            PredictionViewMode.EVERYWHERE, false, 0L, 0L,
            CorrectionProfile.LEGACY_V1
        );
    }

    private static PredictionMipCache.Tile tile(final byte[] blockLight) {
        return new PredictionMipCache.Tile(
            0, new short[CELLS], new byte[CELLS], new byte[PIXELS],
            blockLight, new byte[PatchCodec.MASK_BYTES], null,
            PredictionViewMode.EVERYWHERE, false, 0L, 0L,
            CorrectionProfile.LEGACY_V1
        );
    }

    private static PredictionMipCache.Tile tile(final CorrectionProfile profile) {
        return new PredictionMipCache.Tile(
            0,
            new short[CELLS],
            new byte[CELLS],
            new byte[PIXELS],
            new byte[PIXELS],
            new byte[PatchCodec.MASK_BYTES],
            new long[16 * 16],
            PredictionViewMode.EVERYWHERE,
            false,
            0L,
            0L,
            profile
        );
    }
}

package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.CorrectionProfile;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import org.junit.jupiter.api.Test;

class PredictionMipCacheTest {
    private static final int PIXELS = BaselineGrid.PIXELS * BaselineGrid.PIXELS;

    @Test
    void aggregateUsesTheMostConservativeCorrectionProfile() {
        final PredictionMipCache.Tile[] children = new PredictionMipCache.Tile[] {
            tile(CorrectionProfile.SOURCE_LIGHT_V2),
            tile(CorrectionProfile.SOURCE_LIGHT_V2),
            tile(CorrectionProfile.LEGACY_V1),
            tile(CorrectionProfile.SOURCE_LIGHT_V2)
        };

        final PredictionMipCache.Tile aggregate = PredictionMipCache.aggregate(
            children, PredictionViewMode.EVERYWHERE
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

    /** A tile built without a server differential must not carry the 512 KiB revision plane. */
    @Test
    void nullRevisionsAreAcceptedByTheTileInvariant() {
        final PredictionMipCache.Tile tile = new PredictionMipCache.Tile(
            new int[PIXELS], new byte[PIXELS], new short[PIXELS],
            new byte[PatchCodec.MASK_BYTES], null, new byte[PIXELS],
            1.0F, 0F, PredictionViewMode.EVERYWHERE, false, 0L, 0L,
            CorrectionProfile.LEGACY_V1
        );

        assertNull(tile.syncSourceRevisions());
    }

    /** All four parents null: the aggregate stays null instead of allocating 512 KiB of MIN_VALUE. */
    @Test
    void purePredictionAggregateKeepsRevisionsUnallocated() {
        final PredictionMipCache.Tile[] children = new PredictionMipCache.Tile[] {
            pureTile(), pureTile(), pureTile(), pureTile()
        };

        final PredictionMipCache.Tile aggregate = PredictionMipCache.aggregate(
            children, PredictionViewMode.EVERYWHERE
        );

        assertNull(aggregate.syncSourceRevisions());
        for (int byteIndex = 0; byteIndex < PatchCodec.MASK_BYTES; byteIndex++) {
            assertEquals(0, aggregate.syncEvaluated()[byteIndex],
                "pure prediction must not mark any pixel evaluated");
        }
    }

    /** Mixed children: the corrected child's revisions survive, the null siblings are ignored. */
    @Test
    void aggregateCarriesRevisionsFromTheOnlyCorrectedChild() {
        final PredictionMipCache.Tile corrected = correctedTile(4, 17L);
        final PredictionMipCache.Tile aggregate = PredictionMipCache.aggregate(
            new PredictionMipCache.Tile[] {corrected, pureTile(), pureTile(), pureTile()},
            PredictionViewMode.EVERYWHERE
        );

        assertNotNull(aggregate.syncSourceRevisions(),
            "a parent holding an evaluated sync pixel needs a revision plane");
        assertTrue(isEvaluated(aggregate.syncEvaluated(), 0));
        assertEquals(17L, aggregate.syncSourceRevisions()[0]);
        assertEquals(PredictionMipCache.MISSING, aggregate.syncSourceRevisions()[1]);
    }

    /** Void columns stay \"known, no surface\" through the mip aggregate instead of averaging. */
    @Test
    void voidSurfacesStayVoidThroughAggregation() {
        final PredictionMipCache.Tile voidChild = surfacedTile(BaselineGrid.NO_SURFACE_SHORT);
        final PredictionMipCache.Tile landChild = surfacedTile((short) 80);
        final PredictionMipCache.Tile aggregate = PredictionMipCache.aggregate(
            new PredictionMipCache.Tile[] {voidChild, landChild, landChild, landChild},
            PredictionViewMode.EVERYWHERE
        );

        assertEquals(BaselineGrid.NO_SURFACE_SHORT, aggregate.surfaces()[0],
            "four void samples must aggregate back to the void sentinel, not to Y=0");
        assertEquals((short) 80, aggregate.surfaces()[128]);
    }

    private static boolean isEvaluated(final byte[] evaluated, final int pixel) {
        return (evaluated[pixel >>> 3] & (1 << (pixel & 7))) != 0;
    }

    private static PredictionMipCache.Tile pureTile() {
        return new PredictionMipCache.Tile(
            new int[PIXELS], new byte[PIXELS], new short[PIXELS],
            new byte[PatchCodec.MASK_BYTES], null, new byte[PIXELS],
            1.0F, 0F, PredictionViewMode.EVERYWHERE, false, 0L, 0L,
            CorrectionProfile.LEGACY_V1
        );
    }

    private static PredictionMipCache.Tile correctedTile(final int revision, final long value) {
        final byte[] evaluated = new byte[PatchCodec.MASK_BYTES];
        final long[] revisions = new long[PIXELS];
        for (int pixel : new int[] {0, 1, 256, 257}) {
            evaluated[pixel >>> 3] |= (byte) (1 << (pixel & 7));
            revisions[pixel] = value;
        }
        return new PredictionMipCache.Tile(
            new int[PIXELS], new byte[PIXELS], new short[PIXELS],
            evaluated, revisions, new byte[PIXELS],
            1.0F, 0F, PredictionViewMode.EVERYWHERE, false, 0L, 0L,
            CorrectionProfile.LEGACY_V1
        );
    }

    private static PredictionMipCache.Tile surfacedTile(final short surfaceY) {
        final short[] surfaces = new short[PIXELS];
        java.util.Arrays.fill(surfaces, surfaceY);
        return new PredictionMipCache.Tile(
            new int[PIXELS], new byte[PIXELS], surfaces,
            new byte[PatchCodec.MASK_BYTES], null, new byte[PIXELS],
            1.0F, 0F, PredictionViewMode.EVERYWHERE, false, 0L, 0L,
            CorrectionProfile.LEGACY_V1
        );
    }

    private static PredictionMipCache.Tile tile(final CorrectionProfile profile) {
        final int pixels = BaselineGrid.PIXELS * BaselineGrid.PIXELS;
        return new PredictionMipCache.Tile(
            new int[pixels],
            new byte[pixels],
            new short[pixels],
            new byte[PatchCodec.MASK_BYTES],
            new long[pixels],
            new byte[pixels],
            1.0F,
            0F,
            PredictionViewMode.EVERYWHERE,
            false,
            0L,
            0L,
            profile
        );
    }
}

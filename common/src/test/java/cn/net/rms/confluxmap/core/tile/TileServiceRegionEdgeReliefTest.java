package cn.net.rms.confluxmap.core.tile;

import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.color.MapColorStyle;
import cn.net.rms.confluxmap.core.color.ShadingPipeline;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.store.RegionColumns;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Slope shading must continue to the last column of a region whose relief-shoulder neighbor
 * region is not resident yet. Both style paths used to drop the slope at that edge - the
 * Conflux relief multiplier collapsed to 1.0 on any missing shoulder and the Xaero terrain
 * pass fell back to its depth-only curve - baking a one-texel flat band into the tile that
 * shows up as an intermittent seam on region borders while chunks are still loading.
 */
class TileServiceRegionEdgeReliefTest {
    private static final int SIZE = RegionColumns.SIZE;

    @Test
    void confluxReliefKeepsShadingAtTheEdgeOfAResidentRegion() throws InterruptedException {
        // Uniform east-rising slope anchored at REFERENCE_HEIGHT so the interior reference
        // column carries no absolute-height wash and the guard isolates the relief term.
        final TileUpdate tile = composeLod0(
            MapColorStyle.CONFLUX, (worldX, worldZ) -> ShadingPipeline.REFERENCE_HEIGHT + worldX - 254, 15, 8
        );
        final int interior = green(tile.pixelAt(254, 128));
        final int edge = green(tile.pixelAt(255, 128));
        assertTrue(interior < 120, "slope must darken the terrain, got green " + interior);
        assertTrue(
            Math.abs(edge - interior) <= 4,
            "edge column lost its relief shading: green " + edge + " vs interior " + interior
        );
    }

    @Test
    void xaeroTerrainKeepsShadingAtTheEdgeOfAResidentRegion() throws InterruptedException {
        // Uniform south-rising slope: the Xaero pass reconstructs the normal from the
        // north/north-west shoulders, so z=0 is the row whose shoulders cross into the
        // absent region. The slope term only ever adds light; the defect fell the edge
        // back to the depth-only curve (green 144 here, 187 with slope light).
        final TileUpdate tile = composeLod0(MapColorStyle.XAERO, (worldX, worldZ) -> 64 + worldZ, 8, 0);
        final int interior = green(tile.pixelAt(128, 1));
        final int edge = green(tile.pixelAt(128, 0));
        assertTrue(interior > 160, "slope must light the terrain, got green " + interior);
        assertTrue(
            Math.abs(edge - interior) <= 4,
            "edge column lost its slope shading: green " + edge + " vs interior " + interior
        );
    }

    private static int green(final int argb) {
        return (argb >> 8) & 0xFF;
    }

    /**
     * Stores the single chunk containing both asserted columns (and, for the Xaero path,
     * the interior reference row), then composes that region's LOD-0 tile. No other region
     * is ever stored, so every shoulder reaching outside this region is a missing neighbor.
     */
    private static TileUpdate composeLod0(
        final MapColorStyle style,
        final IntBinaryOperator height,
        final int chunkX,
        final int chunkZ
    ) throws InterruptedException {
        final MapExecutors executors = new MapExecutors();
        try {
            final MapWorldService mapWorlds = new MapWorldService();
            final SessionGuard.Session session = new SessionGuard.Session(
                1L, new WorldIdentity("local", "region-edge-relief"), DimensionId.OVERWORLD
            );
            mapWorlds.switchSession(session);
            mapWorlds.current().put(MapLayer.SURFACE, snapshot(chunkX, chunkZ, height), SampleSource.REAL_LIVE);
            final ConfluxConfig config = new ConfluxConfig();
            config.mapColorStyle = style;
            // Dynamic daylight recolors every channel at noon and would mask the relief
            // contrast this test measures; compose with relief only.
            config.dynamicLighting = false;
            final TileService tiles = new TileService(mapWorlds, executors, config, new DaylightModel());
            final TileKey lod0 = new TileKey(
                session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), 0, 0, 0
            );
            tiles.requestTile(lod0);
            final long deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline) {
                for (final TileUpdate update : tiles.drainUploads(64)) {
                    if (update.key().equals(lod0)) {
                        return update;
                    }
                }
                Thread.sleep(10L);
            }
            throw new AssertionError("no upload arrived for " + lod0);
        } finally {
            executors.shutdown(1000L);
        }
    }

    private static ChunkSnapshot snapshot(final int chunkX, final int chunkZ, final IntBinaryOperator height) {
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        final int[] baseArgb = new int[ChunkSnapshot.COLUMNS];
        final int[] tintArgb = new int[ChunkSnapshot.COLUMNS];
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                final int index = localZ * 16 + localX;
                surfaceY[index] = (short) height.applyAsInt(chunkX * 16 + localX, chunkZ * 16 + localZ);
                baseArgb[index] = 0xFF609040;
                tintArgb[index] = 0xFFFFFFFF;
                kind[index] = (byte) SurfaceKind.LAND.ordinal();
            }
        }
        return new ChunkSnapshot(
            chunkX,
            chunkZ,
            1L,
            surfaceY,
            new String[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS],
            baseArgb,
            tintArgb,
            new int[ChunkSnapshot.COLUMNS],
            kind,
            new byte[ChunkSnapshot.COLUMNS]
        );
    }
}

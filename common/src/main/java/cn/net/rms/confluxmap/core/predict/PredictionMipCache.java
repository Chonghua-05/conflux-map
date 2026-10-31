package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.net.CorrectionProfile;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.util.TileMath;
import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * Bounded CPU mip cache for prediction tiles. A cached tile stores only parameter-independent
 * derived planes - the packed surface kind/height plane, fluid depths, biomes, and the
 * correction planes - never composed pixels: pixels are re-shaded at read time through the exact
 * composer call a fresh composition makes ({@code PredictionTileService#shade}), so
 * map-color-style, daylight, gamma, and view-mode styling changes can never stale the cache.
 */
final class PredictionMipCache {
    static final long MISSING = Long.MIN_VALUE;

    /** Cells per cache-tile side at {@code lod}; every cell covers one correction chunk. */
    static int chunksPerSide(final int lod) {
        return 16 << lod;
    }

    record Tile(
        int lod,
        short[] packedSurfaceKind,       // PackedSurfacePlane.CELLS, margin-inclusive (relief reads neighbours)
        byte[] fluidDepth,               // PackedSurfacePlane.CELLS, margin-inclusive (floorSurface loop reads the full SIZE²)
        byte[] biomes,                   // PIXELS² inner-only (composer reads biomeId at inner indices only)
        byte[] blockLight,               // PIXELS²
        byte[] syncEvaluated,            // PatchCodec.MASK_BYTES
        long[] syncChunkSourceRevisions, // (16<<lod)² or null (unchanged semantics)
        PredictionViewMode mode,
        boolean hasServerState,
        long freshnessValidatedAtMillis,
        long serverCoverageValidatedAtMillis,
        CorrectionProfile correctionProfile
    ) {
        Tile {
            final int pixels = BaselineGrid.PIXELS * BaselineGrid.PIXELS;
            final int expectedChunks = chunksPerSide(lod) * chunksPerSide(lod);
            if (lod < 0 || lod > TileMath.MAX_LOD
                || packedSurfaceKind == null || packedSurfaceKind.length != PackedSurfacePlane.CELLS
                || fluidDepth == null || fluidDepth.length != PackedSurfacePlane.CELLS
                || biomes == null || biomes.length != pixels
                || blockLight == null || blockLight.length != pixels
                || syncEvaluated == null || syncEvaluated.length != PatchCodec.MASK_BYTES
                // null is the "no server revision anywhere in this tile" form: a tile composed
                // without a server differential can never carry an evaluated sync pixel, so the
                // revision plane is not allocated at all. Non-null planes are chunk-granular
                // ((16<<lod)^2 cells) because a chunk's pixels share one source revision; the
                // per-pixel form would cost 512 KiB on every corrected tile.
                || (syncChunkSourceRevisions != null && syncChunkSourceRevisions.length != expectedChunks)
                || mode == null || correctionProfile == null) {
                throw new IllegalArgumentException("invalid prediction mip tile");
            }
        }

        boolean isVisuallyReusableAt(final long nowMillis, final long ttlMillis) {
            return !hasServerState || isFresh(freshnessValidatedAtMillis, nowMillis, ttlMillis);
        }

        private static boolean isFresh(
            final long validatedAtMillis,
            final long nowMillis,
            final long ttlMillis
        ) {
            return validatedAtMillis > 0L
                && nowMillis >= validatedAtMillis
                && nowMillis - validatedAtMillis <= ttlMillis;
        }
    }

    private int limit;
    private final LinkedHashMap<TileKey, Tile> tiles = new LinkedHashMap<>(32, 0.75f, true);

    PredictionMipCache(final int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("prediction mip cache limit must be positive");
        }
        this.limit = limit;
    }

    synchronized void setLimit(final int newLimit) {
        this.limit = Math.max(1, newLimit);
        trim();
    }

    synchronized int limit() {
        return limit;
    }

    synchronized int size() {
        return tiles.size();
    }

    synchronized void clear() {
        tiles.clear();
    }

    synchronized void put(final TileKey key, final Tile tile) {
        tiles.put(key, tile);
        trim();
    }

    synchronized void remove(final TileKey key) {
        tiles.remove(key);
    }

    synchronized void removeCoverage(final TileKey area) {
        tiles.keySet().removeIf(key -> overlaps(area, key));
    }

    synchronized Tile lowerTile(final TileKey parent, final PredictionViewMode mode) {
        if (parent.lod() <= 0) {
            return null;
        }
        final Tile[] children = children(parent, mode);
        return children == null ? null : aggregate(children, mode, parent.lod());
    }

    synchronized Tile exact(final TileKey key, final PredictionViewMode mode) {
        final Tile tile = tiles.get(key);
        return tile != null && tile.mode() == mode ? tile : null;
    }

    synchronized long lowerCoverageValidatedAt(
        final TileKey parent,
        final PredictionViewMode mode
    ) {
        if (parent.lod() <= 0) {
            return MISSING;
        }
        long minimum = Long.MAX_VALUE;
        for (int childZ = 0; childZ < 2; childZ++) {
            for (int childX = 0; childX < 2; childX++) {
                final long childStamp = coverageValidatedAt(childKey(parent, childX, childZ), mode);
                if (childStamp == MISSING) {
                    return MISSING;
                }
                minimum = Math.min(minimum, childStamp);
            }
        }
        return minimum;
    }

    private Tile[] children(final TileKey parent, final PredictionViewMode mode) {
        final Tile[] children = new Tile[4];
        for (int childZ = 0; childZ < 2; childZ++) {
            for (int childX = 0; childX < 2; childX++) {
                final Tile child = cachedOrAggregate(childKey(parent, childX, childZ), mode);
                if (child == null) {
                    return null;
                }
                children[childZ * 2 + childX] = child;
            }
        }
        return children;
    }

    private Tile cachedOrAggregate(final TileKey key, final PredictionViewMode mode) {
        final Tile exact = tiles.get(key);
        if (exact != null && exact.mode() == mode) {
            return exact;
        }
        if (key.lod() <= 0) {
            return null;
        }
        final Tile[] children = children(key, mode);
        if (children == null) {
            return null;
        }
        final Tile aggregate = aggregate(children, mode, key.lod());
        tiles.put(key, aggregate);
        trim();
        return aggregate;
    }

    private long coverageValidatedAt(final TileKey key, final PredictionViewMode mode) {
        final Tile exact = tiles.get(key);
        if (exact != null && exact.mode() == mode) {
            return exact.serverCoverageValidatedAtMillis();
        }
        return lowerCoverageValidatedAt(key, mode);
    }

    private static TileKey childKey(final TileKey parent, final int childX, final int childZ) {
        return new TileKey(
            parent.world(), parent.dimension(), parent.layerId(), parent.lod() - 1,
            parent.tileX() * 2 + childX, parent.tileZ() * 2 + childZ
        );
    }

    private static boolean overlaps(final TileKey first, final TileKey second) {
        if (!first.world().equals(second.world())
            || !first.dimension().equals(second.dimension())
            || !first.layerId().equals(second.layerId())) {
            return false;
        }
        return TileMath.overlaps(
            first.lod(), first.tileX(), first.tileZ(),
            second.lod(), second.tileX(), second.tileZ()
        );
    }

    /**
     * Aggregates four same-mode children into a planar parent tile. Like the pixel average it
     * replaces, the result is an approximation - kind by deterministic majority, heights and the
     * fluid/light planes by averaging - but one parent chunk still covers exactly one child chunk
     * at every LOD, so the revision mapping below is unchanged.
     */
    static Tile aggregate(final Tile[] children, final PredictionViewMode mode, final int parentLod) {
        final int pixels = BaselineGrid.PIXELS * BaselineGrid.PIXELS;
        final short[] packedSurfaceKind = new short[PackedSurfacePlane.CELLS];
        final byte[] fluidDepth = new byte[PackedSurfacePlane.CELLS];
        final byte[] biomes = new byte[pixels];
        final byte[] syncEvaluated = new byte[PatchCodec.MASK_BYTES];
        // Allocated only when at least one child carries revision data, so a parent built purely
        // from client-side predictions keeps the null (unallocated) form.
        long[] syncChunkSourceRevisions = null;
        final byte[] blockLight = new byte[pixels];
        final int parentChunksPerSide = chunksPerSide(parentLod);
        final int parentSamplesPerChunk = BaselineGrid.PIXELS / parentChunksPerSide;
        for (int z = 0; z < BaselineGrid.PIXELS; z++) {
            for (int x = 0; x < BaselineGrid.PIXELS; x++) {
                final int sourceX = x * 2;
                final int sourceZ = z * 2;
                final Tile child = children[(sourceZ >>> 8) * 2 + (sourceX >>> 8)];
                final int localX = sourceX & 255;
                final int localZ = sourceZ & 255;
                final int i0 = localZ * 256 + localX;
                final int i1 = i0 + 1;
                final int i2 = i0 + 256;
                final int i3 = i2 + 1;
                // The margin-inclusive planes index cells by BaselineGrid.index; from the
                // north-west cell the 2x2 block is east/south neighbours on the packed grid.
                final int c0 = BaselineGrid.index(localX, localZ);
                final int c1 = c0 + 1;
                final int c2 = c0 + BaselineGrid.SIZE;
                final int c3 = c2 + 1;
                final int out = z * 256 + x;
                final short[] childKind = child.packedSurfaceKind();
                final int planeCell = BaselineGrid.index(x, z);
                packedSurfaceKind[planeCell] = aggregateSurface(
                    childKind[c0], childKind[c1], childKind[c2], childKind[c3]
                );
                final byte[] childFluid = child.fluidDepth();
                fluidDepth[planeCell] = (byte) (average4(
                    childFluid[c0] & 0xFF, childFluid[c1] & 0xFF,
                    childFluid[c2] & 0xFF, childFluid[c3] & 0xFF
                ));
                final byte[] childBiomes = child.biomes();
                biomes[out] = majorityBiome(
                    childBiomes[i0], childBiomes[i1], childBiomes[i2], childBiomes[i3]
                );
                final boolean allSync = evaluated(child, i0) && evaluated(child, i1)
                    && evaluated(child, i2) && evaluated(child, i3);
                if (allSync) {
                    PatchCodec.setEvaluated(syncEvaluated, out);
                    if (syncChunkSourceRevisions == null) {
                        syncChunkSourceRevisions = new long[parentChunksPerSide * parentChunksPerSide];
                        java.util.Arrays.fill(syncChunkSourceRevisions, Long.MIN_VALUE);
                    }
                    // One parent chunk covers exactly one child chunk at every LOD, so every
                    // pixel of a cell writes the same value and plain assignment is exact.
                    final int cell = (z / parentSamplesPerChunk) * parentChunksPerSide
                        + x / parentSamplesPerChunk;
                    syncChunkSourceRevisions[cell] = maxKnown(
                        revision(child, i0), revision(child, i1),
                        revision(child, i2), revision(child, i3)
                    );
                }
                final byte[] childLight = child.blockLight();
                blockLight[out] = (byte) average4(
                    childLight[i0] & 0xFF, childLight[i1] & 0xFF,
                    childLight[i2] & 0xFF, childLight[i3] & 0xFF
                );
            }
        }
        // Aggregate tiles have no true margin data - each child's margin belongs to a neighbour
        // tile this aggregation never saw - and the ring only feeds directional relief at the
        // tile edge, so duplicating the adjacent inner cell of the same plane is deterministic.
        fillDuplicatedRing(packedSurfaceKind);
        fillDuplicatedRing(fluidDepth);
        boolean hasServerState = false;
        long freshnessValidatedAt = Long.MAX_VALUE;
        long serverCoverageValidatedAt = Long.MAX_VALUE;
        CorrectionProfile correctionProfile = children[0].correctionProfile();
        for (final Tile child : children) {
            if (child.hasServerState()) {
                hasServerState = true;
                freshnessValidatedAt = Math.min(
                    freshnessValidatedAt, child.freshnessValidatedAtMillis()
                );
            }
            serverCoverageValidatedAt = Math.min(
                serverCoverageValidatedAt, child.serverCoverageValidatedAtMillis()
            );
            if (child.correctionProfile().id() < correctionProfile.id()) {
                correctionProfile = child.correctionProfile();
            }
        }
        return new Tile(
            parentLod,
            packedSurfaceKind,
            fluidDepth,
            biomes,
            blockLight,
            syncEvaluated,
            syncChunkSourceRevisions,
            mode,
            hasServerState,
            freshnessValidatedAt == Long.MAX_VALUE ? 0L : freshnessValidatedAt,
            serverCoverageValidatedAt == Long.MAX_VALUE ? 0L : serverCoverageValidatedAt,
            correctionProfile
        );
    }

    /**
     * One parent surface cell from four child cells: the kind by deterministic majority (ties
     * keep the north-west sample, like {@link #majorityBiome}), the height as the floor-division
     * average of the non-void heights. A void-majority cell - or one with no non-void height
     * left - packs as VOID, the packed plane's no-surface form, instead of averaging a
     * fabricated height.
     */
    private static short aggregateSurface(final short p0, final short p1, final short p2, final short p3) {
        final short[] cells = {p0, p1, p2, p3};
        final int voidKind = SurfaceKind.VOID.ordinal();
        int bestKind = PackedSurfacePlane.unpackKind(cells[0]);
        int bestCount = 0;
        for (final short cell : cells) {
            int count = 0;
            final int kind = PackedSurfacePlane.unpackKind(cell);
            for (final short other : cells) {
                if (PackedSurfacePlane.unpackKind(other) == kind) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestKind = kind;
                bestCount = count;
            }
        }
        if (bestKind == voidKind) {
            return PackedSurfacePlane.pack(0, voidKind);
        }
        long sum = 0L;
        int count = 0;
        for (final short cell : cells) {
            if (PackedSurfacePlane.unpackKind(cell) != voidKind) {
                sum += PackedSurfacePlane.unpackHeight(cell);
                count++;
            }
        }
        return count == 0
            ? PackedSurfacePlane.pack(0, voidKind)
            : PackedSurfacePlane.pack((int) Math.floorDiv(sum, count), bestKind);
    }

    /** Fills a planar aggregate's margin ring from the adjacent inner cell of the same plane. */
    private static void fillDuplicatedRing(final short[] plane) {
        final int last = BaselineGrid.PIXELS - 1;
        for (int i = 0; i < BaselineGrid.PIXELS; i++) {
            plane[BaselineGrid.index(i, -1)] = plane[BaselineGrid.index(i, 0)];
            plane[BaselineGrid.index(i, BaselineGrid.PIXELS)] = plane[BaselineGrid.index(i, last)];
            plane[BaselineGrid.index(-1, i)] = plane[BaselineGrid.index(0, i)];
            plane[BaselineGrid.index(BaselineGrid.PIXELS, i)] = plane[BaselineGrid.index(last, i)];
        }
        plane[BaselineGrid.index(-1, -1)] = plane[BaselineGrid.index(0, 0)];
        plane[BaselineGrid.index(BaselineGrid.PIXELS, -1)] = plane[BaselineGrid.index(last, 0)];
        plane[BaselineGrid.index(-1, BaselineGrid.PIXELS)] = plane[BaselineGrid.index(0, last)];
        plane[BaselineGrid.index(BaselineGrid.PIXELS, BaselineGrid.PIXELS)] =
            plane[BaselineGrid.index(last, last)];
    }

    private static void fillDuplicatedRing(final byte[] plane) {
        final int last = BaselineGrid.PIXELS - 1;
        for (int i = 0; i < BaselineGrid.PIXELS; i++) {
            plane[BaselineGrid.index(i, -1)] = plane[BaselineGrid.index(i, 0)];
            plane[BaselineGrid.index(i, BaselineGrid.PIXELS)] = plane[BaselineGrid.index(i, last)];
            plane[BaselineGrid.index(-1, i)] = plane[BaselineGrid.index(0, i)];
            plane[BaselineGrid.index(BaselineGrid.PIXELS, i)] = plane[BaselineGrid.index(last, i)];
        }
        plane[BaselineGrid.index(-1, -1)] = plane[BaselineGrid.index(0, 0)];
        plane[BaselineGrid.index(BaselineGrid.PIXELS, -1)] = plane[BaselineGrid.index(last, 0)];
        plane[BaselineGrid.index(-1, BaselineGrid.PIXELS)] = plane[BaselineGrid.index(0, last)];
        plane[BaselineGrid.index(BaselineGrid.PIXELS, BaselineGrid.PIXELS)] =
            plane[BaselineGrid.index(last, last)];
    }

    private static int average4(final int a, final int b, final int c, final int d) {
        return (a + b + c + d) / 4;
    }

    private static boolean evaluated(final Tile tile, final int pixel) {
        return (tile.syncEvaluated()[pixel >>> 3] & (1 << (pixel & 7))) != 0;
    }

    /** Null-tolerant chunk-cell read for one pixel: an unallocated plane means "unknown". */
    private static long revision(final Tile tile, final int pixel) {
        final long[] revisions = tile.syncChunkSourceRevisions();
        if (revisions == null) {
            return Long.MIN_VALUE;
        }
        final int chunks = chunksPerSide(tile.lod());
        final int samplesPerChunk = BaselineGrid.PIXELS / chunks;
        return revisions[(pixel >>> 8) / samplesPerChunk * chunks + (pixel & 255) / samplesPerChunk];
    }

    private static long maxKnown(final long a, final long b, final long c, final long d) {
        long result = Long.MIN_VALUE;
        for (final long value : new long[] {a, b, c, d}) {
            if (value != Long.MIN_VALUE && (result == Long.MIN_VALUE || value > result)) {
                result = value;
            }
        }
        return result;
    }

    /** Deterministic four-value mode; ties retain the north-west sample. */
    private static byte majorityBiome(final byte b0, final byte b1, final byte b2, final byte b3) {
        final byte[] values = {b0, b1, b2, b3};
        byte best = b0;
        int bestCount = 0;
        for (int i = 0; i < values.length; i++) {
            int count = 0;
            for (final byte value : values) {
                if (value == values[i]) {
                    count++;
                }
            }
            if (count > bestCount) {
                best = values[i];
                bestCount = count;
            }
        }
        return best;
    }

    private void trim() {
        final Iterator<TileKey> keys = tiles.keySet().iterator();
        while (tiles.size() > limit && keys.hasNext()) {
            keys.next();
            keys.remove();
        }
    }
}

package cn.net.rms.confluxmap.server;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decides when the live summary overlay of an unloaded chunk may be dropped.
 *
 * <p>An unloaded chunk that will be written keeps its overlay until the region file's mtime
 * moves, which proves the on-disk copy caught up. The mtime signal alone is not sufficient as a
 * lifetime bound: a chunk that unloads clean is never written, so its mtime never changes and
 * the entry would be retained forever. Every entry therefore also expires after a fixed tick
 * deadline, which bounds both the entry set and the live summaries it pins.</p>
 *
 * <p>Entries are grouped by region file (32x32 chunks) so a scan issues one mtime probe per
 * region, scans run on a fixed interval rather than every tick, and each scan covers a bounded
 * number of regions, rotating retained regions to the tail so none is starved.</p>
 */
public final class PendingLiveRemovals {
    /** Supplies the current mtime in milliseconds of one region file; 0 when it is absent. */
    @FunctionalInterface
    public interface McaMtimeSource {
        long mtimeMs(int worldIndex, int mcaX, int mcaZ);
    }

    /** Receives chunks whose live summary overlay may be dropped now. */
    @FunctionalInterface
    public interface RemovalSink {
        void removeLive(int worldIndex, int chunkX, int chunkZ);
    }

    private static final int CHUNKS_PER_REGION_SIDE = 32;

    private record RegionKey(int worldIndex, int mcaX, int mcaZ) {
    }

    private record ChunkEntry(long observedMtimeMs, long addedTick, boolean awaitWrite) {
    }

    private final McaMtimeSource mtimeSource;
    private final int scanIntervalTicks;
    private final int expiryTicks;
    private final int maxRegionsPerScan;
    private final Map<RegionKey, Map<Long, ChunkEntry>> pendingRegions = new LinkedHashMap<>();

    public PendingLiveRemovals(
        final McaMtimeSource mtimeSource,
        final int scanIntervalTicks,
        final int expiryTicks,
        final int maxRegionsPerScan
    ) {
        if (scanIntervalTicks < 1 || expiryTicks < scanIntervalTicks || maxRegionsPerScan < 1) {
            throw new IllegalArgumentException("invalid retention parameters");
        }
        this.mtimeSource = mtimeSource;
        this.scanIntervalTicks = scanIntervalTicks;
        this.expiryTicks = expiryTicks;
        this.maxRegionsPerScan = maxRegionsPerScan;
    }

    /** Records the unload of {@code chunkX},{@code chunkZ} with the region mtime seen at unload. */
    public void onUnload(
        final int worldIndex,
        final int chunkX,
        final int chunkZ,
        final long observedMtimeMs,
        final boolean awaitWrite,
        final long tick
    ) {
        pendingRegions
            .computeIfAbsent(
                new RegionKey(
                    worldIndex,
                    Math.floorDiv(chunkX, CHUNKS_PER_REGION_SIDE),
                    Math.floorDiv(chunkZ, CHUNKS_PER_REGION_SIDE)
                ),
                ignored -> new LinkedHashMap<>()
            )
            .put(chunkKey(chunkX, chunkZ), new ChunkEntry(observedMtimeMs, tick, awaitWrite));
    }

    /** Drops the pending entry of a chunk that loaded again; its overlay goes back to live use. */
    public void onChunkReload(final int worldIndex, final int chunkX, final int chunkZ) {
        final RegionKey key = new RegionKey(
            worldIndex,
            Math.floorDiv(chunkX, CHUNKS_PER_REGION_SIDE),
            Math.floorDiv(chunkZ, CHUNKS_PER_REGION_SIDE)
        );
        final Map<Long, ChunkEntry> chunks = pendingRegions.get(key);
        if (chunks == null) {
            return;
        }
        chunks.remove(chunkKey(chunkX, chunkZ));
        if (chunks.isEmpty()) {
            pendingRegions.remove(key);
        }
    }

    public boolean isEmpty() {
        return pendingRegions.isEmpty();
    }

    public void clear() {
        pendingRegions.clear();
    }

    /** Runs the expiry scan, but only on ticks that are multiples of the scan interval. */
    public void tick(final long tick, final RemovalSink sink) {
        if (pendingRegions.isEmpty() || tick % scanIntervalTicks != 0) {
            return;
        }
        // Survivors are collected and reinserted after the loop: putting a region back while
        // the iterator is live is a structural modification and throws
        // ConcurrentModificationException on the next step.
        final Map<RegionKey, Map<Long, ChunkEntry>> retained = new LinkedHashMap<>();
        final Iterator<Map.Entry<RegionKey, Map<Long, ChunkEntry>>> iterator =
            pendingRegions.entrySet().iterator();
        int scanned = 0;
        while (iterator.hasNext() && scanned < maxRegionsPerScan) {
            final Map.Entry<RegionKey, Map<Long, ChunkEntry>> entry = iterator.next();
            iterator.remove();
            final RegionKey key = entry.getKey();
            final Map<Long, ChunkEntry> chunks = entry.getValue();
            final long mtime = mtimeSource.mtimeMs(key.worldIndex(), key.mcaX(), key.mcaZ());
            final Iterator<Map.Entry<Long, ChunkEntry>> chunkIterator = chunks.entrySet().iterator();
            while (chunkIterator.hasNext()) {
                final Map.Entry<Long, ChunkEntry> chunk = chunkIterator.next();
                final ChunkEntry pending = chunk.getValue();
                if (expired(tick, pending, mtime)) {
                    sink.removeLive(key.worldIndex(), chunkX(chunk.getKey()), chunkZ(chunk.getKey()));
                    chunkIterator.remove();
                }
            }
            scanned++;
            if (!chunks.isEmpty()) {
                // Reinsertion at the tail keeps scans fair when regions exceed the per-scan cap.
                retained.put(key, chunks);
            }
        }
        pendingRegions.putAll(retained);
    }

    private boolean expired(final long tick, final ChunkEntry pending, final long currentMtime) {
        if (!pending.awaitWrite()) {
            return true;
        }
        if (tick - pending.addedTick() >= expiryTicks) {
            return true;
        }
        return currentMtime > 0L && currentMtime != pending.observedMtimeMs();
    }

    private static long chunkKey(final int chunkX, final int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static int chunkX(final long chunkKey) {
        return (int) (chunkKey >> 32);
    }

    private static int chunkZ(final long chunkKey) {
        return (int) chunkKey;
    }
}

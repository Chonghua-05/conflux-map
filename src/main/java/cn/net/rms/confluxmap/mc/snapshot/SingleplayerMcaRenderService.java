package cn.net.rms.confluxmap.mc.snapshot;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.cache.RegionCacheService;
import cn.net.rms.confluxmap.core.cache.RegionDiskCache;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.store.ColumnStore;
import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.util.ChunkViewport;
import cn.net.rms.confluxmap.mc.color.McaTintResolver;
import cn.net.rms.confluxmap.mc.color.SpriteColorSampler;
import cn.net.rms.confluxmap.server.AnvilMcaScanner;
import cn.net.rms.confluxmap.server.NbtChunkColumnSource;
import cn.net.rms.confluxmap.server.RegionStoragePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.WorldSavePath;

/**
 * Singleplayer-only dynamic import of the save's Anvil region files into map tiles.
 *
 * <p>Chunky-style pregenerators write large explored ranges that the regular capture
 * (player-loaded chunks) and the seed prediction can never render. This service converts
 * those {@code .mca} files through the same surface scan as live capture
 * ({@link McChunkSnapshotFactory#snapshotSurfaceFromColumn}), writing
 * {@link SampleSource#IMPORTED} snapshots into {@link MapWorld}: captured chunks always win,
 * and the existing tile pipeline renders the result with zero extra rendering machinery.
 *
 * <p>Work is viewport-driven, never a whole-save sweep: with the map closed the conversion
 * follows the player within {@link ConfluxConfig#singleplayerMcaRadius}; while the fullscreen
 * map is open it follows the view center via {@link #reportMapViewport}. Files are queued by
 * distance, scanned on a small IO pool, and tiles refresh progressively per summary region.
 * A per-session mtime index skips files already converted; converted tiles persist through
 * the regular region disk cache.
 *
 * <p>Deliberately independent of the network sync stack: no companion activation, no
 * HELLO/patch traffic, and the {@code predictionNetworkSync} setting is irrelevant here.
 * v1 renders the overworld surface layer only; nether/end layers have their own semantics
 * (roof scans, cave pivots) that the surface scan does not express.
 */
public final class SingleplayerMcaRenderService {
    private static final int SCAN_THREADS = 2;
    private static final int CHUNKS_PER_MCA = 32;
    /** Viewport-follow margin beyond the visible map chunks, so panning reveals finished terrain. */
    private static final int VIEWPORT_MARGIN_CHUNKS = 4;
    /** Player-follow re-check cadence; catches files Chunky writes while the player idles. */
    private static final long REPLAN_INTERVAL_MS = 5_000L;
    /** How long a map-open viewport stays authoritative after the last {@link #reportMapViewport}. */
    private static final long MAP_VIEWPORT_TTL_MS = 2_000L;
    /** Plan-center hysteresis: panning by less than this does not restart the plan. */
    private static final int REPLAN_CENTER_HYSTERESIS = 2;
    /** INFO progress-log cadence, in converted files. */
    private static final int LOG_FILE_INTERVAL = 25;

    private final MinecraftClient client;
    private final ConfluxConfig config;
    private final SessionGuard sessionGuard;
    private final MapWorldService mapWorlds;
    private final TileService tiles;
    private final RegionCacheService regionCache;
    private final McChunkSnapshotFactory factory;
    /** Shared with live capture so per-state model sampling stays warm across both paths. */
    private final SpriteColorSampler sampler;
    private final McaTintResolver tints;
    private final AnvilMcaScanner scanner = new AnvilMcaScanner();
    private final ExecutorService executor;

    private final Object queueLock = new Object();
    /** Nearest-last ordering: {@link #drain} pops from the tail. */
    private List<PendingFile> pending = List.of();
    /** Session-scoped mtime index: MCA files already converted this session. */
    private final Map<Long, Long> scannedMcas = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    /** Session-cumulative progress counters, surfaced in throttled INFO logs. */
    private final AtomicInteger filesDone = new AtomicInteger();
    private final AtomicInteger chunksImported = new AtomicInteger();
    private final AtomicInteger chunksRealSkip = new AtomicInteger();
    private final AtomicInteger chunksNotGenerated = new AtomicInteger();
    private final AtomicInteger chunksFailed = new AtomicInteger();
    private final AtomicInteger filesFailed = new AtomicInteger();

    private volatile Path regionDirectory;
    private ChunkViewport mapViewport;
    private long lastMapViewportAtMs;
    private long lastPlanAtMs;
    private int lastPlanCenterX = Integer.MIN_VALUE;
    private int lastPlanCenterZ = Integer.MIN_VALUE;
    private int lastPlanRadius = -1;

    public SingleplayerMcaRenderService(
        final MinecraftClient client,
        final ConfluxConfig config,
        final SessionGuard sessionGuard,
        final MapWorldService mapWorlds,
        final TileService tiles,
        final RegionCacheService regionCache,
        final McChunkSnapshotFactory factory,
        final SpriteColorSampler sampler
    ) {
        this.client = client;
        this.config = config;
        this.sessionGuard = sessionGuard;
        this.mapWorlds = mapWorlds;
        this.tiles = tiles;
        this.regionCache = regionCache;
        this.factory = factory;
        this.sampler = sampler;
        this.tints = new McaTintResolver(client);
        this.executor = Executors.newFixedThreadPool(SCAN_THREADS, namedDaemons());
    }

    /** Client-tick driver: picks the follow mode (map viewport vs player) and refreshes the plan. */
    public void tick() {
        if (!config.singleplayerMcaRender) {
            return;
        }
        final SessionGuard.Session session = sessionGuard.current();
        if (!session.active() || !client.isInSingleplayer() || client.getServer() == null) {
            return;
        }
        if (!DimensionId.OVERWORLD.equals(session.dimension()) || client.player == null) {
            return;
        }
        if (regionDirectory == null) {
            regionDirectory = RegionStoragePaths.regionDirectory(
                client.getServer().getSavePath(WorldSavePath.ROOT),
                session.dimension().namespace() + ":" + session.dimension().path()
            );
        }

        final long now = System.currentTimeMillis();
        final int playerChunkX = client.player.getBlockPos().getX() >> 4;
        final int playerChunkZ = client.player.getBlockPos().getZ() >> 4;
        final int centerX;
        final int centerZ;
        final int radius;
        if (mapViewport != null && now - lastMapViewportAtMs <= MAP_VIEWPORT_TTL_MS) {
            centerX = Math.floorDiv(mapViewport.minChunkX() + mapViewport.maxChunkX(), 2);
            centerZ = Math.floorDiv(mapViewport.minChunkZ() + mapViewport.maxChunkZ(), 2);
            radius = Math.max(
                    Math.max(mapViewport.maxChunkX() - mapViewport.minChunkX(),
                        mapViewport.maxChunkZ() - mapViewport.minChunkZ()) / 2,
                    0
                ) + VIEWPORT_MARGIN_CHUNKS;
        } else {
            centerX = playerChunkX;
            centerZ = playerChunkZ;
            radius = config.singleplayerMcaRadius;
        }
        final boolean centerMoved = Math.abs(centerX - lastPlanCenterX) >= REPLAN_CENTER_HYSTERESIS
            || Math.abs(centerZ - lastPlanCenterZ) >= REPLAN_CENTER_HYSTERESIS;
        final boolean radiusChanged = radius != lastPlanRadius;
        if (!centerMoved && !radiusChanged && now - lastPlanAtMs < REPLAN_INTERVAL_MS) {
            return;
        }
        replan(session, centerX, centerZ, radius, now);
    }

    /**
     * Fullscreen-map driver: the conversion follows the open map's visible chunk rectangle.
     * Views of other sessions (browse mode) are ignored, so another save's map rectangle can
     * never import terrain into the live world.
     */
    public void reportMapViewport(final SessionGuard.Session session, final ChunkViewport view) {
        if (!sessionGuard.isCurrent(session.token())) {
            return;
        }
        mapViewport = view;
        lastMapViewportAtMs = System.currentTimeMillis();
    }

    public void onSessionChanged(final SessionGuard.Session session) {
        if (filesDone.get() > 0) {
            ConfluxMapMod.LOGGER.info(
                "[confluxmap] MCA render session summary: {} files done ({} chunks imported, "
                    + "{} real-skip, {} not-generated stubs, {} chunk failures, {} file failures)",
                filesDone.get(), chunksImported.get(), chunksRealSkip.get(),
                chunksNotGenerated.get(), chunksFailed.get(), filesFailed.get());
        }
        synchronized (queueLock) {
            pending = List.of();
        }
        scannedMcas.clear();
        regionDirectory = null;
        mapViewport = null;
        lastMapViewportAtMs = 0L;
        lastPlanCenterX = Integer.MIN_VALUE;
        lastPlanCenterZ = Integer.MIN_VALUE;
        lastPlanRadius = -1;
    }

    public void close() {
        executor.shutdownNow();
    }

    private void replan(
        final SessionGuard.Session session,
        final int centerX,
        final int centerZ,
        final int radius,
        final long now
    ) {
        lastPlanAtMs = now;
        lastPlanCenterX = centerX;
        lastPlanCenterZ = centerZ;
        lastPlanRadius = radius;
        final Path directory = regionDirectory;
        if (directory == null) {
            return;
        }
        final List<PendingFile> next = new ArrayList<>();
        final int minMcaX = Math.floorDiv(centerX - radius, CHUNKS_PER_MCA);
        final int maxMcaX = Math.floorDiv(centerX + radius, CHUNKS_PER_MCA);
        final int minMcaZ = Math.floorDiv(centerZ - radius, CHUNKS_PER_MCA);
        final int maxMcaZ = Math.floorDiv(centerZ + radius, CHUNKS_PER_MCA);
        for (int mcaX = minMcaX; mcaX <= maxMcaX; mcaX++) {
            for (int mcaZ = minMcaZ; mcaZ <= maxMcaZ; mcaZ++) {
                final long key = pack(mcaX, mcaZ);
                if (scannedMcas.containsKey(key)) {
                    continue;
                }
                final Path path = directory.resolve("r." + mcaX + "." + mcaZ + ".mca");
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                next.add(new PendingFile(mcaX, mcaZ, path, chebyshevToMca(mcaX, mcaZ, centerX, centerZ)));
            }
        }
        next.sort(Comparator.comparingInt(PendingFile::distance).reversed());
        synchronized (queueLock) {
            pending = next;
        }
        if (!next.isEmpty() && filesDone.get() % LOG_FILE_INTERVAL == 0) {
            ConfluxMapMod.LOGGER.info(
                "[confluxmap] MCA render: queued {} region files (center {},{} radius {} chunks; "
                    + "{} files done, {} chunks imported, {} not-generated stubs skipped so far)",
                next.size(), centerX, centerZ, radius,
                filesDone.get(), chunksImported.get(), chunksNotGenerated.get());
        }
        drain(session);
    }

    /** Inclusive {@code r.X.Z.mca} coordinate square covering {@code [center-radius, center+radius]} chunks. */
    static McaRange mcaRange(final int centerChunkX, final int centerChunkZ, final int radiusChunks) {
        return new McaRange(
            Math.floorDiv(centerChunkX - radiusChunks, CHUNKS_PER_MCA),
            Math.floorDiv(centerChunkX + radiusChunks, CHUNKS_PER_MCA),
            Math.floorDiv(centerChunkZ - radiusChunks, CHUNKS_PER_MCA),
            Math.floorDiv(centerChunkZ + radiusChunks, CHUNKS_PER_MCA)
        );
    }

    /** Inclusive MCA-file coordinate square behind one plan; floorDiv keeps negative worlds honest. */
    record McaRange(int minMcaX, int maxMcaX, int minMcaZ, int maxMcaZ) {
        int fileCount() {
            return (maxMcaX - minMcaX + 1) * (maxMcaZ - minMcaZ + 1);
        }
    }

    private void drain(final SessionGuard.Session session) {
        while (inFlight.get() < SCAN_THREADS) {
            final PendingFile next;
            synchronized (queueLock) {
                if (pending.isEmpty()) {
                    return;
                }
                next = pending.remove(pending.size() - 1);
            }
            inFlight.incrementAndGet();
            executor.execute(() -> {
                try {
                    scanFile(next, session);
                } finally {
                    inFlight.decrementAndGet();
                    drain(sessionGuard.current());
                }
            });
        }
    }

    private void scanFile(final PendingFile task, final SessionGuard.Session session) {
        final long token = session.token();
        try {
            final long mtime = Files.getLastModifiedTime(task.path).toMillis();
            final Long known = scannedMcas.get(task.key());
            if (known != null && known == mtime) {
                return;
            }
            final MapWorld world = mapWorlds.ifCurrent(token);
            if (world == null) {
                return;
            }
            final ClientWorld clientWorld = client.world;
            if (clientWorld == null) {
                return;
            }
            final ColumnStore store = world.store(MapLayer.SURFACE);
            final Map<Long, List<ChunkSnapshot>> byRegion = new LinkedHashMap<>();
            final int[] imported = {0};
            final int[] exploredSkip = {0};
            final int[] notGenerated = {0};
            scanner.forEachChunkNbt(task.path, task.mcaX(), task.mcaZ(), (chunkX, chunkZ, nbt) -> {
                try {
                    final NbtChunkColumnSource source = new NbtChunkColumnSource(nbt);
                    // Not-fully-generated chunks stay unknown so the tile shows the map
                    // background (or the prediction underlay) instead of a gray placeholder -
                    // this is the pregenerator border-ring guard. Chunky rim files are full of
                    // structure_starts stubs that carry no terrain at all.
                    if (!source.generated()) {
                        notGenerated[0]++;
                        chunksNotGenerated.incrementAndGet();
                        return;
                    }
                    // Captured data is strictly better than the file's snapshot; the store gate
                    // would reject it anyway, this just skips the sampling work. Imported chunks
                    // deliberately fall through: they must be re-imported so conversion fixes
                    // reach data cached by an older converter - stale tintless or gray rows from
                    // previous versions would otherwise outlive the fix.
                    if (store.chunkSource(chunkX, chunkZ).priority()
                        >= SampleSource.REAL_CACHED.priority()) {
                        exploredSkip[0]++;
                        chunksRealSkip.incrementAndGet();
                        return;
                    }
                    final ChunkSnapshot snapshot = factory.snapshotSurfaceFromColumn(
                        chunkX, chunkZ,
                        new McaSurfaceColumn(chunkX, chunkZ, source, client, clientWorld, tints, sampler),
                        source.revision(), token
                    );
                    if (snapshot == null) {
                        return;
                    }
                    if (world.put(MapLayer.SURFACE, snapshot, SampleSource.IMPORTED)) {
                        imported[0]++;
                        chunksImported.incrementAndGet();
                        byRegion.computeIfAbsent(pack(chunkX >> 4, chunkZ >> 4), ignored -> new ArrayList<>())
                            .add(snapshot);
                    }
                } catch (final RuntimeException e) {
                    // One broken chunk must not abort the file; log and keep scanning.
                    chunksFailed.incrementAndGet();
                    ConfluxMapMod.LOGGER.debug(
                        "MCA import failed for chunk {},{} in {}", chunkX, chunkZ, task.path, e);
                }
            });
            filesDone.incrementAndGet();
            ConfluxMapMod.LOGGER.debug(
                "MCA import r.{}.{}.mca: {} chunks imported, {} real-skip, {} not-generated",
                task.mcaX(), task.mcaZ(), imported[0], exploredSkip[0], notGenerated[0]);
            if (filesDone.get() % LOG_FILE_INTERVAL == 0) {
                ConfluxMapMod.LOGGER.info(
                    "[confluxmap] MCA render progress: {} files done ({} chunks imported, "
                        + "{} real-skip, {} not-generated stubs, {} chunk failures), "
                        + "{} files queued",
                    filesDone.get(), chunksImported.get(), chunksRealSkip.get(),
                    chunksNotGenerated.get(), chunksFailed.get(), pendingSize());
            }
            for (final Map.Entry<Long, List<ChunkSnapshot>> entry : byRegion.entrySet()) {
                final List<TileService.RegionUnit> units = new ArrayList<>(entry.getValue().size());
                for (final ChunkSnapshot snapshot : entry.getValue()) {
                    units.add(new TileService.RegionUnit(snapshot.chunkX, snapshot.chunkZ));
                }
                tiles.markChunksStored(token, session.dimension(), MapLayer.SURFACE, units);
                final RegionDiskCache disk = regionCache.current();
                if (disk != null) {
                    disk.flushRegionIfDirty(
                        MapLayer.Type.SURFACE,
                        (int) (entry.getKey() >> 32),
                        (int) (entry.getKey() & 0xFFFFFFFFL)
                    );
                }
            }
            scannedMcas.put(task.key(), mtime);
        } catch (final IOException | RuntimeException e) {
            filesFailed.incrementAndGet();
            ConfluxMapMod.LOGGER.warn(
                "[confluxmap] MCA render: file task failed for mca {},{} ({})",
                task.mcaX(), task.mcaZ(), task.path, e);
        }
    }

    private int pendingSize() {
        synchronized (queueLock) {
            return pending.size();
        }
    }

    private static int chebyshevToMca(final int mcaX, final int mcaZ, final int centerChunkX, final int centerChunkZ) {
        final int centerChunkOfMcaX = mcaX * CHUNKS_PER_MCA + CHUNKS_PER_MCA / 2;
        final int centerChunkOfMcaZ = mcaZ * CHUNKS_PER_MCA + CHUNKS_PER_MCA / 2;
        return Math.max(Math.abs(centerChunkOfMcaX - centerChunkX), Math.abs(centerChunkOfMcaZ - centerChunkZ));
    }

    private static long pack(final int x, final int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static ThreadFactory namedDaemons() {
        return runnable -> {
            final Thread thread = new Thread(runnable, "ConfluxMap-McaImport");
            thread.setDaemon(true);
            return thread;
        };
    }

    /** One queued {@code .mca} file with its distance ranking from the current plan center. */
    private record PendingFile(int mcaX, int mcaZ, Path path, int distance) {
        long key() {
            return pack(mcaX, mcaZ);
        }
    }
}

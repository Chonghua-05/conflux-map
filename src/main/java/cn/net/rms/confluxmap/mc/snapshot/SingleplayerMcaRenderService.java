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
import java.util.concurrent.atomic.AtomicBoolean;
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
    /**
     * Import startup delay after every session (re)activation. World join floods the IO and
     * worker pools with vanilla chunk loading and first-tile composition; importing region
     * files on top of that made joins crawl. The import always starts from a dead stop.
     */
    private static final long IMPORT_START_DELAY_MS = 15_000L;
    /**
     * Bumped whenever conversion semantics change. Persisted with the per-file index so a
     * converter fix re-imports exactly once (the stale rows are overwritten), while ordinary
     * sessions skip already-converted files instead of paying the full rescan every time.
     */
    private static final int CONVERTER_VERSION = 2;
    /** Persisted-index write cadence; the file is tiny, so trailing writes are cheap. */
    private static final long INDEX_WRITE_DEBOUNCE_MS = 2_000L;
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

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
    /**
     * MCA file name to last-seen mtime, for files the current converter version has already
     * imported. Persisted beside the save so sessions after a full import skip the rescan;
     * a converter version bump invalidates every entry at once.
     */
    private final Map<String, Long> importedIndex = new ConcurrentHashMap<>();
    private volatile Path importIndexPath;
    private volatile long lastIndexWriteAtMs;
    private final AtomicBoolean replanInFlight = new AtomicBoolean();
    private volatile long sessionActivatedAtMs = Long.MIN_VALUE;
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
        final long now = System.currentTimeMillis();
        if (now - sessionActivatedAtMs < IMPORT_START_DELAY_MS) {
            return;
        }
        if (regionDirectory == null) {
            ensureSessionPaths(session);
        }


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
        // The plan stats up to a thousand region files; never do that on the render thread.
        lastPlanAtMs = now;
        lastPlanCenterX = centerX;
        lastPlanCenterZ = centerZ;
        lastPlanRadius = radius;
        if (replanInFlight.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    replan(session, centerX, centerZ, radius);
                } finally {
                    replanInFlight.set(false);
                }
            });
        }
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
        // Every activation (join, dimension switch) restarts the import delay, so the world
        // gets its IO and worker pools back before any region conversion runs.
        sessionActivatedAtMs = System.currentTimeMillis();
        synchronized (queueLock) {
            pending = List.of();
        }
        persistIndex();
        regionDirectory = null;
        importIndexPath = null;
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
        final int radius
    ) {
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
                final String fileName = "r." + mcaX + "." + mcaZ + ".mca";
                final Path path = directory.resolve(fileName);
                final long mtime = mtimeOf(path);
                if (mtime <= 0L) {
                    continue;
                }
                final Long importedAt = importedIndex.get(fileName);
                if (importedAt != null && importedAt == mtime) {
                    continue;
                }
                next.add(new PendingFile(mcaX, mcaZ, path, mtime,
                    chebyshevToMca(mcaX, mcaZ, centerX, centerZ)));
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
            final Long known = importedIndex.get(task.path().getFileName().toString());
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
            /** First resolved biome id among this file's imports; proves tint resolution in the log. */
            final String[] biomeSample = {null};
            /** First non-white tint among this file's imports; 0 when every column stayed untinted. */
            final int[] tintSample = {0};
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
                        if (biomeSample[0] == null) {
                            for (final String biomeId : snapshot.biomeId) {
                                if (biomeId != null) {
                                    biomeSample[0] = biomeId;
                                    break;
                                }
                            }
                        }
                        if (tintSample[0] == 0) {
                            for (final int tint : snapshot.tintArgb) {
                                if (tint != -1 && tint != 0) {
                                    tintSample[0] = tint;
                                    break;
                                }
                            }
                        }
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
                "MCA import r.{}.{}.mca: {} chunks imported, {} real-skip, {} not-generated, "
                    + "biome sample {}, tint sample {}",
                task.mcaX(), task.mcaZ(), imported[0], exploredSkip[0], notGenerated[0],
                biomeSample[0], String.format("0x%08X", tintSample[0]));
            if (filesDone.get() % LOG_FILE_INTERVAL == 0) {
                ConfluxMapMod.LOGGER.info(
                    "[confluxmap] MCA render progress: {} files done ({} chunks imported, "
                        + "{} real-skip, {} not-generated stubs, {} chunk failures), "
                        + "{} files queued, biome sample {}, tint sample {}",
                    filesDone.get(), chunksImported.get(), chunksRealSkip.get(),
                    chunksNotGenerated.get(), chunksFailed.get(), pendingSize(),
                    biomeSample[0], String.format("0x%08X", tintSample[0]));
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
            importedIndex.put(task.path().getFileName().toString(), mtime);
            persistIndex();
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
    private record PendingFile(int mcaX, int mcaZ, Path path, long mtime, int distance) {
    }

    /** Resolves the session's region directory, save-root index path, and persisted index. */
    private void ensureSessionPaths(final SessionGuard.Session session) {
        final Path saveRoot = client.getServer().getSavePath(WorldSavePath.ROOT);
        final String dimension = session.dimension().namespace() + "." + session.dimension().path();
        regionDirectory = RegionStoragePaths.regionDirectory(
            saveRoot, session.dimension().namespace() + ":" + session.dimension().path()
        );
        importIndexPath = saveRoot.resolve(ConfluxMapMod.ID)
            .resolve("mca-import-index-" + dimension + ".json");
        loadIndex();
    }

    /** Reads the persisted import index; a converter version bump discards every entry once. */
    private void loadIndex() {
        final Path path = importIndexPath;
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        try {
            final IndexFile file = GSON.fromJson(
                Files.readString(path), IndexFile.class
            );
            if (file == null || file.version != CONVERTER_VERSION || file.files == null) {
                ConfluxMapMod.LOGGER.info(
                    "[confluxmap] MCA render: import index written by converter v{} is obsolete "
                        + "(current v{}); already-converted files will be re-imported once",
                    file == null ? "?" : file.version, CONVERTER_VERSION);
                importedIndex.clear();
                return;
            }
            importedIndex.clear();
            importedIndex.putAll(file.files);
        } catch (final IOException | RuntimeException e) {
            ConfluxMapMod.LOGGER.warn(
                "[confluxmap] MCA render: unreadable import index at {}, starting fresh", path, e);
            importedIndex.clear();
        }
    }

    /** Debounced index write; tiny (hundreds of entries), so a trailing write is cheap. */
    private void persistIndex() {
        final Path path = importIndexPath;
        if (path == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        final long last = lastIndexWriteAtMs;
        if (now - last < INDEX_WRITE_DEBOUNCE_MS) {
            return;
        }
        lastIndexWriteAtMs = now;
        try {
            Files.createDirectories(path.getParent());
            final Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(new IndexFile(CONVERTER_VERSION, importedIndex)));
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            ConfluxMapMod.LOGGER.warn(
                "[confluxmap] MCA render: failed to persist import index at {}", path, e);
        }
    }

    private static long mtimeOf(final Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (final IOException e) {
            return 0L;
        }
    }

    /** Persisted shape of the import index; {@link #version} gates stale-converter entries. */
    private static final class IndexFile {
        final int version;
        final Map<String, Long> files;

        @SuppressWarnings("unused")
        IndexFile() {
            version = 0;
            files = new java.util.HashMap<>();
        }

        IndexFile(final int version, final Map<String, Long> files) {
            this.version = version;
            this.files = files;
        }
    }
}

package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.FlatBaselineS2C;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.predict.FlatBaseline;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import cn.net.rms.confluxmap.core.store.ServerInstanceIdStore;
import cn.net.rms.confluxmap.core.store.WorldIdStore;
import cn.net.rms.confluxmap.core.update.GithubReleaseFetcher;
import cn.net.rms.confluxmap.core.update.UpdateCheckService;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import cn.net.rms.confluxmap.server.ServerConfig;
import cn.net.rms.confluxmap.server.PlayerPositionBroadcastGate;
import cn.net.rms.confluxmap.server.web.WebMapServer;
import cn.net.rms.confluxmap.server.web.WebMapPrivacyStore;
import cn.net.rms.confluxmap.server.shared.SharedWaypointIo;
import cn.net.rms.confluxmap.server.shared.SharedWaypointService;
import cn.net.rms.confluxmap.server.shared.SharedWaypointStore;
import cn.net.rms.confluxmap.server.shared.SharedWaypointValidator;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldLoadEvent;

/** Owns one Paper server's companion lifecycle and platform adapters. */
final class PaperCompanion implements Listener {
    private static final int MAX_LIVE_SUMMARIES_PER_TICK = 2;
    private static final int MAX_LIVE_INSPECTIONS_PER_TICK = 128;
    enum WaypointToggleResult {
        ENABLED,
        DISABLED,
        ALREADY_ENABLED,
        ALREADY_DISABLED,
        MASTER_DISABLED,
        LOAD_FAILED,
        SAVE_FAILED,
        DISABLED_SAVE_FAILED
    }

    private record ChunkKey(PaperWorldDirectory.Entry world, int chunkX, int chunkZ) {
    }

    private record PendingRemoval(boolean awaitMcaWrite, long observedMcaMtime) {
    }

    private static final Logger SHARED_LOGGER = LogManager.getLogger("ConfluxMap-Paper");

    private final ConfluxMapPaperPlugin plugin;
    private final PaperServerConfigIo configIo;
    private final PaperWorldDirectory worlds = new PaperWorldDirectory();
    // Chunk handles are owned by their region, so only coordinates are tracked here; the rotation
    // is what spreads the capture budget evenly across the loaded chunks.
    private final PaperLiveChunkRotation<ChunkKey> liveChunks = new PaperLiveChunkRotation<>();
    private final Map<ChunkKey, PendingRemoval> pendingRemovals = new ConcurrentHashMap<>();
    private final Map<Integer, FlatBaseline> flatBaselines = new ConcurrentHashMap<>();
    private final Map<Integer, Map<FlatBaseline, Integer>> flatCandidates =
        new ConcurrentHashMap<>();
    private final PaperPlayerProbe players;
    private ServerConfig config;
    private UUID worldId;
    private UUID instanceId;
    private long worldSeed;
    private Path primaryWorldRoot;
    private PaperCorrectionService corrections;
    private PaperMapColors mapColors;
    private PaperChunkLoadStateService chunkLoadStates;
    private SharedWaypointService sharedWaypoints;
    private PaperNetworking networking;
    private PaperSharedWaypointNetworking sharedNetworking;
    private PaperPluginMessageDispatcher pluginMessages;
    private ScheduledTask tickTask;
    private WebMapServer webMap;
    private PaperWebMapBackend webMapBackend;
    private int webPlayerTicks;
    private WebMapPrivacyStore webMapPrivacy;
    private final PlayerPositionBroadcastGate playerPositionBroadcast =
        new PlayerPositionBroadcastGate();

    PaperCompanion(
        final ConfluxMapPaperPlugin plugin,
        final PaperServerConfigIo configIo
    ) {
        this.plugin = plugin;
        this.configIo = configIo;
        this.players = new PaperPlayerProbe(plugin);
    }

    void enable() {
        config = configIo.load();
        for (final World world : Bukkit.getWorlds()) {
            worlds.add(world);
        }
        final World primary = primaryWorld();
        if (primary == null) {
            throw new IllegalStateException("Paper server has no loaded worlds");
        }
        primaryWorldRoot = primary.getWorldFolder().toPath();
        worldId = WorldIdStore.loadOrCreate(primaryWorldRoot);
        // Kept beside the config rather than in the world, so a world synced to a mirror server
        // reaches it without carrying this server's identity along.
        instanceId = ServerInstanceIdStore.loadOrCreate(configIo.directory());
        worldSeed = primary.getSeed();
        webMapPrivacy = new WebMapPrivacyStore(
            primaryWorldRoot.resolve("confluxmap/webmap-hidden.txt")
        );
        try {
            webMapPrivacy.load();
        } catch (final IOException e) {
            plugin.getSLF4JLogger().error("Could not load web-map privacy preferences", e);
        }
        mapColors = new PaperMapColors();
        initializeFlatBaselines(mapColors);
        corrections = new PaperCorrectionService(
            config,
            worlds,
            Bukkit.getMinecraftVersion(),
            worldSeed,
            entry -> flatBaselines.get(entry.index()),
            mapColors,
            plugin.getSLF4JLogger()
        );
        // The load-level service polls Chunk.LoadLevel for every loaded chunk, and that is region
        // owned state on Folia: publishing it there would cost thousands of region tasks per tick.
        // The feature therefore stays off, and clients are told during the handshake that it is
        // unavailable rather than silently receiving nothing.
        chunkLoadStates = config.enabled && config.shareChunkLoadState && !PaperPlatform.FOLIA
            ? new PaperChunkLoadStateService() : null;
        if (config.shareChunkLoadState && PaperPlatform.FOLIA) {
            plugin.getSLF4JLogger().info(
                "Chunk load state stays disabled: regionised servers cannot poll load levels"
            );
        }
        if (config.enabled) {
            NativeLib.init(primaryWorldRoot.resolve("confluxmap"));
            if (config.shareWaypoints) {
                sharedWaypoints = loadSharedWaypoints();
            }
        }
        if (config.checkForUpdates) {
            new UpdateCheckService(
                plugin.getPluginMeta().getVersion(),
                GithubReleaseFetcher.confluxMapReleases(plugin.getPluginMeta().getVersion()),
                SHARED_LOGGER
            ).checkAsync(info -> plugin.getSLF4JLogger().warn(
                "Conflux Map {} is available (installed {}). Download: {}",
                info.latestVersion(), info.currentVersion(), info.releaseUrl()
            ));
        }
        pluginMessages = new PaperPluginMessageDispatcher();
        networking = new PaperNetworking(plugin, this, pluginMessages);
        sharedNetworking = new PaperSharedWaypointNetworking(plugin, this, pluginMessages);
        networking.register();
        sharedNetworking.register();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (PaperPlatform.FOLIA) {
            plugin.getSLF4JLogger().info(
                "Skipping the initial loaded-chunk scan: regionised servers do not expose it, "
                    + "so live summaries start from the chunk loads that follow"
            );
        }
        for (final PaperWorldDirectory.Entry entry : worlds.entries()) {
            plugin.getSLF4JLogger().info(
                "Paper companion dimension {} index={} region={}",
                entry.dimensionId(), entry.index(), entry.regionDirectory()
            );
            if (PaperPlatform.FOLIA) {
                continue;
            }
            for (final Chunk chunk : entry.world().getLoadedChunks()) {
                trackLoaded(
                    entry,
                    chunk,
                    entry.preset() == WorldPreset.FLAT
                        && !flatBaselines.containsKey(entry.index())
                );
            }
        }
        tickTask = PaperPlatform.startTicking(plugin, ignored -> tick());
        if (config.enabled && config.webMap.enabled) {
            try {
                webMapBackend = new PaperWebMapBackend(plugin, this);
                webMap = WebMapServer.start(config.webMap, webMapBackend);
                plugin.getSLF4JLogger().info(
                    "Web map listening on {}:{}",
                    config.webMap.bindAddress, config.webMap.port
                );
            } catch (final IOException e) {
                webMapBackend = null;
                plugin.getSLF4JLogger().error("Web map failed to start", e);
            }
        }
        plugin.getSLF4JLogger().info(
            "Paper companion ready (enabled={}, seed={}, corrections={}, loadState={}, waypoints={})",
            config.enabled, config.shareSeed, config.shareCorrections,
            chunkLoadStates != null, sharedWaypointsEnabled()
        );
    }

    void disable() {
        if (webMap != null) {
            webMap.close();
            webMap = null;
            webMapBackend = null;
        }
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        HandlerList.unregisterAll(this);
        if (networking != null) {
            networking.unregister();
        }
        if (sharedNetworking != null) {
            sharedNetworking.unregister();
        }
        if (pluginMessages != null) {
            pluginMessages.clear();
            pluginMessages = null;
        }
        if (chunkLoadStates != null) {
            chunkLoadStates.clear();
            chunkLoadStates = null;
        }
        if (corrections != null) {
            corrections.close();
            corrections = null;
        }
        sharedWaypoints = null;
        liveChunks.clear();
        pendingRemovals.clear();
        flatBaselines.clear();
        flatCandidates.clear();
        players.clear();
        mapColors = null;
        webMapPrivacy = null;
    }

    @EventHandler
    public void onWorldLoad(final WorldLoadEvent event) {
        worlds.add(event.getWorld());
        final PaperWorldDirectory.Entry entry = worlds.find(event.getWorld());
        if (entry == null) {
            return;
        }
        if (entry.preset() == WorldPreset.FLAT && mapColors != null) {
            PaperWorldMetadata.flatBaseline(entry.world(), mapColors::mapColorId).ifPresent(
                baseline -> flatBaselines.put(entry.index(), baseline)
            );
        }
        // Regionised servers do not expose the loaded-chunk set, so a world that appears after
        // startup contributes its live summaries through the chunk loads that follow it.
        if (PaperPlatform.FOLIA) {
            return;
        }
        for (final Chunk chunk : event.getWorld().getLoadedChunks()) {
            trackLoaded(
                entry,
                chunk,
                entry.preset() == WorldPreset.FLAT
                    && !flatBaselines.containsKey(entry.index())
            );
        }
    }

    @EventHandler
    public void onChunkLoad(final ChunkLoadEvent event) {
        final PaperWorldDirectory.Entry entry = worlds.find(event.getWorld());
        trackLoaded(
            entry,
            event.getChunk(),
            entry != null && entry.preset() == WorldPreset.FLAT
                && !flatBaselines.containsKey(entry.index())
        );
    }

    @EventHandler
    public void onChunkUnload(final ChunkUnloadEvent event) {
        final PaperWorldDirectory.Entry entry = worlds.find(event.getWorld());
        if (entry == null) {
            return;
        }
        final Chunk chunk = event.getChunk();
        // The unload event is dispatched by the region that owns the chunk, so the last summary
        // can still be taken here; dispatching it would race the unload and lose the snapshot.
        summarize(entry, chunk);
        final ChunkKey key = new ChunkKey(entry, chunk.getX(), chunk.getZ());
        liveChunks.remove(key);
        pendingRemovals.put(key, new PendingRemoval(
            event.isSaveChunk(),
            PaperAnvilReader.regionMtime(
                entry.regionDirectory(), chunk.getX(), chunk.getZ()
            )
        ));
        if (chunkLoadStates != null) {
            chunkLoadStates.onChunkUnload(entry, chunk);
        }
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent event) {
        final UUID playerId = event.getPlayer().getUniqueId();
        // Quit is dispatched by the region that owns the player, while the session tables it
        // clears belong to the global state, so the teardown is handed back to the global region.
        PaperPlatform.global(plugin, () -> {
            players.forget(playerId);
            if (networking != null) {
                networking.disconnect(playerId);
            }
            if (sharedNetworking != null) {
                sharedNetworking.disconnect(playerId);
            }
            if (pluginMessages != null) {
                pluginMessages.disconnect(playerId);
            }
        });
    }

    @EventHandler
    public void onPlayerRegisterChannel(final PlayerRegisterChannelEvent event) {
        pluginMessages.channelRegistered(
            pluginMessages.recipient(plugin, event.getPlayer()),
            event.getChannel()
        );
    }

    boolean isEnabled() {
        return config != null && config.enabled;
    }

    boolean webMapHidden(final UUID playerId) {
        return webMapPrivacy != null && webMapPrivacy.hidden(playerId);
    }

    boolean setWebMapHidden(final UUID playerId, final boolean hidden) {
        if (webMapPrivacy == null) return false;
        try {
            webMapPrivacy.setHidden(playerId, hidden);
            return true;
        } catch (final IOException e) {
            plugin.getSLF4JLogger().error("Could not persist web-map privacy preference", e);
            return false;
        }
    }

    ServerConfig config() {
        return config;
    }

    UUID worldId() {
        return worldId;
    }

    /** Identity of this server installation, unaffected by copying the world it hosts. */
    UUID instanceId() {
        return instanceId;
    }

    long worldSeed() {
        return worldSeed;
    }

    PaperWorldDirectory worlds() {
        return worlds;
    }

    /**
     * Player state captured on each player's own scheduler.
     *
     * <p>Positions, names and permissions belong to the entity, so the global tick may not read
     * them directly; every consumer works from these snapshots instead.
     */
    PaperPlayerProbe players() {
        return players;
    }

    PaperCorrectionService corrections() {
        return corrections;
    }

    PaperChunkLoadStateService chunkLoadStates() {
        return chunkLoadStates;
    }

    boolean chunkLoadStatesEnabled() {
        return isEnabled() && config.shareChunkLoadState && chunkLoadStates != null;
    }

    SharedWaypointService sharedWaypoints() {
        return sharedWaypoints;
    }

    boolean sharedWaypointsEnabled() {
        return isEnabled() && config.shareWaypoints && sharedWaypoints != null;
    }

    void onSharedWaypointCommandMutation(
        final SharedWaypointService.MutationResult mutation
    ) {
        sharedNetworking.commandMutation(mutation);
    }

    List<FlatBaselineS2C.Entry> flatBaselines() {
        final List<FlatBaselineS2C.Entry> result = new ArrayList<>();
        flatBaselines.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> result.add(new FlatBaselineS2C.Entry(
                entry.getKey(), entry.getValue()
            )));
        return List.copyOf(result);
    }

    synchronized WaypointToggleResult enableSharedWaypoints() {
        if (!isEnabled()) {
            return WaypointToggleResult.MASTER_DISABLED;
        }
        if (sharedWaypointsEnabled()) {
            return WaypointToggleResult.ALREADY_ENABLED;
        }
        SharedWaypointService candidate = sharedWaypoints;
        if (candidate == null) {
            candidate = loadSharedWaypoints();
            if (candidate == null) {
                return WaypointToggleResult.LOAD_FAILED;
            }
        }
        final boolean previous = config.shareWaypoints;
        config.shareWaypoints = true;
        if (!configIo.save(config)) {
            config.shareWaypoints = previous;
            return WaypointToggleResult.SAVE_FAILED;
        }
        sharedWaypoints = candidate;
        sharedNetworking.featureStateChanged();
        return WaypointToggleResult.ENABLED;
    }

    synchronized WaypointToggleResult disableSharedWaypoints() {
        final boolean wasEnabled = config.shareWaypoints || sharedWaypointsEnabled();
        config.shareWaypoints = false;
        final boolean saved = configIo.save(config);
        if (!wasEnabled) {
            return saved
                ? WaypointToggleResult.ALREADY_DISABLED
                : WaypointToggleResult.DISABLED_SAVE_FAILED;
        }
        sharedNetworking.featureStateChanged();
        return saved ? WaypointToggleResult.DISABLED : WaypointToggleResult.DISABLED_SAVE_FAILED;
    }

    private void tick() {
        if (!isEnabled()) {
            return;
        }
        players.refresh();
        refreshLiveChunks();
        removeExpiredLiveSummaries();
        corrections.tick();
        if (chunkLoadStates != null) {
            chunkLoadStates.tick();
        }
        sharedNetworking.tick();
        if (playerPositionBroadcast.tick(config.allowEntityRadar)) {
            networking.broadcastPlayerPositions();
        }
        if (webMapBackend != null && (config.webMap.sharePlayers || sharedWaypointsEnabled())
            && ++webPlayerTicks >= 40) {
            webPlayerTicks = 0;
            webMapBackend.updatePlayers();
        }
    }

    private void trackLoaded(
        final PaperWorldDirectory.Entry entry,
        final Chunk chunk,
        final boolean captureNow
    ) {
        if (entry == null || chunk == null) {
            return;
        }
        final ChunkKey key = new ChunkKey(entry, chunk.getX(), chunk.getZ());
        liveChunks.add(key);
        pendingRemovals.remove(key);
        // Null on regionised servers by construction, so reaching this call means the handler is
        // already running on the thread that owns the chunk.
        if (chunkLoadStates != null) {
            chunkLoadStates.onChunkLoad(entry, chunk);
        }
        if (captureNow && isEnabled()) {
            summarize(entry, chunk);
        }
    }

    private void refreshLiveChunks() {
        final int configuredPerTick = Math.max(
            1, (config.maxChunkSummariesPerSecond + 19) / 20
        );
        final int captureBudget = Math.min(MAX_LIVE_SUMMARIES_PER_TICK, configuredPerTick);
        final int available = liveChunks.size();
        final int inspectionBudget = Math.min(available, MAX_LIVE_INSPECTIONS_PER_TICK);
        final long nowNanos = System.nanoTime();
        int captured = 0;
        for (int inspected = 0;
             inspected < inspectionBudget && captured < captureBudget;
             inspected++) {
            final ChunkKey key = liveChunks.next();
            if (key == null) {
                break;
            }
            if (!corrections.liveSummaryDemanded(
                key.world(),
                key.chunkX(),
                key.chunkZ(),
                nowNanos
            )) {
                continue;
            }
            capture(key);
            captured++;
        }
    }

    /**
     * Schedules a live summary on the region that owns the chunk.
     *
     * <p>The world time and height limits are read here, on the global region, because they are
     * world wide state; only the snapshot itself needs the owning region.
     */
    private void capture(final ChunkKey key) {
        if (!isEnabled()) {
            return;
        }
        final PaperWorldDirectory.Entry entry = key.world();
        final World world = entry.world();
        final long revision = Math.max(1L, world.getFullTime());
        final int minHeight = world.getMinHeight();
        final int maxHeight = world.getMaxHeight();
        PaperPlatform.onChunk(plugin, world, key.chunkX(), key.chunkZ(), () -> {
            if (!world.isChunkLoaded(key.chunkX(), key.chunkZ())) {
                return;
            }
            summarize(
                entry,
                world.getChunkAt(key.chunkX(), key.chunkZ()),
                revision,
                minHeight,
                maxHeight
            );
        });
    }

    /** Takes a snapshot of a chunk the calling thread already owns. */
    private void summarize(final PaperWorldDirectory.Entry entry, final Chunk chunk) {
        summarize(
            entry,
            chunk,
            Math.max(1L, entry.world().getFullTime()),
            entry.world().getMinHeight(),
            entry.world().getMaxHeight()
        );
    }

    private void summarize(
        final PaperWorldDirectory.Entry entry,
        final Chunk chunk,
        final long revision,
        final int minHeight,
        final int maxHeight
    ) {
        if (!isEnabled() || !chunk.isLoaded()) {
            return;
        }
        try {
            final ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, true, false);
            final SummaryCodec.Chunk summary = corrections.summarizeLive(
                snapshot,
                revision,
                minHeight,
                maxHeight
            );
            corrections.putLive(entry, chunk.getX(), chunk.getZ(), summary);
            learnFlatBaseline(entry, summary);
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().warn(
                "Could not summarize loaded chunk {},{} in {}",
                chunk.getX(), chunk.getZ(), entry.dimensionId(), e
            );
        }
    }

    private void learnFlatBaseline(
        final PaperWorldDirectory.Entry entry,
        final SummaryCodec.Chunk summary
    ) {
        if (entry.preset() != WorldPreset.FLAT || summary == null || !summary.generated()) {
            return;
        }
        final Map<FlatBaseline, Integer> candidates = flatCandidates.computeIfAbsent(
            entry.index(), ignored -> new ConcurrentHashMap<>()
        );
        for (int z = 2; z < 16; z += 4) {
            for (int x = 2; x < 16; x += 4) {
                final SummaryCodec.Column column = summary.columns()[z * 16 + x];
                if (column == null || column.kind() == SurfaceKind.UNKNOWN.ordinal()) {
                    continue;
                }
                final FlatBaseline candidate = new FlatBaseline(
                    column.biomeId(), column.surfaceY(), column.kind(), column.mapColorId(),
                    column.fluidDepth()
                );
                if (candidates.size() < 64 || candidates.containsKey(candidate)) {
                    candidates.merge(candidate, 1, Integer::sum);
                }
            }
        }
        candidates.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .ifPresent(best -> flatBaselines.put(entry.index(), best.getKey()));
    }

    private void initializeFlatBaselines(final PaperMapColors mapColors) {
        for (final PaperWorldDirectory.Entry entry : worlds.entries()) {
            if (entry.preset() != WorldPreset.FLAT) {
                continue;
            }
            PaperWorldMetadata.flatBaseline(entry.world(), mapColors::mapColorId).ifPresent(
                baseline -> flatBaselines.put(entry.index(), baseline)
            );
        }
    }

    private void removeExpiredLiveSummaries() {
        final Iterator<Map.Entry<ChunkKey, PendingRemoval>> iterator =
            pendingRemovals.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<ChunkKey, PendingRemoval> entry = iterator.next();
            final ChunkKey key = entry.getKey();
            final PendingRemoval pending = entry.getValue();
            final long currentMtime = PaperAnvilReader.regionMtime(
                key.world().regionDirectory(), key.chunkX(), key.chunkZ()
            );
            if (pending.awaitMcaWrite()
                && (currentMtime <= 0L || currentMtime == pending.observedMcaMtime())) {
                continue;
            }
            corrections.removeLive(key.world(), key.chunkX(), key.chunkZ());
            iterator.remove();
        }
    }

    private SharedWaypointService loadSharedWaypoints() {
        final SharedWaypointIo io = new SharedWaypointIo(
            primaryWorldRoot, instanceId.toString(), SHARED_LOGGER
        );
        try {
            final Map<DimensionId, SharedWaypointValidator.HeightRange> dimensions =
                new LinkedHashMap<>();
            for (final PaperWorldDirectory.Entry entry : worlds.entries()) {
                dimensions.put(
                    entry.parsedDimensionId(),
                    new SharedWaypointValidator.HeightRange(
                        entry.world().getMinHeight(), entry.world().getMaxHeight()
                    )
                );
            }
            final SharedWaypointValidator validator = new SharedWaypointValidator(dimensions);
            final SharedWaypointStore store = new SharedWaypointStore(
                SharedWaypointService.sanitizeLoaded(io.load(), validator, SHARED_LOGGER)
            );
            return new SharedWaypointService(
                store,
                io,
                validator,
                Clock.systemUTC(),
                UUID::randomUUID,
                new SharedWaypointService.Limits(
                    config.maxSharedWaypointsPerWorld,
                    config.maxSharedWaypointsPerPlayer,
                    config.sharedWaypointMutationsPerMinute
                ),
                config.sharedWaypointAccessPolicy(),
                event -> plugin.getSLF4JLogger().info(
                    "Shared waypoint operation={} actor={} action={} status={} error={} waypoint={} revision={}",
                    event.operationId(), event.actorId(), event.action(), event.status(), event.error(),
                    event.waypointId(), event.revision()
                ),
                SHARED_LOGGER
            );
        } catch (final SharedWaypointIo.UnsupportedSchemaVersionException e) {
            plugin.getSLF4JLogger().error(
                "Shared waypoints disabled: {} has unsupported schema {}",
                io.file(), e.schemaVersion()
            );
        } catch (final IOException | RuntimeException e) {
            plugin.getSLF4JLogger().error(
                "Shared waypoints disabled: could not initialize {}", io.file(), e
            );
        }
        return null;
    }

    private World primaryWorld() {
        return worlds.entries().stream()
            .map(PaperWorldDirectory.Entry::world)
            .min(Comparator.comparingInt(world ->
                world.getEnvironment() == World.Environment.NORMAL ? 0 : 1
            ))
            .orElse(null);
    }
}

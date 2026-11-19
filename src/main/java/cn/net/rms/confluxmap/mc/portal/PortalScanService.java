package cn.net.rms.confluxmap.mc.portal;

import cn.net.rms.confluxmap.compat.Dimensions;
import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.portal.PortalKind;
import cn.net.rms.confluxmap.core.portal.PortalMarker;
import cn.net.rms.confluxmap.core.portal.PortalService;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import cn.net.rms.confluxmap.neoforge.compat.ClientTickEvents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Client-side detection of activated portal blocks (nether portals, end
 * portals, end gateways) feeding {@link PortalService}. Detection is
 * chunk-granular with a palette prefilter: a section whose palette cannot
 * contain a tracked block is skipped without touching its 4096 cells, so a
 * chunk-load scan costs a handful of palette lookups for ordinary terrain.
 *
 * <p>Three triggers, all on the main thread: a chunk arriving from the server
 * (via {@code PortalScanHandler}), a block update inside a chunk that either
 * introduces a portal block or sits in an already-tracked chunk (same
 * handler), and a one-shot pass over the loaded chunks when a session starts
 * or the setting is switched on mid-session. Block updates never edit
 * markers incrementally - they schedule a full rescan of that one chunk, which
 * keeps anchors and multi-kind chunks correct in both directions (a portal
 * lit by another player appears; a dismantled one disappears).
 *
 * <p>Markers are attributed to the dimension of {@code client.level}, never
 * to the session dimension: the session tracker only rotates at the end of a
 * tick, so chunks arriving right after a dimension switch would otherwise be
 * filed under the dimension the player just left, and those markers would
 * show up on the wrong dimension's map.
 */
public final class PortalScanService {
    private final Minecraft client;
    private final ConfluxConfig config;
    private final PortalService portals;

    private Map<Block, PortalKind> portalBlocks;
    private boolean pendingInitialScan;

    public PortalScanService(
        final Minecraft client,
        final ConfluxConfig config,
        final PortalService portals
    ) {
        this.client = client;
        this.config = config;
        this.portals = portals;
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> tick());
    }

    /** Main thread, from the session tracker: rescan loaded chunks once the world is up. */
    public void onSessionChanged(final SessionGuard.Session session) {
        pendingInitialScan = session.active();
    }

    /** The setting was switched on mid-session; catch up with what is already loaded. */
    public void requestLoadedChunkScan() {
        scanLoadedChunks();
    }

    /** Mixin entry: a whole chunk just arrived from the server. */
    public void chunkLoaded(final LevelChunk chunk) {
        if (config.portalMarkersEnabled) {
            scanChunk(chunk);
        }
    }

    /** Mixin entry: one block changed (single update or section-delta batch member). */
    public void blockUpdated(final int x, final int y, final int z, final BlockState state) {
        final ClientLevel world = client.level;
        if (!config.portalMarkersEnabled || world == null) {
            return;
        }
        final DimensionId dimension = Dimensions.of(world);
        if (portalBlocks().containsKey(state.getBlock())
            || portals.hasMarkersInChunk(dimension, x >> 4, z >> 4)) {
            rescanChunkAt(x >> 4, z >> 4);
        }
    }

    private void tick() {
        if (pendingInitialScan && client.level != null && client.player != null) {
            pendingInitialScan = false;
            if (config.portalMarkersEnabled) {
                scanLoadedChunks();
            }
        }
    }

    private void scanLoadedChunks() {
        final ClientLevel world = client.level;
        if (world == null || client.player == null) {
            return;
        }
        final int radius = MinecraftAccess.viewDistance(client);
        final int playerChunkX = client.player.getBlockX() >> 4;
        final int playerChunkZ = client.player.getBlockZ() >> 4;
        for (int chunkX = playerChunkX - radius; chunkX <= playerChunkX + radius; chunkX++) {
            for (int chunkZ = playerChunkZ - radius; chunkZ <= playerChunkZ + radius; chunkZ++) {
                final LevelChunk chunk = world.getChunkSource()
                    .getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk != null) {
                    scanChunk(chunk);
                }
            }
        }
    }

    private void rescanChunkAt(final int chunkX, final int chunkZ) {
        final ClientLevel world = client.level;
        if (world == null) {
            return;
        }
        final LevelChunk chunk = world.getChunkSource()
            .getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk != null) {
            scanChunk(chunk);
        }
    }

    private void scanChunk(final LevelChunk chunk) {
        final ClientLevel world = client.level;
        if (world == null) {
            return;
        }
        final DimensionId dimension = Dimensions.of(world);
        final int chunkX = chunk.getPos().x();
        final int chunkZ = chunk.getPos().z();
        portals.applyChunkScan(dimension, chunkX, chunkZ, findPortals(chunk, dimension, chunkX, chunkZ));
    }

    /** First tracked block per kind in bottom-up section order; empty when the chunk holds none. */
    private List<PortalMarker> findPortals(
        final LevelChunk chunk,
        final DimensionId dimension,
        final int chunkX,
        final int chunkZ
    ) {
        final Map<Block, PortalKind> tracked = portalBlocks();
        if (tracked.isEmpty()) {
            return List.of();
        }
        final Map<PortalKind, PortalMarker> found = new HashMap<>();
        final LevelChunkSection[] sections = chunk.getSections();
        for (final LevelChunkSection section : sections) {
            if (found.size() == tracked.size()) {
                break;
            }
            if (section.hasOnlyAir()) {
                continue;
            }
            final PalettedContainer<BlockState> states = section.getStates();
            if (!states.maybeHas(state -> tracked.containsKey(state.getBlock()))) {
                continue;
            }
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        final PortalKind kind = tracked.get(states.get(x, y, z).getBlock());
                        if (kind == null || found.containsKey(kind)) {
                            continue;
                        }
                        found.put(kind, new PortalMarker(
                            dimension, chunkX, chunkZ, kind,
                            (chunkX << 4) + x, (chunkZ << 4) + z
                        ));
                        if (found.size() == tracked.size()) {
                            return new ArrayList<>(found.values());
                        }
                    }
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    private Map<Block, PortalKind> portalBlocks() {
        Map<Block, PortalKind> resolved = portalBlocks;
        if (resolved == null) {
            final Map<Block, PortalKind> fresh = new HashMap<>();
            for (final PortalKind kind : PortalKind.values()) {
                Regs.block(Ids.of(kind.blockId())).ifPresent(block -> fresh.put(block, kind));
            }
            portalBlocks = fresh;
            resolved = fresh;
        }
        return resolved;
    }
}
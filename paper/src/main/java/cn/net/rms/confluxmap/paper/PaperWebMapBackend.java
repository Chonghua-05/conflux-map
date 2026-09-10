package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.net.MapRegionSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.MsgCodec;
import cn.net.rms.confluxmap.core.net.ProtoException;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.server.web.WebMapBackend;
import cn.net.rms.confluxmap.server.web.WebMapManifest;
import cn.net.rms.confluxmap.server.web.WebMapSnapshot;
import cn.net.rms.confluxmap.server.web.WebPlayerSnapshot;
import cn.net.rms.confluxmap.server.web.WebAvatarCache;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.Bukkit;

/** Paper adapter for the shared HTTP transport. */
final class PaperWebMapBackend implements WebMapBackend {
    private final ConfluxMapPaperPlugin plugin;
    private final PaperCompanion companion;
    private final WebMapManifest manifest;
    private volatile WebMapSnapshot snapshot = WebMapSnapshot.EMPTY;
    private volatile Map<UUID, URI> skinUrls = Map.of();
    private final WebAvatarCache avatars = new WebAvatarCache();

    PaperWebMapBackend(final ConfluxMapPaperPlugin plugin, final PaperCompanion companion) {
        this.plugin = plugin;
        this.companion = companion;
        final List<WebMapManifest.Dimension> dimensions = new ArrayList<>();
        for (final PaperWorldDirectory.Entry entry : companion.worlds().entries()) {
            final DimensionId id = DimensionId.parse(entry.dimensionId());
            dimensions.add(new WebMapManifest.Dimension(
                entry.index(), entry.dimensionId(), entry.dimensionType(),
                PredictionDimensions.supported(id) && entry.preset().predictable(),
                entry.preset()
            ));
        }
        final String worldgenVersion = Bukkit.getMinecraftVersion();
        final java.util.OptionalInt predictionVersion = McVersions.toCubiomes(worldgenVersion);
        final boolean sharePrediction = companion.config().shareSeed
            && companion.config().allowBiomeMap
            && predictionVersion.isPresent();
        manifest = new WebMapManifest(
            companion.worldId().toString(), worldgenVersion,
            sharePrediction ? companion.worldSeed() : null,
            sharePrediction ? predictionVersion.getAsInt() : -1,
            dimensions,
            new WebMapManifest.Limits(
                companion.config().maxTilesPerRequest,
                Math.max(
                    companion.config().minRequestIntervalMs,
                    companion.config().webMap.minRequestIntervalMs
                )
            )
        );
    }

    @Override
    public WebMapManifest manifest() {
        return manifest;
    }

    @Override
    public void requestTiles(
        final UUID clientId,
        final MapViewReqC2S request,
        final int requestBytes,
        final Consumer<byte[]> response
    ) {
        // Correction state is global state, so these requests queue on the global region rather
        // than being handled on the HTTP thread that accepted them.
        PaperPlatform.global(plugin, () -> companion.corrections().requestTiles(
            clientId, request, requestBytes, true, sender(response)
        ));
    }

    @Override
    public void subscribeRegions(
        final UUID clientId,
        final MapRegionSyncSubscribeC2S request,
        final Consumer<byte[]> response
    ) {
        PaperPlatform.global(plugin, () -> companion.corrections().subscribeRegions(
            clientId, request, sender(response)
        ));
    }

    @Override
    public void removeClient(final UUID clientId) {
        PaperPlatform.global(plugin, () -> companion.corrections().remove(clientId));
    }

    @Override
    public WebPlayerSnapshot players() {
        return new WebPlayerSnapshot(snapshot.playerRevision(), snapshot.players());
    }

    @Override
    public WebMapSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public CompletableFuture<byte[]> avatar(final UUID playerId) {
        return avatars.face(skinUrls.get(playerId));
    }

    private static PaperCorrectionService.MessageSender sender(
        final Consumer<byte[]> response
    ) {
        return new PaperCorrectionService.MessageSender() {
            @Override
            public void send(final Message message) {
                try {
                    response.accept(MsgCodec.encode(message));
                } catch (final ProtoException ignored) {
                    // Every server-originated message is validated before it reaches this seam.
                }
            }

            @Override
            public void sendEncoded(final Message message, final byte[] payload) {
                response.accept(payload);
            }
        };
    }

    void updatePlayers() {
        final List<WebPlayerSnapshot.Player> result = new ArrayList<>();
        final Map<UUID, URI> currentSkins = new HashMap<>();
        if (companion.config().webMap.sharePlayers) {
            // Player state is read on each player's own scheduler; this only consumes snapshots.
            for (final PaperPlayerProbe.Snapshot player : companion.players().snapshots()) {
                if (companion.webMapHidden(player.id())) continue;
                final PaperWorldDirectory.Entry world =
                    companion.worlds().findByDimensionId(player.worldKey());
                if (world == null) continue;
                result.add(new WebPlayerSnapshot.Player(
                    player.id().toString(), player.name(), world.index(),
                    player.x(), player.z(),
                    player.hidden()
                ));
                final URI skin = player.skin();
                if (skin != null) {
                    currentSkins.put(player.id(), skin);
                }
            }
        }
        final List<WebMapSnapshot.Waypoint> waypoints = new ArrayList<>();
        if (companion.sharedWaypointsEnabled()) {
            for (final SharedWaypoint waypoint : companion.sharedWaypoints().snapshot().waypoints()) {
                final int dimension = worldIndex(waypoint.dimensionId());
                if (dimension >= 0) {
                    waypoints.add(new WebMapSnapshot.Waypoint(
                        waypoint.id().toString(), waypoint.name(), dimension,
                        waypoint.x(), waypoint.y(), waypoint.z(), waypoint.colorArgb(),
                        waypoint.type().name()
                    ));
                }
            }
        }
        snapshot = snapshot.next(result, waypoints);
        skinUrls = Map.copyOf(currentSkins);
    }

    private int worldIndex(final DimensionId target) {
        for (final PaperWorldDirectory.Entry entry : companion.worlds().entries()) {
            if (DimensionId.parse(entry.dimensionId()).equals(target)) return entry.index();
        }
        return -1;
    }
}

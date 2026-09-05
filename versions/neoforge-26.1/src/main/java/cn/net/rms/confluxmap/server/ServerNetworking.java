package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.MinecraftVersion;
import cn.net.rms.confluxmap.compat.PlayNetworking;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.net.CorrectionProfile;
import cn.net.rms.confluxmap.core.net.ErrorS2C;
import cn.net.rms.confluxmap.core.net.FlatBaselineS2C;
import cn.net.rms.confluxmap.core.net.HelloC2S;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.net.LoadStateSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapSyncCapability;
import cn.net.rms.confluxmap.core.net.MapRegionSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapRegionViewReqC2S;
import cn.net.rms.confluxmap.core.net.MapSyncProtocol;
import cn.net.rms.confluxmap.core.net.MapSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.MsgCodec;
import cn.net.rms.confluxmap.core.net.NegotiatedMapSync;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ProtoException;
import cn.net.rms.confluxmap.core.net.PlayerPositionsS2C;
import cn.net.rms.confluxmap.core.net.ServerInstanceS2C;
import cn.net.rms.confluxmap.core.net.ServerViewDistanceS2C;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import cn.net.rms.confluxmap.nativepredict.PredictorVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

/**
 * Server-side channel handler for {@link Proto#CHANNEL_ID}. Owns the receiver registrations and
 * the per-player state map; defers everything else to {@link ConfluxMapCompanion}.
 *
 * <p>HELLO_C2S is answered with HELLO_POLICY immediately. MAP_VIEW_REQ is registered so the
 * channel is wired, but its handler just logs-and-drops each packet in S3 - the patch-serving
 * implementation lands in S4 ({@code PatchBuilder}) and the request-planning client side in S5.
 */
public final class ServerNetworking {
    public static final Identifier CHANNEL = Ids.of(Proto.CHANNEL_ID);

    private final ConfluxMapCompanion companion;
    private final ConcurrentMap<UUID, Integer> malformedStrikes = new ConcurrentHashMap<>();
    private final Set<UUID> mutedPlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<UUID, NegotiatedMapSync> peerSessions =
        new ConcurrentHashMap<>();
    private boolean registered;

    public ServerNetworking(final ConfluxMapCompanion companion) {
        this.companion = companion;
    }

    public synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        PlayNetworking.registerServer(CHANNEL, this::onReceive);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> { if (!(event.getEntity() instanceof final ServerPlayer player)) return;
            final UUID uuid = player.getUUID();
            malformedStrikes.remove(uuid);
            mutedPlayers.remove(uuid);
            peerSessions.remove(uuid);
            final RegionSummaryService summaries = companion.summaries();
            if (summaries != null) {
                summaries.remove(uuid);
            }
            final ChunkLoadStateService loadStates = companion.chunkLoadStates();
            if (loadStates != null) {
                loadStates.remove(uuid);
            }
        });
    }

    private void onReceive(
        final MinecraftServer server,
        final ServerPlayer player,
        final byte[] payload
    ) {
        if (!companion.isEnabled()) {
            return;
        }
        if (mutedPlayers.contains(player.getUUID())) {
            return;
        }
        try {
            validatePayload(payload);
        } catch (final ProtoException e) {
            ConfluxMapMod.LOGGER.warn(
                "companion: dropping malformed payload from {} ({})",
                MinecraftAccess.playerName(player),
                e.getMessage()
            );
            recordMalformed(player);
            return;
        }
        try {
            final NegotiatedMapSync session = peerSessions.get(player.getUUID());
            final Message msg = session == null
                ? MapSyncProtocol.decodeServerbound(payload)
                : session.decodeInbound(payload);
            if (msg instanceof final HelloC2S hello) {
                handleHello(server, player, hello);
            } else if (msg instanceof final MapViewReqC2S req) {
                handleMapViewReq(server, player, req, payload.length);
            } else if (msg instanceof final LoadStateSubscribeC2S req) {
                handleLoadStateSubscribe(server, player, req);
            } else if (msg instanceof final MapSyncSubscribeC2S req) {
                handleMapSyncSubscribe(server, player, req);
            } else if (msg instanceof final MapRegionViewReqC2S req) {
                handleMapRegionViewReq(server, player, req, payload.length);
            } else if (msg instanceof final MapRegionSyncSubscribeC2S req) {
                handleMapRegionSyncSubscribe(server, player, req);
            } else {
                ConfluxMapMod.LOGGER.warn(
                    "companion: unexpected {} from {} (server-side handlers expect C2S only)",
                    msg.getClass().getSimpleName(), MinecraftAccess.playerName(player)
                );
            }
        } catch (final ProtoException e) {
            ConfluxMapMod.LOGGER.warn("companion: undecodable {}-byte payload from {} ({})",
                payload.length, MinecraftAccess.playerName(player), e.getMessage());
            recordMalformed(player);
        }
    }

    private void recordMalformed(final ServerPlayer player) {
        final int strikes = malformedStrikes.merge(player.getUUID(), 1, Integer::sum);
        send(player, new ErrorS2C(ErrorS2C.ERR_MALFORMED_REQUEST, "malformed companion payload (strike " + strikes + ")"));
        if (strikes >= 3) {
            mutedPlayers.add(player.getUUID());
        }
    }

    private void handleHello(final MinecraftServer server, final ServerPlayer player, final HelloC2S hello) {
        if (!companion.isEnabled()) {
            return;
        }
        final MapSyncProtocol.ServerHandshake handshake = MapSyncProtocol.acceptClient(
            hello, ConfluxMapMod.getVersion(), PredictorVersion.full()
        );
        final NegotiatedMapSync session = handshake.session();
        companion.summaries().remove(player.getUUID());
        peerSessions.put(player.getUUID(), session);
        if (handshake.selection() != null) {
            send(player, handshake.selection());
        }
        // FLAT_BASELINE goes out before policy: the client activates its session on HELLO_POLICY, so the
        // flat surfaces must already be stored by then. Pre-minor-2 clients log and ignore it.
        final List<FlatBaselineS2C.Entry> flatEntries = buildFlatBaselines(server);
        if (!flatEntries.isEmpty()
            && session.supports(MapSyncCapability.FLAT_BASELINE)) {
            sendNegotiated(player, session, new FlatBaselineS2C(flatEntries));
        }
        if (session.supports(MapSyncCapability.SERVER_VIEW_DISTANCE)) {
            sendNegotiated(player, session, ServerViewDistanceS2C.bounded(
                server.getPlayerList().getViewDistance()
            ));
        }
        // Precedes HELLO_POLICY for the same reason FLAT_BASELINE does: the client opens its
        // session on the policy frame and must already know which namespace the data belongs to.
        if (session.supports(MapSyncCapability.SERVER_INSTANCE)) {
            sendNegotiated(
                player, session, new ServerInstanceS2C(companion.instanceId().toString())
            );
        }
        final HelloPolicyS2C policy = buildPolicy(server, session);
        send(player, policy);
        ConfluxMapMod.LOGGER.info(
            "companion: replied HELLO_POLICY to {} (modVersion={} predictorVersion={} corrections={} absolute={} negotiated={} worldId={} seedGranted={})",
            MinecraftAccess.playerName(player), hello.modVersion(), hello.predictorVersion(),
            policy.flags().correctionsEnabled(), session.forceAbsolute(),
            handshake.selection() != null, policy.worldId(), policy.flags().seedGranted()
        );
    }

    private void handleMapViewReq(
        final MinecraftServer server,
        final ServerPlayer player,
        final MapViewReqC2S req,
        final int payloadBytes
    ) {
        final NegotiatedMapSync session = peerSession(player);
        if (!companion.config().shareCorrections || !session.correctionsEnabled()) {
            send(player, new ErrorS2C(ErrorS2C.ERR_COMPANION_DISABLED, "map corrections are disabled"));
            return;
        }
        companion.summaries().request(
            server, player, req, payloadBytes, session.forceAbsolute(),
            senderFor(player, session)
        );
    }

    private void handleMapRegionViewReq(
        final MinecraftServer server,
        final ServerPlayer player,
        final MapRegionViewReqC2S request,
        final int payloadBytes
    ) {
        final NegotiatedMapSync session = peerSession(player);
        if (!companion.config().shareCorrections || !session.correctionsEnabled()) {
            send(player, new ErrorS2C(ErrorS2C.ERR_COMPANION_DISABLED, "map corrections are disabled"));
            return;
        }
        companion.summaries().requestRegions(
            server, player, request, payloadBytes, session.forceAbsolute(),
            session.correctionProfile(),
            senderFor(player, session)
        );
    }

    private void handleLoadStateSubscribe(
        final MinecraftServer server,
        final ServerPlayer player,
        final LoadStateSubscribeC2S request
    ) {
        final ChunkLoadStateService service = companion.chunkLoadStates();
        if (!companion.chunkLoadStatesEnabled() || service == null) {
            send(player, new ErrorS2C(ErrorS2C.ERR_COMPANION_DISABLED, "chunk load state is disabled"));
            return;
        }
        if (!service.subscribe(
            server, player.getUUID(), request,
            delta -> sendNegotiated(player, peerSession(player), delta)
        )) {
            send(player, new ErrorS2C(ErrorS2C.ERR_MALFORMED_REQUEST, "invalid load-state dimension"));
        }
    }

    private void handleMapSyncSubscribe(
        final MinecraftServer server,
        final ServerPlayer player,
        final MapSyncSubscribeC2S request
    ) {
        if (!companion.config().shareCorrections || !peerSession(player).correctionsEnabled()) {
            send(player, new ErrorS2C(ErrorS2C.ERR_COMPANION_DISABLED, "map corrections are disabled"));
            return;
        }
        if (!companion.summaries().subscribe(
            server, player.getUUID(), request,
            senderFor(player, peerSession(player))
        )) {
            send(player, new ErrorS2C(ErrorS2C.ERR_MALFORMED_REQUEST, "invalid map-sync viewport"));
        }
    }

    private void handleMapRegionSyncSubscribe(
        final MinecraftServer server,
        final ServerPlayer player,
        final MapRegionSyncSubscribeC2S request
    ) {
        if (!companion.config().shareCorrections || !peerSession(player).correctionsEnabled()) {
            send(player, new ErrorS2C(ErrorS2C.ERR_COMPANION_DISABLED, "map corrections are disabled"));
            return;
        }
        if (!companion.summaries().subscribeRegions(
            server, player.getUUID(), request,
            senderFor(player, peerSession(player))
        )) {
            send(player, new ErrorS2C(ErrorS2C.ERR_MALFORMED_REQUEST, "invalid region-sync viewport"));
        }
    }

    private NegotiatedMapSync peerSession(final ServerPlayer player) {
        return peerSessions.getOrDefault(player.getUUID(), disabledSession());
    }

    void broadcastPlayerPositions(final MinecraftServer server) {
        if (!companion.config().allowEntityRadar) {
            return;
        }
        final List<PlayerPositionsS2C.Entry> entries = new ArrayList<>();
        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            entries.add(new PlayerPositionsS2C.Entry(
                player.getUUID(),
                MinecraftAccess.playerName(player),
                player.level().dimension().identifier().toString(),
                player.getX(),
                player.getY(),
                player.getZ(),
                player.isSpectator()
            ));
        }
        final PlayerPositionsS2C snapshot = new PlayerPositionsS2C(entries);
        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            final NegotiatedMapSync session = peerSessions.get(player.getUUID());
            if (session != null && session.supports(MapSyncCapability.PLAYER_POSITIONS)) {
                sendNegotiated(player, session, snapshot);
            }
        }
    }

    private HelloPolicyS2C buildPolicy(
        final MinecraftServer server,
        final NegotiatedMapSync session
    ) {
        final ServerConfig cfg = companion.config();
        final HelloPolicyS2C.Flags configured = policyFlags(cfg);
        final HelloPolicyS2C.Flags flags = compatibleFlags(configured, session);
        final UUID worldId = companion.worldIds().get(server);
        final HelloPolicyS2C.Budgets budgets = new HelloPolicyS2C.Budgets(
            cfg.maxBytesPerSecondPerPlayer,
            cfg.maxTilesPerRequest,
            cfg.minRequestIntervalMs,
            Proto.DEFAULT_MAX_PATCH_LOD
        );
        final List<HelloPolicyS2C.DimDescriptor> dims = buildDimDescriptors(server, flags.seedGranted());
        return new HelloPolicyS2C(flags, worldId.toString(), WORLDGEN_VERSION, budgets, dims);
    }

    static HelloPolicyS2C.Flags compatibleFlags(
        final HelloPolicyS2C.Flags configured,
        final NegotiatedMapSync session
    ) {
        return CompanionPolicy.compatibleFlags(configured, session);
    }

    static HelloPolicyS2C.Flags policyFlags(final ServerConfig cfg) {
        return CompanionPolicy.configuredFlags(cfg);
    }

    private static List<HelloPolicyS2C.DimDescriptor> buildDimDescriptors(final MinecraftServer server, final boolean shareSeed) {
        // Vanilla seed is identical across dimensions (see research R6). Read once from the overworld.
        final long seed = server.overworld().getSeed();
        final List<HelloPolicyS2C.DimDescriptor> dims = new ArrayList<>(2);
        for (final ServerLevel sw : server.getAllLevels()) {
            final String dimId = sw.dimension().identifier().toString();
            final String dimType = sw.dimension().identifier().getPath();
            final WorldPreset preset = WorldPresetDetector.detect(sw);
            // predictable=false also withholds the seed on pre-preset clients (their seedFor
            // checks it), so superflat/custom dims degrade correctly across versions.
            final boolean predictable = PredictionDimensions.supported(DimensionId.parse(dimId))
                && preset.predictable();
            // The server always knows the seed; we just don't always share it.
            final boolean hasSeed = shareSeed;
            final long seedToSend = shareSeed ? seed : 0L;
            dims.add(new HelloPolicyS2C.DimDescriptor(dimId, dimType, predictable, hasSeed, seedToSend, preset));
        }
        return dims;
    }

    /** One entry per superflat dimension, indexed like {@link #buildDimDescriptors}'s list. */
    private static List<FlatBaselineS2C.Entry> buildFlatBaselines(final MinecraftServer server) {
        final List<FlatBaselineS2C.Entry> entries = new ArrayList<>(1);
        int dimIndex = 0;
        for (final ServerLevel sw : server.getAllLevels()) {
            if (WorldPresetDetector.detect(sw) == WorldPreset.FLAT) {
                final int index = dimIndex;
                FlatWorldBaseline.of(sw).ifPresent(
                    baseline -> entries.add(new FlatBaselineS2C.Entry(index, baseline))
                );
            }
            dimIndex++;
        }
        return entries;
    }

    private static void send(final ServerPlayer player, final Message msg) {
        final byte[] payload;
        try {
            payload = MsgCodec.encode(msg);
        } catch (final ProtoException e) {
            ConfluxMapMod.LOGGER.error("companion: failed to serialize {}: {}", msg.getClass().getSimpleName(), e.getMessage());
            return;
        }
        send(player, payload);
    }

    private static void send(final ServerPlayer player, final byte[] payload) {
        PlayNetworking.sendServer(player, CHANNEL, payload);
    }

    private static void sendNegotiated(
        final ServerPlayer player,
        final NegotiatedMapSync session,
        final Message message
    ) {
        try {
            send(player, session.encodeOutbound(message));
        } catch (final ProtoException e) {
            ConfluxMapMod.LOGGER.warn(
                "companion: could not encode {} ({})",
                message.getClass().getSimpleName(), e.getMessage()
            );
        }
    }

    private static RegionSummaryService.MessageSender senderFor(
        final ServerPlayer player,
        final NegotiatedMapSync session
    ) {
        return new RegionSummaryService.MessageSender() {
            @Override
            public void send(final Message message) {
                ServerNetworking.sendNegotiated(player, session, message);
            }

            @Override
            public void sendEncoded(final Message message, final byte[] payload) {
                if (session.correctionProfile().carriesSourceMetadata()) {
                    ServerNetworking.send(player, payload);
                } else {
                    ServerNetworking.sendNegotiated(player, session, message);
                }
            }
        };
    }

    private static NegotiatedMapSync disabledSession() {
        return NegotiatedMapSync.server(
            CorrectionProfile.SOURCE_LIGHT_V2,
            NegotiatedMapSync.CorrectionMode.DISABLED,
            "",
            Map.of()
        );
    }

    private static void validatePayload(final byte[] payload) throws ProtoException {
        final int readable = payload.length;
        if (readable < 1) {
            throw new ProtoException("empty payload");
        }
        if (readable > Proto.MAX_C2S_PAYLOAD) {
            throw new ProtoException("C2S payload " + readable + " above cap " + Proto.MAX_C2S_PAYLOAD);
        }
    }

    /** Vanilla worldgen version this server jar speaks; the client maps it to a cubiomes {@code MCVersion}. */
    private static final String WORLDGEN_VERSION = MinecraftVersion.current();
}


package cn.net.rms.confluxmap.server.shared;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.PlayNetworking;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointCodec;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointMessage;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointProto;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointProtocolException;
import cn.net.rms.confluxmap.server.ConfluxMapCompanion;
import cn.net.rms.confluxmap.server.ServerConfig;
import java.util.UUID;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.Identifier;

/** Fabric transport adapter for the independent shared-waypoint protocol channel. */
public final class SharedWaypointNetworking {
    public static final Identifier CHANNEL = Ids.of(SharedWaypointProto.CHANNEL_ID);

    private final ConfluxMapCompanion companion;
    private final SharedWaypointSessionHandler sessions = new SharedWaypointSessionHandler();
    private boolean registered;

    public SharedWaypointNetworking(final ConfluxMapCompanion companion) {
        this.companion = companion;
    }

    /** Global receivers live for the process lifetime and therefore must be registered only once. */
    public synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        PlayNetworking.registerServer(CHANNEL, this::onReceive);
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> onServerTick(e.getServer()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> { if (!(event.getEntity() instanceof final ServerPlayer player)) return;
            final UUID playerId = player.getUUID();
            sessions.disconnect(playerId);
            // Service-owned idempotency results deliberately survive reconnects.
        });
    }

    private void onReceive(
        final MinecraftServer server,
        final ServerPlayer player,
        final byte[] payload
    ) {
        // Master-disabled companions never answer either protocol channel.
        if (!companion.isEnabled()) {
            return;
        }
        if (sessions.isMuted(player.getUUID())) {
            return;
        }
        final int readable = payload.length;
        if (readable < 1 || readable > SharedWaypointProto.MAX_C2S_PAYLOAD) {
            recordMalformed(player, readable, "payload size outside cap");
            return;
        }
        final SharedWaypointMessage message;
        try {
            message = SharedWaypointCodec.decodeC2S(
                payload,
                sessions.negotiatedMinor(player.getUUID())
            );
        } catch (final SharedWaypointProtocolException | RuntimeException e) {
            recordMalformed(player, readable, e.getMessage());
            return;
        }
        server.execute(() -> handle(server, player, message));
    }

    private void handle(
        final MinecraftServer server,
        final ServerPlayer player,
        final SharedWaypointMessage message
    ) {
        if (!companion.isEnabled() || server.getPlayerList().getPlayer(player.getUUID()) != player) {
            return;
        }
        final SharedWaypointSessionHandler.Dispatch dispatch = sessions.handle(
            peer(player),
            message,
            environment(server)
        );
        for (final SharedWaypointMessage direct : dispatch.direct()) {
            send(player, direct);
        }
        if (dispatch.broadcast() != null) {
            broadcast(server, dispatch.broadcast());
        }
    }

    private void broadcast(final MinecraftServer server, final SharedWaypointMessage message) {
        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (sessions.isSubscribed(player.getUUID())) {
                send(player, message);
            }
        }
    }

    private void onServerTick(final MinecraftServer server) {
        if (!companion.isEnabled()) {
            return;
        }
        SharedWaypointSessionHandler.Environment environment = null;
        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            final SharedWaypointSessionHandler.Peer peer = peer(player);
            if (!sessions.updateOperator(peer)) {
                continue;
            }
            if (environment == null) {
                environment = environment(server);
            }
            send(player, sessions.status(peer, environment));
        }
    }

    private void recordMalformed(
        final ServerPlayer player,
        final int payloadBytes,
        final String reason
    ) {
        final SharedWaypointSessionHandler.MalformedOutcome outcome = sessions.recordMalformed(player.getUUID());
        ConfluxMapMod.LOGGER.warn(
            "shared-waypoint: dropped malformed {}-byte payload from {} (strike {}/{}, reason={})",
            payloadBytes,
            MinecraftAccess.playerName(player),
            outcome.strikes(),
            SharedWaypointSessionHandler.MAX_MALFORMED_STRIKES,
            reason == null ? "decode failure" : reason
        );
        if (outcome.newlyMuted()) {
            ConfluxMapMod.LOGGER.warn(
                "shared-waypoint: muted malformed packets from {} until disconnect",
                MinecraftAccess.playerName(player)
            );
        }
    }

    /** Sends a fresh capability status after an operator changes the runtime feature switch. */
    public void onFeatureStateChanged(final MinecraftServer server) {
        if (!companion.sharedWaypointsEnabled()) {
            sessions.clearSubscriptions();
        }
        if (!companion.isEnabled()) {
            return;
        }
        final SharedWaypointSessionHandler.Environment environment = environment(server);
        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (sessions.isCompatible(player.getUUID())) {
                send(player, sessions.status(peer(player), environment));
            }
        }
    }

    /** Publishes a command-originated mutation to clients subscribed through the binary protocol. */
    public void onCommandMutation(
        final MinecraftServer server,
        final SharedWaypointService.MutationResult mutation
    ) {
        final SharedWaypointMessage delta = SharedWaypointSessionHandler.deltaMessage(mutation);
        if (delta != null) {
            broadcast(server, delta);
        }
    }

    public void onServerStopping() {
        sessions.clear();
    }

    private SharedWaypointSessionHandler.Environment environment(final MinecraftServer server) {
        final ServerConfig config = companion.config();
        return new SharedWaypointSessionHandler.Environment(
            companion.sharedWaypointsEnabled(),
            companion.worldIds().get(server).toString(),
            config.maxSharedWaypointsPerWorld,
            config.maxSharedWaypointsPerPlayer,
            companion.sharedWaypoints()
        );
    }

    private static SharedWaypointSessionHandler.Peer peer(final ServerPlayer player) {
        return new SharedWaypointSessionHandler.Peer(
            player.getUUID(),
            MinecraftAccess.playerName(player),
            MinecraftAccess.hasPermission(player, 2)
        );
    }

    private void send(
        final ServerPlayer player,
        final SharedWaypointMessage message
    ) {
        // canSend prevents an unknown custom payload from reaching unmodded/older clients.
        if (!PlayNetworking.canSend(player, CHANNEL)) {
            return;
        }
        final byte[] payload;
        try {
            payload = SharedWaypointCodec.encode(
                message,
                sessions.negotiatedMinor(player.getUUID())
            );
        } catch (final SharedWaypointProtocolException | RuntimeException e) {
            ConfluxMapMod.LOGGER.error(
                "shared-waypoint: failed to encode {} for {}",
                message.getClass().getSimpleName(),
                MinecraftAccess.playerName(player),
                e
            );
            return;
        }
        PlayNetworking.sendServer(player, CHANNEL, payload);
    }
}


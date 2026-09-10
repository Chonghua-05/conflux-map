package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.core.net.shared.SharedWaypointCodec;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointMessage;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointProto;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointProtocolException;
import cn.net.rms.confluxmap.server.ServerConfig;
import cn.net.rms.confluxmap.server.shared.SharedWaypointSessionHandler;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

/** Paper plugin-messaging adapter for the independent shared-waypoint protocol. */
final class PaperSharedWaypointNetworking implements PluginMessageListener {
    static final String CHANNEL = SharedWaypointProto.CHANNEL_ID;

    private final ConfluxMapPaperPlugin plugin;
    private final PaperCompanion companion;
    private final PaperPluginMessageDispatcher messages;
    private final SharedWaypointSessionHandler sessions = new SharedWaypointSessionHandler();

    PaperSharedWaypointNetworking(
        final ConfluxMapPaperPlugin plugin,
        final PaperCompanion companion,
        final PaperPluginMessageDispatcher messages
    ) {
        this.plugin = plugin;
        this.companion = companion;
        this.messages = messages;
    }

    void register() {
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
    }

    void unregister() {
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
        sessions.clear();
    }

    @Override
    public void onPluginMessageReceived(
        final String channel,
        final Player player,
        final byte[] payload
    ) {
        if (!CHANNEL.equals(channel) || !companion.isEnabled()
            || sessions.isMuted(player.getUniqueId())) {
            return;
        }
        final byte[] stable = payload.clone();
        // A payload can only arrive on a channel the client registered, so the reply path is
        // opened here rather than waiting for the registration event.
        messages.confirm(messages.recipient(plugin, player), channel);
        if (!Bukkit.isPrimaryThread()) {
            // Session and waypoint state live in common/ and are not thread safe, so the payload
            // is handled on the global region, which owns the shared waypoint state.
            PaperPlatform.global(plugin, () -> receive(player.getUniqueId(), stable));
            return;
        }
        receive(player.getUniqueId(), stable);
    }

    void tick() {
        if (!companion.isEnabled()) {
            return;
        }
        final SharedWaypointSessionHandler.Environment environment = environment();
        for (final PaperPlayerProbe.Snapshot player : companion.players().snapshots()) {
            if (sessions.updateOperator(peer(player))) {
                deliver(player, sessions.status(peer(player), environment));
            }
        }
    }

    void disconnect(final UUID playerId) {
        sessions.disconnect(playerId);
    }

    void featureStateChanged() {
        if (!companion.sharedWaypointsEnabled()) {
            sessions.clearSubscriptions();
        }
        if (!companion.isEnabled()) {
            return;
        }
        final SharedWaypointSessionHandler.Environment environment = environment();
        for (final PaperPlayerProbe.Snapshot player : companion.players().snapshots()) {
            if (sessions.isCompatible(player.id())) {
                deliver(player, sessions.status(peer(player), environment));
            }
        }
    }

    void commandMutation(final cn.net.rms.confluxmap.server.shared.SharedWaypointService.MutationResult mutation) {
        final SharedWaypointMessage delta = SharedWaypointSessionHandler.deltaMessage(mutation);
        if (delta == null) {
            return;
        }
        for (final PaperPlayerProbe.Snapshot recipient : companion.players().snapshots()) {
            if (sessions.isSubscribed(recipient.id())) {
                deliver(recipient, delta);
            }
        }
    }

    private void receive(final UUID playerId, final byte[] payload) {
        final Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline() || !companion.isEnabled()) {
            return;
        }
        if (payload.length < 1 || payload.length > SharedWaypointProto.MAX_C2S_PAYLOAD) {
            malformed(player, payload.length, "payload size outside cap");
            return;
        }
        final SharedWaypointMessage message;
        try {
            message = SharedWaypointCodec.decodeC2S(
                payload,
                sessions.negotiatedMinor(player.getUniqueId())
            );
        } catch (final SharedWaypointProtocolException | RuntimeException e) {
            malformed(player, payload.length, e.getMessage());
            return;
        }
        final SharedWaypointSessionHandler.Dispatch dispatch = sessions.handle(
            peer(player), message, environment()
        );
        for (final SharedWaypointMessage direct : dispatch.direct()) {
            send(player, direct);
        }
        if (dispatch.broadcast() != null) {
            for (final PaperPlayerProbe.Snapshot recipient : companion.players().snapshots()) {
                if (sessions.isSubscribed(recipient.id())) {
                    deliver(recipient, dispatch.broadcast());
                }
            }
        }
    }

    private SharedWaypointSessionHandler.Environment environment() {
        final ServerConfig config = companion.config();
        return new SharedWaypointSessionHandler.Environment(
            companion.sharedWaypointsEnabled(),
            companion.worldId().toString(),
            config.maxSharedWaypointsPerWorld,
            config.maxSharedWaypointsPerPlayer,
            companion.sharedWaypoints()
        );
    }

    private SharedWaypointSessionHandler.Peer peer(final Player player) {
        final PaperPlayerProbe.Snapshot snapshot =
            companion.players().snapshotOf(player.getUniqueId());
        if (snapshot == null) {
            // Only reachable during the first refresh interval after a join, before the player
            // has published itself; the caller already runs on the thread that owns it.
            return new SharedWaypointSessionHandler.Peer(
                player.getUniqueId(),
                player.getName(),
                player.hasPermission("confluxmap.admin")
            );
        }
        return peer(snapshot);
    }

    private static SharedWaypointSessionHandler.Peer peer(
        final PaperPlayerProbe.Snapshot snapshot
    ) {
        return new SharedWaypointSessionHandler.Peer(
            snapshot.id(),
            snapshot.name(),
            snapshot.admin()
        );
    }

    /** Sends to a player identified by its snapshot, without reading any entity state. */
    private void deliver(
        final PaperPlayerProbe.Snapshot snapshot,
        final SharedWaypointMessage message
    ) {
        final Player target = Bukkit.getPlayer(snapshot.id());
        if (target != null) {
            send(target, message);
        }
    }

    private void malformed(final Player player, final int bytes, final String reason) {
        final SharedWaypointSessionHandler.MalformedOutcome outcome =
            sessions.recordMalformed(player.getUniqueId());
        plugin.getSLF4JLogger().warn(
            "Dropped malformed shared-waypoint payload from {} (bytes={}, strike={}/{}, reason={})",
            player.getName(), bytes, outcome.strikes(),
            SharedWaypointSessionHandler.MAX_MALFORMED_STRIKES,
            reason == null ? "decode failure" : reason
        );
        if (outcome.newlyMuted()) {
            plugin.getSLF4JLogger().warn(
                "Muted malformed shared-waypoint payloads from {} until disconnect",
                player.getName()
            );
        }
    }

    private void send(final Player player, final SharedWaypointMessage message) {
        try {
            messages.send(
                messages.recipient(plugin, player),
                CHANNEL,
                SharedWaypointCodec.encode(
                    message,
                    sessions.negotiatedMinor(player.getUniqueId())
                )
            );
        } catch (final SharedWaypointProtocolException | RuntimeException e) {
            plugin.getSLF4JLogger().error(
                "Failed to encode {} for {}",
                message.getClass().getSimpleName(), player.getName(), e
            );
        }
    }
}

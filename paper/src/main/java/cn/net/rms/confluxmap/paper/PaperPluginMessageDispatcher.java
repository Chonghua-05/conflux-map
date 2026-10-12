package cn.net.rms.confluxmap.paper;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;

/** Delivers Paper plugin messages only after the client has registered the target channel. */
final class PaperPluginMessageDispatcher {
    static final int MAX_PENDING_MESSAGES_PER_CHANNEL = 16;
    static final int MAX_PENDING_BYTES_PER_CHANNEL = 2 * 1024 * 1024;

    private static final class PendingChannel {
        private final ArrayDeque<byte[]> payloads = new ArrayDeque<>();
        private int bytes;

        private void add(final byte[] payload) {
            if (payload.length > MAX_PENDING_BYTES_PER_CHANNEL) {
                return;
            }
            while (!payloads.isEmpty()
                && (payloads.size() >= MAX_PENDING_MESSAGES_PER_CHANNEL
                    || bytes + payload.length > MAX_PENDING_BYTES_PER_CHANNEL)) {
                bytes -= payloads.removeFirst().length;
            }
            payloads.addLast(payload.clone());
            bytes += payload.length;
        }
    }

    private final Map<UUID, Map<String, PendingChannel>> pending = new HashMap<>();
    /**
     * Channels each player has registered, mirrored here because the player's own channel set is
     * entity state: the global region sends messages constantly and may not read it directly.
     */
    private final Map<UUID, Set<String>> listening = new ConcurrentHashMap<>();

    interface Recipient {
        UUID id();

        boolean listensTo(String channel);

        void send(String channel, byte[] payload);
    }

    Recipient recipient(
        final ConfluxMapPaperPlugin plugin,
        final Player player
    ) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(player, "player");
        return new Recipient() {
            @Override
            public UUID id() {
                return player.getUniqueId();
            }

            @Override
            public boolean listensTo(final String channel) {
                final Set<String> channels = listening.get(player.getUniqueId());
                return channels != null && channels.contains(channel);
            }

            @Override
            public void send(final String channel, final byte[] payload) {
                player.sendPluginMessage(plugin, channel, payload);
            }
        };
    }

    synchronized void send(
        final Recipient recipient,
        final String channel,
        final byte[] payload
    ) {
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(payload, "payload");
        if (recipient.listensTo(channel)) {
            recipient.send(channel, payload);
            return;
        }
        pending.computeIfAbsent(recipient.id(), ignored -> new HashMap<>())
            .computeIfAbsent(channel, ignored -> new PendingChannel())
            .add(payload);
    }

    synchronized void channelRegistered(final Recipient recipient, final String channel) {
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(channel, "channel");
        confirm(recipient, channel);
    }

    /**
     * Records that the client listens to {@code channel} and releases anything queued for it.
     *
     * <p>Reaching this from an inbound payload is stronger evidence than the registration event:
     * the client cannot have sent on a channel it did not register. The companion relies on that
     * because a handshake reply is produced and sent in the same breath, before the asynchronous
     * channel mirror can have run.
     */
    synchronized void confirm(final Recipient recipient, final String channel) {
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(channel, "channel");
        listening.computeIfAbsent(recipient.id(), ignored -> ConcurrentHashMap.newKeySet())
            .add(channel);
        final Map<String, PendingChannel> playerPending = pending.get(recipient.id());
        if (playerPending == null) {
            return;
        }
        final PendingChannel ready = playerPending.remove(channel);
        if (playerPending.isEmpty()) {
            pending.remove(recipient.id());
        }
        if (ready == null) {
            return;
        }
        for (final byte[] payload : ready.payloads) {
            recipient.send(channel, payload);
        }
    }

    synchronized void disconnect(final UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        pending.remove(playerId);
        listening.remove(playerId);
    }

    /**
     * Adds the channels a player already listens to, without dropping any confirmed earlier.
     * Must run on the player's own scheduler, which is the only place the underlying channel set
     * may be read.
     */
    void seed(final Player player) {
        listening.computeIfAbsent(player.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet())
            .addAll(player.getListeningPluginChannels());
    }

    synchronized void clear() {
        pending.clear();
        listening.clear();
    }
}

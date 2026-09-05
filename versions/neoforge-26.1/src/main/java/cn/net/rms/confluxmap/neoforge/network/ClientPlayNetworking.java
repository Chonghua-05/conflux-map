package cn.net.rms.confluxmap.neoforge.network;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

/** Client-only half of the raw transport. Only install it from a physical-client entrypoint. */
public final class ClientPlayNetworking {
    private static final Map<Identifier, ClientReceiver> RECEIVERS = new ConcurrentHashMap<>();

    private ClientPlayNetworking() {
    }

    @FunctionalInterface
    public interface ClientReceiver {
        void receive(Minecraft client, ClientPacketListener handler, byte[] payload);
    }

    /** Receivers execute on the client thread and may be bound after a channel is declared. */
    public static void registerClient(final Identifier id, final ClientReceiver receiver) {
        PlayNetworking.declareChannel(id);
        RECEIVERS.put(id, Objects.requireNonNull(receiver, "receiver"));
    }

    /** Attach this listener to the mod event bus only on the physical client. */
    public static void registerPayloadHandlers(final RegisterClientPayloadHandlersEvent event) {
        for (final PlayNetworking.Channel channel : PlayNetworking.channels()) {
            event.register(channel.type, HandlerThread.MAIN, (payload, context) -> {
                final Minecraft client = Minecraft.getInstance();
                final ClientReceiver receiver = RECEIVERS.get(channel.type.id());
                // Ignore queued packets from a connection that has already been replaced.
                if (receiver != null && client.getConnection() == context.listener()
                    && context.connection().isConnected()) {
                    receiver.receive(client, client.getConnection(), payload.bytes());
                }
            });
        }
    }

    public static boolean canSend(final Identifier id) {
        final ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return canSend(connection, id);
    }

    public static void sendClient(final Identifier id, final byte[] payload) {
        final ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (!canSend(connection, id)) {
            throw new IllegalStateException("Server cannot receive play channel " + id);
        }
        connection.send(PlayNetworking.outbound(id, payload, RawPayload.MAX_SERVERBOUND_BYTES));
    }

    private static boolean canSend(final ClientPacketListener connection, final Identifier id) {
        return connection != null && PlayNetworking.registeredChannel(id) != null
            && connection.getConnection().isConnected() && connection.hasChannel(id);
    }
}

package cn.net.rms.confluxmap.neoforge.network;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Raw play-channel transport. This class is safe to load on a dedicated server. */
public final class PlayNetworking {
    private static final Map<Identifier, Channel> CHANNELS = new LinkedHashMap<>();
    private static boolean registered;

    private PlayNetworking() {
    }

    @FunctionalInterface
    public interface ServerReceiver {
        void receive(MinecraftServer server, ServerPlayer player, byte[] payload);
    }

    /** Declare channels on both physical sides before RegisterPayloadHandlersEvent fires. */
    public static void declareChannel(final Identifier id) {
        channel(id);
    }

    /** Receivers execute on the server thread and may be bound after a channel is declared. */
    public static void registerServer(final Identifier id, final ServerReceiver receiver) {
        channel(id).serverReceiver = Objects.requireNonNull(receiver, "receiver");
    }

    /** Attach this listener to the mod event bus from the common mod constructor. */
    public static synchronized void registerPayloads(final RegisterPayloadHandlersEvent event) {
        if (registered) {
            throw new IllegalStateException("Play payloads are already registered");
        }
        registered = true;
        // The application protocol negotiates compatibility inside its unchanged HELLO frame.
        final PayloadRegistrar registrar = event.registrar("1")
            .optional().executesOn(HandlerThread.MAIN);
        for (final Channel channel : CHANNELS.values()) {
            registrar.playBidirectional(channel.type, channel.codec, PlayNetworking::receiveServer);
        }
    }

    public static boolean canSend(final ServerPlayer player, final Identifier id) {
        return player != null && registeredChannel(id) != null
            && player.connection.getConnection().isConnected()
            && player.connection.hasChannel(id);
    }

    public static void sendServer(
        final ServerPlayer player,
        final Identifier id,
        final byte[] payload
    ) {
        if (!canSend(player, id)) {
            throw new IllegalStateException("Player cannot receive play channel " + id);
        }
        player.connection.send(outbound(id, payload, RawPayload.MAX_CLIENTBOUND_BYTES));
    }

    private static void receiveServer(final RawPayload payload, final IPayloadContext context) {
        final ServerPlayer player = (ServerPlayer) context.player();
        final ServerReceiver receiver = payload.channel().serverReceiver;
        if (receiver != null && context.connection().isConnected()) {
            receiver.receive(player.level().getServer(), player, payload.bytes());
        }
    }

    static synchronized Channel channel(final Identifier id) {
        Objects.requireNonNull(id, "id");
        final Channel existing = CHANNELS.get(id);
        if (existing != null) {
            return existing;
        }
        if (registered) {
            throw new IllegalStateException("Cannot declare a play channel after registration: " + id);
        }
        final Channel created = new Channel(id);
        CHANNELS.put(id, created);
        return created;
    }

    static synchronized List<Channel> channels() {
        return List.copyOf(CHANNELS.values());
    }

    static synchronized Channel registeredChannel(final Identifier id) {
        return registered ? CHANNELS.get(id) : null;
    }

    static RawPayload outbound(final Identifier id, final byte[] bytes, final int maximumBytes) {
        Objects.requireNonNull(bytes, "payload");
        if (bytes.length > maximumBytes) {
            throw new IllegalArgumentException("Payload exceeds " + maximumBytes + " bytes");
        }
        final Channel channel = registeredChannel(id);
        if (channel == null) {
            throw new IllegalStateException("Unregistered play channel " + id);
        }
        // Sending can serialize later on the network thread, after the caller reuses its array.
        return new RawPayload(channel, bytes.clone());
    }

    static final class Channel {
        final CustomPacketPayload.Type<RawPayload> type;
        final StreamCodec<RegistryFriendlyByteBuf, RawPayload> codec;
        volatile ServerReceiver serverReceiver;

        Channel(final Identifier id) {
            type = new CustomPacketPayload.Type<>(id);
            codec = RawPayload.codec(this);
        }
    }
}

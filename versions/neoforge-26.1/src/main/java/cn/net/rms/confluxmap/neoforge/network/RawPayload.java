package cn.net.rms.confluxmap.neoforge.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The payload body is exactly the existing protocol frame, with no extra length prefix. */
record RawPayload(PlayNetworking.Channel channel, byte[] bytes) implements CustomPacketPayload {
    static final int MAX_CLIENTBOUND_BYTES = 1 << 20;
    static final int MAX_SERVERBOUND_BYTES = 32_767;

    static StreamCodec<RegistryFriendlyByteBuf, RawPayload> codec(final PlayNetworking.Channel channel) {
        return StreamCodec.of(
            (buffer, payload) -> buffer.writeBytes(payload.bytes()),
            buffer -> {
                final int length = buffer.readableBytes();
                if (length > MAX_CLIENTBOUND_BYTES) {
                    throw new DecoderException("Raw play payload exceeds " + MAX_CLIENTBOUND_BYTES + " bytes");
                }
                final byte[] bytes = new byte[length];
                buffer.readBytes(bytes);
                return new RawPayload(channel, bytes);
            }
        );
    }

    @Override
    public Type<RawPayload> type() {
        return channel.type;
    }
}

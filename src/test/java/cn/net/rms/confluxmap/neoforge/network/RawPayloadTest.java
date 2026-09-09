package cn.net.rms.confluxmap.neoforge.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.Arrays;
import java.util.Base64;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class RawPayloadTest {
    // The released v0.1.0 HELLO frame also used by ReleasedProtocolCompatibilityTest.
    private static final byte[] RELEASED_HELLO = Base64.getDecoder().decode(
        "AQAFMC4xLjAAHmNiOjlhZmMxMDM4ZWE1YXxzaGltOjl8YmFzZToxNA=="
    );

    private final PlayNetworking.Channel channel = new PlayNetworking.Channel(
        Identifier.parse("confluxmap:map_sync")
    );

    @Test
    void writesReleasedHandshakeBytesWithoutLengthPrefixOrChannelId() {
        final RegistryFriendlyByteBuf buffer = buffer();
        try {
            channel.codec.encode(buffer, new RawPayload(channel, RELEASED_HELLO));

            assertEquals(RELEASED_HELLO.length, buffer.readableBytes());
            final byte[] encoded = new byte[buffer.readableBytes()];
            buffer.readBytes(encoded);
            assertArrayEquals(RELEASED_HELLO, encoded);
        } finally {
            buffer.release();
        }
    }

    @Test
    void readsAllRemainingBytesStartingAtCurrentReaderIndex() {
        final RegistryFriendlyByteBuf buffer = buffer();
        try {
            buffer.writeInt(0x12345678);
            buffer.writeBytes(RELEASED_HELLO);
            buffer.skipBytes(Integer.BYTES);

            final RawPayload decoded = channel.codec.decode(buffer);

            assertArrayEquals(RELEASED_HELLO, decoded.bytes());
            assertSame(channel.type, decoded.type());
            assertEquals(buffer.writerIndex(), buffer.readerIndex());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void acceptsExactlyOneMebibyteAndPreservesEveryByte() {
        final byte[] bytes = new byte[1_048_576];
        Arrays.fill(bytes, (byte) 0xa5);
        bytes[0] = 0;
        bytes[bytes.length - 1] = 0x7f;
        final RegistryFriendlyByteBuf buffer = buffer();
        try {
            channel.codec.encode(buffer, new RawPayload(channel, bytes));
            assertEquals(bytes.length, buffer.readableBytes());

            assertArrayEquals(bytes, channel.codec.decode(buffer).bytes());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsMoreThanOneMebibyteWithoutConsumingInput() {
        final RegistryFriendlyByteBuf buffer = buffer();
        try {
            buffer.writeZero(1_048_577);

            assertThrows(DecoderException.class, () -> channel.codec.decode(buffer));
            assertEquals(0, buffer.readerIndex());
            assertEquals(1_048_577, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void emptyTransportBodyStaysEmptyForTheApplicationCodecToValidate() {
        final RegistryFriendlyByteBuf buffer = buffer();
        try {
            channel.codec.encode(buffer, new RawPayload(channel, new byte[0]));

            assertEquals(0, buffer.readableBytes());
            assertArrayEquals(new byte[0], channel.codec.decode(buffer).bytes());
            assertEquals(0, buffer.readerIndex());
        } finally {
            buffer.release();
        }
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }
}

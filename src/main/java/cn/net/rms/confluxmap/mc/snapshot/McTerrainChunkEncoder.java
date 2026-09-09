package cn.net.rms.confluxmap.mc.snapshot;

import cn.net.rms.confluxmap.core.terrain.EncodedChunk;
import cn.net.rms.confluxmap.core.terrain.EncodedSection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.LevelChunk;

/** Copies compressed block-state containers while the client chunk is owned by the main thread. */
final class McTerrainChunkEncoder {
    private static final int LOCAL_PALETTE_MAX_BITS = 8;

    private final Minecraft client;

    McTerrainChunkEncoder(final Minecraft client) {
        this.client = client;
    }

    EncodedChunk capture(
        final int chunkX, final int chunkZ, final long sessionToken
    ) {
        final ClientLevel world = client.level;
        if (world == null) {
            return null;
        }
        final LevelChunk chunk = (LevelChunk) world.getChunkSource()
            .getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null) {
            return null;
        }

        final LevelChunkSection[] sections = chunk.getSections();
        final int minSectionY = world.getMinSectionY();
        final int maxSectionY = minSectionY + sections.length - 1;
        final List<EncodedSection> encoded = new ArrayList<>(sections.length);
        int directPaletteBits = 15;
        for (int index = 0; index < sections.length; index++) {
            final LevelChunkSection section = sections[index];
            if (section.hasOnlyAir()) {
                continue;
            }
            final PalettedContainer<net.minecraft.world.level.block.state.BlockState> states = section.getStates();
            final byte[] packet = encode(states);
            final int bits = packet[0] & 0xFF;
            if (bits > LOCAL_PALETTE_MAX_BITS) {
                directPaletteBits = bits;
            }
            encoded.add(new EncodedSection(minSectionY + index, packet));
        }
        return new EncodedChunk(
            sessionToken,
            world.getGameTime(),
            chunkX,
            chunkZ,
            minSectionY,
            maxSectionY,
            LOCAL_PALETTE_MAX_BITS,
            directPaletteBits,
            Block.getId(Blocks.AIR.defaultBlockState()),
            encoded
        );
    }

    private static byte[] encode(final PalettedContainer<net.minecraft.world.level.block.state.BlockState> states) {
        final ByteBuf bytes = Unpooled.buffer(Math.max(64, states.getSerializedSize()));
        try {
            final FriendlyByteBuf packet = new FriendlyByteBuf(bytes);
            states.write(packet);
            final byte[] result = new byte[bytes.readableBytes()];
            bytes.getBytes(bytes.readerIndex(), result);
            return result;
        } finally {
            bytes.release();
        }
    }
}

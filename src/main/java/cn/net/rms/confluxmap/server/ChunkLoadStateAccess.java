package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.ChunkLoadBand;
import java.util.Optional;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Version-adapted read access to the server's authoritative effective chunk ticket level. */
public final class ChunkLoadStateAccess {
    private ChunkLoadStateAccess() {
    }

    public record State(int level, ChunkLoadBand band) {
    }

    public static Optional<State> read(final ServerLevel world, final ChunkPos pos) {
        final ChunkHolder holder;
        holder = world.getChunkSource().chunkMap.getVisibleChunkIfPresent(chunkLong(pos));
        if (holder == null) {
            return Optional.empty();
        }
        final int level = holder.getTicketLevel();
        final ChunkLoadBand band = ChunkLoadBand.fromTicketLevel(level);
        return band == ChunkLoadBand.UNLOADED
            ? Optional.empty()
            : Optional.of(new State(level, band));
    }

    private static long chunkLong(final ChunkPos pos) {
        return pos.pack();
    }
}

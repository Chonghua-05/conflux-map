package cn.net.rms.confluxmap.server;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/** Routes successful live block writes to the active server summary service. */
public final class ServerChunkDirtyHandler {
    private static volatile RegionSummaryService summaries;

    private ServerChunkDirtyHandler() {
    }

    static void bind(final RegionSummaryService service) {
        summaries = service;
    }

    public static void chunkDirty(final LevelChunk chunk) {
        final RegionSummaryService current = summaries;
        if (current == null || chunk == null) {
            return;
        }
        final Level world = chunk.getLevel();
        if (world instanceof final ServerLevel serverWorld) {
            current.onChunkDirty(serverWorld, chunk);
        }
    }
}

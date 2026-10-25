package cn.net.rms.confluxmap.mc.snapshot;

import cn.net.rms.confluxmap.ConfluxMapMod;

/**
 * Static bridge between mixins and the capture service. Mixins fire before
 * the client entrypoint may have run, so every hook is null-tolerant, and a
 * fault never escapes into the vanilla packet path: the first one disables
 * the bridge for the rest of the process, native-fault style, because these
 * hooks run per block and per chunk.
 */
public final class ChunkCaptureHandler {
    private static volatile ChunkCaptureService service;
    private static volatile boolean disabledForSession;

    private ChunkCaptureHandler() {
    }

    public static void bind(final ChunkCaptureService captureService) {
        service = captureService;
    }

    public static void chunkDirty(final int chunkX, final int chunkZ) {
        final ChunkCaptureService s = service;
        if (s == null || disabledForSession) {
            return;
        }
        try {
            s.markDirty(chunkX, chunkZ);
        } catch (final Throwable fault) {
            disableForSession("chunkDirty", "chunk " + chunkX + "," + chunkZ, fault);
        }
    }

    /** A whole chunk arrived from the server; its already-captured neighbours go stale with it. */
    public static void chunkLoaded(final int chunkX, final int chunkZ) {
        final ChunkCaptureService s = service;
        if (s == null || disabledForSession) {
            return;
        }
        try {
            s.markChunkLoaded(chunkX, chunkZ);
        } catch (final Throwable fault) {
            disableForSession("chunkLoaded", "chunk " + chunkX + "," + chunkZ, fault);
        }
    }

    public static void blockDirty(final int blockX, final int blockZ) {
        chunkDirty(blockX >> 4, blockZ >> 4);
    }

    public static void blockDirty(
        final int blockX, final int blockY, final int blockZ, final int stateId
    ) {
        final ChunkCaptureService s = service;
        if (s == null || disabledForSession) {
            return;
        }
        try {
            s.markBlockDirty(blockX, blockY, blockZ, stateId);
        } catch (final Throwable fault) {
            disableForSession(
                "blockDirty", "block " + blockX + "," + blockY + "," + blockZ, fault
            );
        }
    }

    /** Logs the first fault with its entry point and coordinates, then drops later calls. */
    private static synchronized void disableForSession(
        final String entry, final String context, final Throwable fault
    ) {
        if (disabledForSession) {
            return;
        }
        disabledForSession = true;
        ConfluxMapMod.LOGGER.error("chunk capture disabled after fault in {} at {}", entry, context, fault);
    }
}

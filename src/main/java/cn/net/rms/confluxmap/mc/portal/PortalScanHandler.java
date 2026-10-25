package cn.net.rms.confluxmap.mc.portal;

import cn.net.rms.confluxmap.ConfluxMapMod;
import net.minecraft.block.BlockState;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Static bridge between mixins and the portal scan service, mirroring
 * {@code ChunkCaptureHandler}: mixins fire before the client entrypoint may
 * have run, so every hook is null-tolerant, and a fault never escapes into
 * the vanilla packet path - the first one disables the bridge for the rest
 * of the process, because these hooks run per block and per chunk.
 */
public final class PortalScanHandler {
    private static volatile PortalScanService service;
    private static volatile boolean disabledForSession;

    private PortalScanHandler() {
    }

    public static void bind(final PortalScanService scanService) {
        service = scanService;
    }

    public static void chunkLoaded(final WorldChunk chunk) {
        final PortalScanService s = service;
        if (s == null || disabledForSession) {
            return;
        }
        try {
            s.chunkLoaded(chunk);
        } catch (final Throwable fault) {
            final String context = chunk == null ? "null chunk" : "chunk " + chunk.getPos();
            disableForSession("chunkLoaded", context, fault);
        }
    }

    public static void blockUpdated(final int x, final int y, final int z, final BlockState state) {
        final PortalScanService s = service;
        if (s == null || disabledForSession) {
            return;
        }
        try {
            s.blockUpdated(x, y, z, state);
        } catch (final Throwable fault) {
            disableForSession("blockUpdated", "block " + x + "," + y + "," + z, fault);
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
        ConfluxMapMod.LOGGER.error("portal scan disabled after fault in {} at {}", entry, context, fault);
    }
}

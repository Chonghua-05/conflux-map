package cn.net.rms.confluxmap.core.util;

/**
 * Packs a chunk position into a single long: chunk X in the high 32 bits, chunk Z in the
 * low 32. Deliberately not Minecraft's {@code ChunkPos#toLong} layout, which puts Z high
 * and X low; keep the two conventions separate, since every capture, terrain and sync
 * store keys on this one.
 */
public final class ChunkKeys {
    private ChunkKeys() {
    }

    public static long pack(final int chunkX, final int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int chunkX(final long key) {
        return (int) (key >> 32);
    }

    public static int chunkZ(final long key) {
        return (int) key;
    }
}

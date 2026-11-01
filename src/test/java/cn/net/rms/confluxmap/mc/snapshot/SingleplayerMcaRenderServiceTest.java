package cn.net.rms.confluxmap.mc.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link SingleplayerMcaRenderService}'s static plan math, no Minecraft runtime required:
 *
 * <ul>
 *   <li>{@code mcaRange} - the inclusive MCA-file square covering the chunk window
 *       {@code [center-radius, center+radius]}, floorDiv-honest for negative worlds (an MCA
 *       file spans 32x32 chunks, and every negative chunk belongs to a negative file).</li>
 *   <li>{@code chebyshevToMca} - the Chebyshev distance from a plan center chunk to an MCA
 *       file's center chunk, used to order the import queue nearest-last.</li>
 *   <li>{@code pack} - the {@code x<<32 | z&0xFFFFFFFF} key layout shared by the session
 *       mtime index and the per-region tile flush.</li>
 * </ul>
 *
 * <p>{@code mcaRange} and the {@code McaRange} record are package-visible and called
 * directly; {@code chebyshevToMca} and {@code pack} are private static helpers, so they run
 * through reflection (pure arithmetic on the same compiled method, no Minecraft state).
 */
class SingleplayerMcaRenderServiceTest {
    private static final Method CHEBYSHEV_TO_MCA;
    private static final Method PACK;

    static {
        try {
            CHEBYSHEV_TO_MCA = SingleplayerMcaRenderService.class.getDeclaredMethod(
                "chebyshevToMca", int.class, int.class, int.class, int.class);
            CHEBYSHEV_TO_MCA.setAccessible(true);
            PACK = SingleplayerMcaRenderService.class.getDeclaredMethod("pack", int.class, int.class);
            PACK.setAccessible(true);
        } catch (final ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Test
    void mcaRangeRadiusZeroCoversExactlyTheCenterFile() {
        // Chunk 0 is file 0's first chunk.
        assertEquals(new SingleplayerMcaRenderService.McaRange(0, 0, 0, 0), range(0, 0, 0));
        assertEquals(1, range(0, 0, 0).fileCount());
        // Chunk -1 belongs to file -1 (chunks -32..-1): floorDiv, not truncating division.
        assertEquals(new SingleplayerMcaRenderService.McaRange(-1, -1, -1, -1), range(-1, -1, 0));
        assertEquals(1, range(-1, -1, 0).fileCount());
        assertEquals(new SingleplayerMcaRenderService.McaRange(1, 1, 1, 1), range(32, 33, 0));
        assertEquals(1, range(32, 33, 0).fileCount());
    }

    @Test
    void mcaRangeFollowsFloorDivAcrossNegativeWorlds() {
        // Radius 32 around chunk 0 covers chunks [-32, 32]: file -1 (chunks -32..-1), file 0
        // (0..31) and file 1 (chunk 32) -> 3x3. A 65-chunk window can never fit inside a
        // single 32-chunk file, so no radius-32 plan around any center is a 1x1 square.
        assertEquals(new SingleplayerMcaRenderService.McaRange(-1, 1, -1, 1), range(0, 0, 32));
        assertEquals(9, range(0, 0, 32).fileCount());
        // Radius 33 reaches chunk -33, and floorDiv(-33, 32) = -2 opens file -2 (chunks -64..-33).
        assertEquals(new SingleplayerMcaRenderService.McaRange(-2, 1, -2, 1), range(0, 0, 33));
        assertEquals(16, range(0, 0, 33).fileCount());
        // Center (-17,-17), radius 16: chunks [-33, -1] stay within files -2..-1 on both axes.
        assertEquals(new SingleplayerMcaRenderService.McaRange(-2, -1, -2, -1), range(-17, -17, 16));
        assertEquals(4, range(-17, -17, 16).fileCount());
        // Center (-33,33), radius 32: the x window [-65, -1] spans floorDiv(-65,32) = -3
        // through floorDiv(-1,32) = -1, the z window [1, 65] spans floorDiv(1,32) = 0 through
        // floorDiv(65,32) = 2 - three files per axis, each axis on its own side of zero.
        assertEquals(new SingleplayerMcaRenderService.McaRange(-3, -1, 0, 2), range(-33, 33, 32));
        assertEquals(9, range(-33, 33, 32).fileCount());
        // Asymmetric window: only the x axis crosses a file boundary (z stays inside file -1).
        assertEquals(new SingleplayerMcaRenderService.McaRange(-1, 0, -1, -1), range(5, -20, 10));
        assertEquals(2, range(5, -20, 10).fileCount());
        // A window that fits inside one file away from the boundaries stays a single file.
        assertEquals(new SingleplayerMcaRenderService.McaRange(3, 3, 3, 3), range(100, 100, 3));
        assertEquals(1, range(100, 100, 3).fileCount());
    }

    @Test
    void fileCountAlwaysAgreesWithTheRangeEdges() {
        final int[] centersX = {-100, -33, -17, -1, 0, 5, 100};
        final int[] centersZ = {33, -65, -2, 0, 7, -20, 96};
        final int[] radii = {0, 1, 15, 16, 31, 32, 33};
        for (final int centerX : centersX) {
            for (final int centerZ : centersZ) {
                for (final int radius : radii) {
                    final SingleplayerMcaRenderService.McaRange range = range(centerX, centerZ, radius);
                    assertEquals(
                        (range.maxMcaX() - range.minMcaX() + 1) * (range.maxMcaZ() - range.minMcaZ() + 1),
                        range.fileCount(),
                        () -> "fileCount mismatch for center " + centerX + "," + centerZ + " radius " + radius
                    );
                    // The window's edges really sit inside the reported boundary files.
                    assertEquals(Math.floorDiv(centerX - radius, 32), range.minMcaX());
                    assertEquals(Math.floorDiv(centerX + radius, 32), range.maxMcaX());
                    assertEquals(Math.floorDiv(centerZ - radius, 32), range.minMcaZ());
                    assertEquals(Math.floorDiv(centerZ + radius, 32), range.maxMcaZ());
                }
            }
        }
    }

    @Test
    void chebyshevToMcaMeasuresChunkDistanceToEachFileCenter() {
        // File 0's center chunk is (16, 16): plan center (0,0) sits 16 chunks in from it.
        assertEquals(16, chebyshev(0, 0, 0, 0));
        // A plan center on the file's own center chunk is distance 0.
        assertEquals(0, chebyshev(0, 0, 16, 16));
        // Adjacent file: +32 exactly, further files +32 each.
        assertEquals(48, chebyshev(1, 0, 0, 0));
        assertEquals(80, chebyshev(2, 0, 0, 0));
        // Chebyshev, not Manhattan: the farther axis alone decides.
        assertEquals(48, chebyshev(1, 1, 0, 0));
        assertEquals(48, chebyshev(1, 0, 0, 0));
        assertEquals(48, chebyshev(0, 1, 0, 0));
        // File -1 on one axis is as close as file 0: its center chunk (-16) mirrors file 0's
        // (16) around the boundary chunk the plan sits on.
        assertEquals(16, chebyshev(0, -1, 0, 0));
    }

    @Test
    void chebyshevToMcaIsSymmetricAcrossNegativeFiles() {
        // Chunk (0,0) is file 0's first chunk, so files 0 and -1 are exactly as far away -
        // every file center chunk sits at 32k+16, mirroring around the nearest pair.
        assertEquals(16, chebyshev(0, 0, 0, 0));
        assertEquals(16, chebyshev(-1, 0, 0, 0));
        assertEquals(48, chebyshev(1, 0, 0, 0));
        assertEquals(48, chebyshev(-2, 0, 0, 0));
        assertEquals(80, chebyshev(2, 0, 0, 0));
        assertEquals(80, chebyshev(-3, 0, 0, 0));
        // Around a file center chunk the mirror is exact in both directions and both axes.
        assertEquals(0, chebyshev(0, 0, 16, 16));
        assertEquals(32, chebyshev(1, 0, 16, 16));
        assertEquals(32, chebyshev(-1, 0, 16, 16));
        assertEquals(64, chebyshev(2, 0, 16, 16));
        assertEquals(64, chebyshev(-2, 0, 16, 16));
        assertEquals(32, chebyshev(0, 1, 16, 16));
        assertEquals(32, chebyshev(0, -1, 16, 16));
    }

    @Test
    void packKeysRoundTripThroughTheConsumersUnpack() {
        assertEquals(0L, pack(0, 0));
        assertEquals(1L << 32, pack(1, 0));
        assertEquals(0xFFFFFFFF00000000L, pack(-1, 0));
        assertEquals(0x00000000FFFFFFFFL, pack(0, -1));
        assertEquals(0x00000001FFFFFFFFL, pack(1, -1));
        assertEquals(0xFFFFFFFF00000001L, pack(-1, 1));
        // The decode used by the mtime index and scanFile's region grouping relies on this
        // exact layout, including the unsigned low half for negative z.
        final int[] xs = {Integer.MIN_VALUE, -33, -1, 0, 1, 32, Integer.MAX_VALUE};
        final int[] zs = {Integer.MIN_VALUE, -65, -2, 0, 7, 96, Integer.MAX_VALUE};
        final Set<Long> keys = new HashSet<>();
        for (final int x : xs) {
            for (final int z : zs) {
                final long key = pack(x, z);
                assertEquals(x, (int) (key >> 32), () -> "x round trip failed for " + x + "," + z);
                assertEquals(z, (int) (key & 0xFFFFFFFFL), () -> "z round trip failed for " + x + "," + z);
                keys.add(key);
            }
        }
        // Distinct files always map to distinct keys.
        assertEquals(xs.length * zs.length, keys.size());
        assertNotEquals(pack(0, 0), pack(0, 1));
        assertNotEquals(pack(0, 0), pack(1, 0));
    }

    private static SingleplayerMcaRenderService.McaRange range(final int centerX, final int centerZ, final int radius) {
        return SingleplayerMcaRenderService.mcaRange(centerX, centerZ, radius);
    }

    private static int chebyshev(final int mcaX, final int mcaZ, final int centerChunkX, final int centerChunkZ) {
        try {
            return (Integer) CHEBYSHEV_TO_MCA.invoke(null, mcaX, mcaZ, centerChunkX, centerChunkZ);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("chebyshevToMca is not reflectively reachable", e);
        }
    }

    private static long pack(final int x, final int z) {
        try {
            return (Long) PACK.invoke(null, x, z);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("pack is not reflectively reachable", e);
        }
    }
}

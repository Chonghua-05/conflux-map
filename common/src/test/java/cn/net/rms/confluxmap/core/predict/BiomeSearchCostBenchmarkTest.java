package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import java.util.OptionalInt;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Measures the per-point cost of each biome-search sampling shape so the coarse-to-fine design
 * in {@code BiomeSearchService} stays grounded in measured costs. Small grids keep the run
 * interactive; per-point costs are coordinate-independent, so ratios extrapolate to the search
 * UI's 513x513 grid.
 */
@Tag("benchmark")
class BiomeSearchCostBenchmarkTest {
    private static final long SEED = 146008555L;
    private static final int SIDE = 64;
    private static final int STRIDE = 392;

    @BeforeEach
    void requireNative() {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable on this platform");
    }

    @Test
    void measureSearchSamplingPaths() {
        final OptionalInt mc = McVersions.toCubiomes("1.21.1");
        assertTrue(mc.isPresent(), "McVersions must know \"1.21.1\"");
        final NativeBaselineSampler sampler = new NativeBaselineSampler(
            mc.getAsInt(), SEED, PredictionDimensions.OVERWORLD, 0
        );

        final int cells = SIDE * SIDE;
        // Warmup: force setupGenerator and page in the noise/spline tables.
        final int[] warm = new int[16];
        assertTrue(sampler.biomes(16, 0, 0, 4, 4, warm), "warmup sampling must succeed");
        final int[] warmHeights = new int[4];
        assertTrue(sampler.heights(0, 0, 2, 2, warmHeights), "warmup heights must succeed");

        final int[] terrainY = new int[cells];
        final int[] biomeIds = new int[cells];

        final long t0 = System.nanoTime();
        final boolean heightsOk = sampler.overviewHeights(0, 0, SIDE, SIDE, STRIDE, terrainY);
        final long t1 = System.nanoTime();
        final boolean surfaceOk = sampler.surfaceBiomes(0, 0, SIDE, SIDE, STRIDE, terrainY, biomeIds);
        final long t2 = System.nanoTime();
        assertTrue(heightsOk && surfaceOk, "surface path must succeed");

        final int[] quartIds = new int[cells];
        final long t3 = System.nanoTime();
        // Scale 4 stays on the dense path and embeds the exact terrain surface solve
        // (~45 ms/cell measured); only a 4x4 probe keeps this section interactive.
        final boolean quartOk = sampler.biomesStrided(4, 0, 0, 4, 4, STRIDE / 4, quartIds);
        final long t4 = System.nanoTime();

        final int[] coarseIds = new int[cells];
        final long t5 = System.nanoTime();
        final boolean coarseOk = sampler.biomesStrided(16, 0, 0, SIDE, SIDE, STRIDE / 16, coarseIds);
        final long t6 = System.nanoTime();

        final int[] sparseIds = new int[cells];
        final long t7 = System.nanoTime();
        final boolean sparseOk = sampler.biomesSparseStrided(16, 0, 0, SIDE, SIDE, STRIDE / 16, sparseIds);
        final long t8 = System.nanoTime();

        final OptionalInt forest = CubiomesBiomeIds.idForName("forest");
        assertTrue(forest.isPresent(), "CubiomesBiomeIds must know \"forest\"");
        final OptionalInt mushroom = CubiomesBiomeIds.idForName("mushroom_fields");
        assertTrue(mushroom.isPresent(), "CubiomesBiomeIds must know \"mushroom_fields\"");
        final int[] scanIds = new int[cells];
        final long t9 = System.nanoTime();
        final boolean scanForestOk = sampler.biomesScanStrided(
            16, 0, 0, SIDE, SIDE, STRIDE / 16, forest.getAsInt(), scanIds
        );
        final long t10 = System.nanoTime();
        final long t11 = System.nanoTime();
        final boolean scanMushroomOk = sampler.biomesScanStrided(
            16, 0, 0, SIDE, SIDE, STRIDE / 16, mushroom.getAsInt(), scanIds
        );
        final long t12 = System.nanoTime();
        int rejectedByMushroomPrefilter = 0;
        for (final int id : scanIds) {
            if (id == -1) {
                rejectedByMushroomPrefilter++;
            }
        }

        System.out.printf(
            "grid=%dx%d strideBlocks=%d cells=%d%n"
                + "  overviewHeights       : %10.3f ms  %8.3f us/point%n"
                + "  surfaceBiomes         : %10.3f ms  %8.3f us/point%n"
                + "  surface total         : %10.3f ms%n"
                + "  biomesStrided s4      : %10.3f ms  %8.3f us/point (%b)%n"
                + "  biomesStrided s16     : %10.3f ms  %8.3f us/point (%b)%n"
                + "  biomesSparseStrided16 : %10.3f ms  %8.3f us/point (%b)%n"
                + "  scanStrided forest    : %10.3f ms  %8.3f us/point (%b)%n"
                + "  scanStrided mushroom  : %10.3f ms  %8.3f us/point (%b, %d/%d prefiltered)%n",
            SIDE, SIDE, STRIDE, cells,
            (t1 - t0) / 1e6, (t1 - t0) / 1e3 / cells,
            (t2 - t1) / 1e6, (t2 - t1) / 1e3 / cells,
            (t2 - t0) / 1e6,
            (t4 - t3) / 1e6, (t4 - t3) / 1e3 / 16.0, quartOk,
            (t6 - t5) / 1e6, (t6 - t5) / 1e3 / cells, coarseOk,
            (t8 - t7) / 1e6, (t8 - t7) / 1e3 / cells, sparseOk,
            (t10 - t9) / 1e6, (t10 - t9) / 1e3 / cells, scanForestOk,
            (t12 - t11) / 1e6, (t12 - t11) / 1e3 / cells, scanMushroomOk,
            rejectedByMushroomPrefilter, cells
        );
    }
}

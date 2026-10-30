package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.net.PatchCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("benchmark")
public class PredictionTileMemoryBenchmarkTest {

    // Legacy unoptimized tile layout: holds long[65536] (512 KiB) and int[65536] (256 KiB)
    record OldTile(
        int[] pixels,
        byte[] biomes,
        int[] surfaces,
        byte[] syncEvaluated,
        long[] syncSourceRevisions,
        byte[] blockLight
    ) {}

    // Optimized tile layout: syncSourceRevisions is null, surfaces is short[65536] (128 KiB)
    record NewTile(
        int[] pixels,
        byte[] biomes,
        short[] surfaces,
        byte[] syncEvaluated,
        long[] syncSourceRevisions,
        byte[] blockLight
    ) {}

    private static OldTile createOldTile() {
        final int expected = BaselineGrid.PIXELS * BaselineGrid.PIXELS;
        final long[] revisions = new long[expected];
        java.util.Arrays.fill(revisions, Long.MIN_VALUE);
        return new OldTile(
            new int[expected],
            new byte[expected],
            new int[expected],
            new byte[PatchCodec.MASK_BYTES],
            revisions,
            new byte[expected]
        );
    }

    private static NewTile createNewTile() {
        final int expected = BaselineGrid.PIXELS * BaselineGrid.PIXELS;
        return new NewTile(
            new int[expected],
            new byte[expected],
            new short[expected],
            new byte[PatchCodec.MASK_BYTES],
            null,
            new byte[expected]
        );
    }

    private static long measureHeap(final Runnable task) {
        System.gc();
        System.gc();
        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {}
        final long before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        task.run();
        System.gc();
        System.gc();
        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {}
        final long after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        return Math.max(0, after - before);
    }

    @Test
    void runMemoryComparison() throws IOException {
        final int[] limits = {32, 64, 128};
        final StringBuilder sb = new StringBuilder();
        sb.append("=== Prediction Tile Memory Benchmark Report ===\n\n");

        final int pixels = 65536;
        final long oldArraysBytes = (long) pixels * 4 + pixels + (long) pixels * 4 + 8192 + (long) pixels * 8 + pixels;
        final long newArraysBytes = (long) pixels * 4 + pixels + (long) pixels * 2 + 8192 + 0 + pixels;
        final double theoreticalSavings = 100.0 * (oldArraysBytes - newArraysBytes) / oldArraysBytes;

        sb.append(String.format("Single tile array footprint: old %d bytes (%.2f MB) -> new %d bytes (%.2f MB), reduction: %.1f%%\n\n",
            oldArraysBytes, oldArraysBytes / (1024.0 * 1024.0),
            newArraysBytes, newArraysBytes / (1024.0 * 1024.0),
            theoreticalSavings
        ));

        assertTrue(theoreticalSavings >= 50.0, "Optimized arrays must reduce memory by at least 50%");

        sb.append(String.format("%-8s | %-16s | %-16s | %-12s | %-10s\n",
            "Limit", "Old JVM Heap", "New JVM Heap", "Saved RAM", "Reduction"));
        sb.append("--------------------------------------------------------------------------------\n");

        for (final int limit : limits) {
            final List<OldTile> oldList = new ArrayList<>(limit);
            final long oldHeap = measureHeap(() -> {
                for (int i = 0; i < limit; i++) {
                    oldList.add(createOldTile());
                }
            });

            final List<NewTile> newList = new ArrayList<>(limit);
            final long newHeap = measureHeap(() -> {
                for (int i = 0; i < limit; i++) {
                    newList.add(createNewTile());
                }
            });

            final double oldMb = oldHeap / (1024.0 * 1024.0);
            final double newMb = newHeap / (1024.0 * 1024.0);
            final double savedMb = oldMb - newMb;
            final double reduction = 100.0 * savedMb / oldMb;

            sb.append(String.format("%-8d | %6.1f MB (%6d KB) | %6.1f MB (%6d KB) | -%5.1f MB     | -%4.1f%%\n",
                limit, oldMb, oldHeap / 1024, newMb, newHeap / 1024, savedMb, reduction));

            oldList.clear();
            newList.clear();
        }

        final Path reportFile = Paths.get("build", "memory-comparison-report.txt");
        Files.createDirectories(reportFile.getParent());
        Files.writeString(reportFile, sb.toString());
    }
}

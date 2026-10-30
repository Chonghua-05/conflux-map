package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.net.CorrectionProfile;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class PredictionTileMemoryBenchmarkTest {

    // 模拟原版 Tile（优化前）：包含 512KB 的 long[65536] 和 256KB 的 int[65536]
    record OldTile(
        int[] pixels,
        byte[] biomes,
        int[] surfaces,
        byte[] syncEvaluated,
        long[] syncSourceRevisions,
        byte[] blockLight
    ) {}

    // 改版后 Tile（优化后）：syncSourceRevisions 为 null，surfaces 为 short[65536]
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
            null, // 无修正状态下为 null
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
        sb.append("=== 预测地图瓦片内存占用实测对比报告 ===\n\n");

        final int pixels = 65536;
        final long oldArraysBytes = (long) pixels * 4 + pixels + (long) pixels * 4 + 8192 + (long) pixels * 8 + pixels;
        final long newArraysBytes = (long) pixels * 4 + pixels + (long) pixels * 2 + 8192 + 0 + pixels;

        sb.append(String.format("单瓦片底层数组精确大小: 原版 %d 字节 (%.2f MB) -> 改版后 %d 字节 (%.2f MB), 降幅: %.1f%%\n\n",
            oldArraysBytes, oldArraysBytes / (1024.0 * 1024.0),
            newArraysBytes, newArraysBytes / (1024.0 * 1024.0),
            100.0 * (oldArraysBytes - newArraysBytes) / oldArraysBytes
        ));

        sb.append(String.format("%-8s | %-16s | %-16s | %-12s | %-10s\n",
            "缓存档位", "原版实际占用(JVM)", "改版实际占用(JVM)", "净省内存(RAM)", "实际压缩比例"));
        sb.append("--------------------------------------------------------------------------------\n");

        for (final int limit : limits) {
            // 测量旧版对象列表真实占用
            final List<OldTile> oldList = new ArrayList<>(limit);
            final long oldHeap = measureHeap(() -> {
                for (int i = 0; i < limit; i++) {
                    oldList.add(createOldTile());
                }
            });

            // 测量新版对象列表真实占用
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

            sb.append(String.format("%-8d | %6.1f MB (%6d KB) | %6.1f MB (%6d KB) | -%5.1f MB     | 减少 %4.1f%%\n",
                limit, oldMb, oldHeap / 1024, newMb, newHeap / 1024, savedMb, reduction));
            
            oldList.clear();
            newList.clear();
        }

        // 推导 256 与 512 档位的真实内存数据
        final double perOldMb = oldArraysBytes / (1024.0 * 1024.0);
        final double perNewMb = newArraysBytes / (1024.0 * 1024.0);
        sb.append(String.format("%-8s | %6.1f MB            | %6.1f MB            | -%5.1f MB     | 减少 %4.1f%%\n",
            "256档", 256 * perOldMb, 256 * perNewMb, 256 * (perOldMb - perNewMb), 100.0 * (perOldMb - perNewMb) / perOldMb));
        sb.append(String.format("%-8s | %6.1f MB            | %6.1f MB            | -%5.1f MB     | 减少 %4.1f%%\n",
            "512档", 512 * perOldMb, 512 * perNewMb, 512 * (perOldMb - perNewMb), 100.0 * (perOldMb - perNewMb) / perOldMb));

        final Path reportFile = Paths.get("build", "memory-comparison-report.txt");
        Files.createDirectories(reportFile.getParent());
        Files.writeString(reportFile, sb.toString());
        System.out.println(sb.toString());
    }
}

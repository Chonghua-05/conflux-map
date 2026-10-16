package cn.net.rms.confluxmap.core.net;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Tracks full-sync progress and emits periodic (every 5 seconds) feedback
 * and completion summaries. Decoupled from Minecraft UI and network IO for testability.
 */
public final class FullSyncTracker {
    public static final long FEEDBACK_INTERVAL_NANOS = 5_000_000_000L; // 5 seconds

    private boolean running;
    private int total;
    private int completed;
    private long trafficBytes;
    private long startedNanos;
    private long lastFeedbackNanos;
    private final Consumer<String> feedbackSink;

    public FullSyncTracker(final Consumer<String> feedbackSink) {
        this.feedbackSink = feedbackSink == null ? ignored -> { } : feedbackSink;
    }

    public synchronized void start(final int totalRegions, final long nowNanos) {
        running = true;
        total = totalRegions;
        completed = 0;
        trafficBytes = 0;
        startedNanos = nowNanos;
        lastFeedbackNanos = nowNanos;

        feedbackSink.accept(String.format(
            "[ConfluxMap] 开始全量同步：已扫描到 %d 个区域，正在接收数据...", total
        ));
        if (total == 0) {
            running = false;
            feedbackSink.accept("[ConfluxMap] 当前维度无已生成的区域数据。");
        }
    }

    public synchronized void recordProgress(final int payloadBytes, final long nowNanos) {
        if (!running) {
            return;
        }
        trafficBytes += Math.max(0, payloadBytes);
        completed++;

        if (completed >= total) {
            running = false;
            final double durationSec = (nowNanos - startedNanos) / 1_000_000_000.0;
            feedbackSink.accept(String.format(
                Locale.ROOT,
                "[ConfluxMap] 全量同步已完成：共同步 %d 个区域，耗时 %.2f 秒，总流量 %.2f MiB。",
                total, durationSec, trafficBytes / (1024.0 * 1024.0)
            ));
            return;
        }

        if (nowNanos - lastFeedbackNanos >= FEEDBACK_INTERVAL_NANOS) {
            lastFeedbackNanos = nowNanos;
            final double percent = (double) completed / total * 100.0;
            final double elapsedSec = (nowNanos - startedNanos) / 1_000_000_000.0;
            feedbackSink.accept(String.format(
                Locale.ROOT,
                "[ConfluxMap] 全量同步进度：%d/%d (%.1f%%)，耗时 %.1f 秒，流量 %.2f MiB...",
                completed, total, percent, elapsedSec, trafficBytes / (1024.0 * 1024.0)
            ));
        }
    }

    public synchronized void cancel() {
        if (running) {
            running = false;
            feedbackSink.accept("[ConfluxMap] 全量同步已取消。");
        }
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized int total() {
        return total;
    }

    public synchronized int completed() {
        return completed;
    }
}

package cn.net.rms.confluxmap.core.net;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Tracks full-sync progress and emits periodic (every 5 seconds) feedback and completion
 * summaries as translatable {@link Feedback} events; the platform boundary resolves the
 * key against its language files, keeping this class free of user-facing text. All numeric
 * args are pre-formatted with {@link Locale#ROOT} so every locale renders the same digits.
 */
public final class FullSyncTracker {
    public static final long FEEDBACK_INTERVAL_NANOS = 5_000_000_000L;
    /** A sync silent for this long is stalled; the owner cancels it with a stall notice. */
    public static final long STALL_TIMEOUT_NANOS = 60_000_000_000L;

    /** Translation key plus pre-formatted args for one chat line. */
    public record Feedback(String key, Object... args) {
    }

    private final Consumer<Feedback> feedbackSink;
    private boolean running;
    private int total;
    private int completed;
    private int failed;
    private long trafficBytes;
    private long startedNanos;
    private long lastFeedbackNanos;
    private long lastActivityNanos;

    public FullSyncTracker(final Consumer<Feedback> feedbackSink) {
        this.feedbackSink = feedbackSink == null ? ignored -> { } : feedbackSink;
    }

    public synchronized void start(final int totalRegions, final long nowNanos) {
        running = totalRegions > 0;
        total = totalRegions;
        completed = 0;
        failed = 0;
        trafficBytes = 0;
        startedNanos = nowNanos;
        lastFeedbackNanos = nowNanos;
        lastActivityNanos = nowNanos;
        if (!running) {
            feedbackSink.accept(new Feedback("confluxmap.fullsync.empty"));
            return;
        }
        feedbackSink.accept(new Feedback("confluxmap.fullsync.started", Integer.toString(total)));
    }

    public synchronized void recordProgress(final int payloadBytes, final long nowNanos) {
        if (!running) {
            return;
        }
        trafficBytes += Math.max(0, payloadBytes);
        completed++;
        lastActivityNanos = nowNanos;
        if (completed + failed >= total) {
            finish(nowNanos);
            return;
        }
        if (nowNanos - lastFeedbackNanos >= FEEDBACK_INTERVAL_NANOS) {
            lastFeedbackNanos = nowNanos;
            feedbackSink.accept(new Feedback(
                "confluxmap.fullsync.progress",
                Integer.toString(completed),
                Integer.toString(total),
                percent(completed, total),
                seconds(nowNanos - startedNanos, 1),
                mib(trafficBytes)
            ));
        }
    }

    /** A region that exhausted its retries; it is skipped, not retried again. */
    public synchronized void noteFailure(final long nowNanos) {
        if (!running) {
            return;
        }
        failed++;
        lastActivityNanos = nowNanos;
        if (completed + failed >= total) {
            finish(nowNanos);
        }
    }

    public synchronized void cancel(final long nowNanos) {
        if (running) {
            running = false;
            feedbackSink.accept(new Feedback("confluxmap.fullsync.cancelled"));
        }
    }

    /** Stops without any feedback; used for disconnects and superseded syncs. */
    public synchronized void abandon() {
        running = false;
    }

    /** Stops a sync that made no progress for {@link #STALL_TIMEOUT_NANOS}. */
    public synchronized void failStalled(final long nowNanos) {
        if (running) {
            running = false;
            feedbackSink.accept(new Feedback("confluxmap.fullsync.stalled"));
        }
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized boolean isStalled(final long nowNanos) {
        return running && nowNanos - lastActivityNanos >= STALL_TIMEOUT_NANOS;
    }

    public synchronized int total() {
        return total;
    }

    public synchronized int completed() {
        return completed;
    }

    private void finish(final long nowNanos) {
        running = false;
        if (failed > 0) {
            feedbackSink.accept(new Feedback(
                "confluxmap.fullsync.done_skipped",
                Integer.toString(completed),
                Integer.toString(total),
                Integer.toString(failed)
            ));
            return;
        }
        feedbackSink.accept(new Feedback(
            "confluxmap.fullsync.done",
            Integer.toString(total),
            seconds(nowNanos - startedNanos, 2),
            mib(trafficBytes)
        ));
    }

    private static String percent(final int completed, final int total) {
        return String.format(Locale.ROOT, "%.1f", completed * 100.0 / total);
    }

    private static String seconds(final long nanos, final int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", nanos / 1_000_000_000.0);
    }

    private static String mib(final long bytes) {
        return String.format(Locale.ROOT, "%.2f", bytes / (1024.0 * 1024.0));
    }
}

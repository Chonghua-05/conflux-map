package cn.net.rms.confluxmap.core.task;

import java.util.function.LongSupplier;

/**
 * Bounds one client tick's synchronous visible-terrain sampling. The wall-clock slice scales
 * with the configured per-tick budget (clamped to {@link #MIN_WINDOW_NANOS}..
 * {@link #MAX_WINDOW_NANOS}, 12ms at the default of 8) so the budget controls real capture
 * throughput, while the unconditional floor stays capped so a high budget can never force a
 * slow tick deep past the window.
 */
public final class CaptureTickBudget {
    static final long REFERENCE_WINDOW_NANOS = 12_000_000L;
    static final int REFERENCE_SNAPSHOTS = 8;
    static final long MIN_WINDOW_NANOS = 1_500_000L;
    static final long MAX_WINDOW_NANOS = 24_000_000L;
    static final int MAX_UNCONDITIONAL_SNAPSHOTS = 8;
    static final int MAX_VISIBLE_SNAPSHOTS = 256;

    private final int minimumSnapshots;
    private final int maximumSnapshots;
    private final long deadlineNanos;
    private final LongSupplier nanoTime;

    private CaptureTickBudget(
        final int minimumSnapshots,
        final int maximumSnapshots,
        final long deadlineNanos,
        final LongSupplier nanoTime
    ) {
        this.minimumSnapshots = minimumSnapshots;
        this.maximumSnapshots = maximumSnapshots;
        this.deadlineNanos = deadlineNanos;
        this.nanoTime = nanoTime;
    }

    public static CaptureTickBudget visible(
        final int configuredSnapshots, final LongSupplier nanoTime
    ) {
        final int minimum = Math.min(
            MAX_UNCONDITIONAL_SNAPSHOTS, Math.max(1, configuredSnapshots)
        );
        final long started = nanoTime.getAsLong();
        return new CaptureTickBudget(
            minimum,
            MAX_VISIBLE_SNAPSHOTS,
            started + windowNanos(configuredSnapshots),
            nanoTime
        );
    }

    /**
     * The slice saturates at {@link #MAX_WINDOW_NANOS} rather than growing with extreme budgets,
     * because the whole slice is client-main-thread time.
     */
    static long windowNanos(final int configuredSnapshots) {
        final long scaled = REFERENCE_WINDOW_NANOS
            * Math.max(1, configuredSnapshots) / REFERENCE_SNAPSHOTS;
        return Math.min(MAX_WINDOW_NANOS, Math.max(MIN_WINDOW_NANOS, scaled));
    }

    public boolean canCapture(final int capturedSnapshots) {
        return canCapture(capturedSnapshots, false);
    }

    public boolean canCapture(
        final int capturedSnapshots, final boolean tickAlreadyWorked
    ) {
        final int minimum = tickAlreadyWorked ? 0 : minimumSnapshots;
        return capturedSnapshots < maximumSnapshots
            && (capturedSnapshots < minimum || nanoTime.getAsLong() < deadlineNanos);
    }

    public boolean canFinish(final int finishedResults) {
        return finishedResults < maximumSnapshots
            && (finishedResults == 0 || nanoTime.getAsLong() < deadlineNanos);
    }

    /** Largest candidate batch worth sorting/draining for this tick. */
    public int maximumCandidates() {
        return maximumSnapshots;
    }
}

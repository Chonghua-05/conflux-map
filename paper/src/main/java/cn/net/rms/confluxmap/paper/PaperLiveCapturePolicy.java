package cn.net.rms.confluxmap.paper;


/**
 * Pure decisions and budgets for the Paper live-chunk capture pipeline. Paper has no
 * block-dirty signal (the adapter is pure Bukkit API), so captures are demand-gated and
 * TTL-paced instead of event-driven, and only the snapshot acquisition stays on the main
 * thread while the summary is computed on a worker.
 */
final class PaperLiveCapturePolicy {
    /** Freshness target for recaptures: a watched chunk is refreshed at most this often. */
    static final int RECAPTURE_INTERVAL_TICKS = 100;

    /** Hard cap on snapshots handed to workers before backpressure skips further captures. */
    static final int MAX_IN_FLIGHT_CAPTURES = 64;

    /** Nanosecond budget for snapshot acquisition within one tick. */
    static final long SNAPSHOT_BUDGET_NANOS = 4_000_000L;

    private PaperLiveCapturePolicy() {
    }

    /**
     * Rotating-scan decision: first captures go through immediately, recaptures only once the
     * freshness interval has elapsed. {@code ticksSinceCapture} of {@link Long#MAX_VALUE}
     * (no bookkeeping for this session) always recaptures, so a summary that lost its tick
     * record still refreshes.
     */
    static boolean shouldScanCapture(final boolean summaryPresent, final long ticksSinceCapture) {
        if (!summaryPresent) {
            return true;
        }
        return ticksSinceCapture >= RECAPTURE_INTERVAL_TICKS;
    }
}

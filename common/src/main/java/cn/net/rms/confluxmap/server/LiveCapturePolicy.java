package cn.net.rms.confluxmap.server;

/**
 * Shared bounds for demand-gated live-chunk summary capture. The per-tick cap converts the
 * configured per-second budget at the vanilla tick rate (20/s) with round-up, floored at one and
 * ceilinged at the inspection bound, so a raised configured budget always yields more captures
 * while a single tick's cost stays bounded.
 */
public final class LiveCapturePolicy {
    /** Ceiling on live summaries captured per tick, whatever the configured rate. */
    public static final int MAX_LIVE_SUMMARIES_PER_TICK = 8;

    /** Ceiling on loaded-chunk inspections per capture pass. */
    public static final int MAX_LIVE_INSPECTIONS_PER_TICK = 128;

    private LiveCapturePolicy() {
    }

    /** Per-tick capture allowance derived from {@code configuredPerSecond}. */
    public static int perTickCaptures(final int configuredPerSecond) {
        final int configuredPerTick = Math.max(1, (configuredPerSecond + 19) / 20);
        return Math.min(MAX_LIVE_SUMMARIES_PER_TICK, configuredPerTick);
    }
}

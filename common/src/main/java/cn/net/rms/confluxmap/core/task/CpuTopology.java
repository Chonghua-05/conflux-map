package cn.net.rms.confluxmap.core.task;

/**
 * Snapshot of the host CPU topology as far as it matters for worker-pool sizing:
 * how many logical processors the JVM can use, and whether they split into a
 * faster performance tier and a slower efficiency tier (Intel P/E cores, AMD
 * dual-CCD, ARM big.LITTLE). Detection is best-effort; any unknown collapses to
 * a uniform topology, which simply sizes the pool from the processor count.
 *
 * <p>{@code logicalProcessors} is always the JVM's own view
 * ({@code Runtime.availableProcessors()}, so affinity and cgroup limits are
 * respected), while the performance-processor count comes from the OS probe and
 * may cover the whole machine. Pool sizing clamps the result, so a restricted
 * process on a hybrid machine still gets a sane count.
 */
public final class CpuTopology {
    private final int logicalProcessors;
    /** Count of logical processors in the fastest tier; meaningless unless {@link #hybrid()}. */
    private final int performanceProcessors;
    private final String description;

    private CpuTopology(final int logicalProcessors, final int performanceProcessors, final String description) {
        this.logicalProcessors = Math.max(1, logicalProcessors);
        this.performanceProcessors = performanceProcessors;
        this.description = description;
    }

    /**
     * A CPU whose logical processors form one tier (or whose tiers could not be
     * detected); {@code description} should say which of the two it is, for the
     * startup log.
     */
    public static CpuTopology uniform(final int logicalProcessors, final String description) {
        return new CpuTopology(logicalProcessors, -1, description);
    }

    /**
     * A hybrid CPU. A performance count that covers all logical processors (or
     * none) is not a hybrid, and collapses to uniform so callers never see an
     * inconsistent snapshot.
     */
    public static CpuTopology hybrid(
        final int logicalProcessors,
        final int performanceProcessors,
        final String description
    ) {
        if (performanceProcessors <= 0 || performanceProcessors >= logicalProcessors) {
            return uniform(logicalProcessors, description);
        }
        return new CpuTopology(logicalProcessors, performanceProcessors, description);
    }

    public int logicalProcessors() {
        return logicalProcessors;
    }

    /** Logical processors in the performance tier; only meaningful when {@link #hybrid()}. */
    public int performanceProcessors() {
        return performanceProcessors;
    }

    public boolean hybrid() {
        return performanceProcessors > 0;
    }

    /** Human-readable provenance for the startup log, e.g. "8P+8E (sysfs cpuinfo_max_freq)". */
    public String description() {
        return description;
    }
}

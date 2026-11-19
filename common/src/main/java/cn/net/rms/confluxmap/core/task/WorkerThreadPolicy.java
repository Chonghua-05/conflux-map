package cn.net.rms.confluxmap.core.task;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.config.ConfluxConfig.WorkerThreadMode;

/**
 * Decides the shared worker-pool size from the configured mode and the detected
 * CPU topology. The pool backs every background pipeline of the mod (tile
 * composition, prediction, region cache, waypoint/portal saves, entity icons),
 * so the AUTO defaults reserve headroom for the game's own main and render
 * threads: on a hybrid CPU the pool is sized from the performance cores only
 * (efficiency cores would throttle the whole pool), and on a uniform CPU from
 * all logical processors minus that same reservation.
 */
public final class WorkerThreadPolicy {
    /** AUTO ceiling on hybrid CPUs; a larger P-core count keeps this many workers at most. */
    public static final int HYBRID_AUTO_CAP = 12;
    /** AUTO ceiling on uniform CPUs. */
    public static final int UNIFORM_AUTO_CAP = 16;
    /** Logical processors AUTO leaves to the game's main and render threads. */
    public static final int AUTO_RESERVED_THREADS = 2;

    private WorkerThreadPolicy() {
    }

    /** The worker-pool size for the given mode; every result is at least one thread. */
    public static int resolve(final WorkerThreadMode mode, final int customCount, final CpuTopology topology) {
        return switch (mode) {
            case ALL_THREADS -> clamp(
                topology.logicalProcessors(), 1, ConfluxConfig.MAX_WORKER_THREADS
            );
            case CUSTOM -> clamp(
                customCount, ConfluxConfig.MIN_WORKER_THREADS, ConfluxConfig.MAX_WORKER_THREADS
            );
            case AUTO -> auto(topology);
        };
    }

    private static int auto(final CpuTopology topology) {
        if (topology.hybrid()) {
            return clamp(
                topology.performanceProcessors() - AUTO_RESERVED_THREADS,
                1,
                HYBRID_AUTO_CAP
            );
        }
        return clamp(
            topology.logicalProcessors() - AUTO_RESERVED_THREADS,
            1,
            UNIFORM_AUTO_CAP
        );
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }
}

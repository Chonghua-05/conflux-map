package cn.net.rms.confluxmap.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.config.ConfluxConfig.WorkerThreadMode;
import org.junit.jupiter.api.Test;

final class WorkerThreadPolicyTest {
    @Test
    void autoSizesHybridCpuFromPerformanceCoresOnly() {
        // 8P+8E: the pool takes the performance cores minus the game-thread reservation.
        assertEquals(
            6,
            WorkerThreadPolicy.resolve(WorkerThreadMode.AUTO, 0, CpuTopology.hybrid(16, 8, "test"))
        );
    }

    @Test
    void autoCapsHybridCpuAtTwelveWorkers() {
        assertEquals(
            WorkerThreadPolicy.HYBRID_AUTO_CAP,
            WorkerThreadPolicy.resolve(WorkerThreadMode.AUTO, 0, CpuTopology.hybrid(32, 24, "test"))
        );
    }

    @Test
    void autoNeverDropsBelowOneWorkerOnHybridCpu() {
        assertEquals(
            1,
            WorkerThreadPolicy.resolve(WorkerThreadMode.AUTO, 0, CpuTopology.hybrid(8, 2, "test"))
        );
    }

    @Test
    void autoSizesUniformCpuFromAllLogicalProcessors() {
        assertEquals(
            14,
            WorkerThreadPolicy.resolve(WorkerThreadMode.AUTO, 0, CpuTopology.uniform(16, "test"))
        );
    }

    @Test
    void autoCapsUniformCpuAtSixteenWorkers() {
        assertEquals(
            WorkerThreadPolicy.UNIFORM_AUTO_CAP,
            WorkerThreadPolicy.resolve(WorkerThreadMode.AUTO, 0, CpuTopology.uniform(64, "test"))
        );
    }

    @Test
    void autoNeverDropsBelowOneWorkerOnUniformCpu() {
        assertEquals(
            1,
            WorkerThreadPolicy.resolve(WorkerThreadMode.AUTO, 0, CpuTopology.uniform(1, "test"))
        );
    }

    @Test
    void allThreadsUsesEveryLogicalProcessorUpToTheConfiguredCeiling() {
        assertEquals(
            8,
            WorkerThreadPolicy.resolve(WorkerThreadMode.ALL_THREADS, 0, CpuTopology.uniform(8, "test"))
        );
        assertEquals(
            ConfluxConfig.MAX_WORKER_THREADS,
            WorkerThreadPolicy.resolve(WorkerThreadMode.ALL_THREADS, 0, CpuTopology.uniform(64, "test"))
        );
    }

    @Test
    void customCountIsClampedToTheConfiguredRange() {
        assertEquals(
            7,
            WorkerThreadPolicy.resolve(WorkerThreadMode.CUSTOM, 7, CpuTopology.uniform(16, "test"))
        );
        assertEquals(
            ConfluxConfig.MIN_WORKER_THREADS,
            WorkerThreadPolicy.resolve(WorkerThreadMode.CUSTOM, 0, CpuTopology.uniform(16, "test"))
        );
        assertEquals(
            ConfluxConfig.MAX_WORKER_THREADS,
            WorkerThreadPolicy.resolve(WorkerThreadMode.CUSTOM, 99, CpuTopology.uniform(16, "test"))
        );
    }
}

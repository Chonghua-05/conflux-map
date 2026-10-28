package cn.net.rms.confluxmap.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class CpuTopologyDetectorTest {
    private static final long GHZ = 1_000_000L;

    @Test
    void frequencyGroupsSplitHybridCpuAcrossMultiplePerformanceBins() {
        // A real hybrid desktop: P cores capped at two different bins plus slower E cores.
        final CpuTopology topology = CpuTopologyDetector.fromFrequencyGroups(
            24,
            Map.of(39L * GHZ / 10, 8, 53L * GHZ / 10, 8, 55L * GHZ / 10, 8)
        );

        assertTrue(topology.hybrid());
        assertEquals(16, topology.performanceProcessors());
        assertEquals(24, topology.logicalProcessors());
    }

    @Test
    void singleFrequencyGroupIsUniform() {
        final CpuTopology topology = CpuTopologyDetector.fromFrequencyGroups(
            16, Map.of(55L * GHZ / 10, 16)
        );

        assertFalse(topology.hybrid());
        assertEquals(16, topology.logicalProcessors());
    }

    @Test
    void tinyFrequencySpreadIsOneTierNotTwo() {
        // Same-tier cores routinely bin a few hundred MHz apart; that is not a hybrid CPU.
        final CpuTopology topology = CpuTopologyDetector.fromFrequencyGroups(
            16, Map.of(53L * GHZ / 10, 8, 55L * GHZ / 10, 8)
        );

        assertFalse(topology.hybrid());
    }

    @Test
    void missingFrequencyDataIsUniform() {
        final CpuTopology topology = CpuTopologyDetector.fromFrequencyGroups(12, Map.of());

        assertFalse(topology.hybrid());
        assertEquals(12, topology.logicalProcessors());
    }

    @Test
    void efficiencyClassesSplitPerformanceFromEfficiencyCores() {
        final CpuTopology topology = CpuTopologyDetector.fromEfficiencyClasses(
            16, new int[] {1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0}
        );

        assertTrue(topology.hybrid());
        assertEquals(8, topology.performanceProcessors());
        assertEquals(16, topology.logicalProcessors());
    }

    @Test
    void allZeroEfficiencyClassesAreUniform() {
        final CpuTopology topology = CpuTopologyDetector.fromEfficiencyClasses(8, new int[8]);

        assertFalse(topology.hybrid());
    }

    @Test
    void emptyEfficiencyClassesAreUniform() {
        final CpuTopology topology = CpuTopologyDetector.fromEfficiencyClasses(8, new int[0]);

        assertFalse(topology.hybrid());
        assertEquals(8, topology.logicalProcessors());
    }

    @Test
    void hybridWithoutAnEfficiencyTierCollapsesToUniform() {
        // Every logical processor in the performance tier is a uniform CPU by definition.
        final CpuTopology topology = CpuTopology.hybrid(8, 8, "inconsistent");

        assertFalse(topology.hybrid());
    }

    @Test
    void windowsDispatchReadsEfficiencyClasses() {
        final CpuTopologyDetector detector = new CpuTopologyDetector(
            () -> {
                throw new AssertionError("sysfs must not be read on Windows");
            },
            () -> new int[] {1, 1, 1, 1, 0, 0, 0, 0}
        );

        final CpuTopology topology = detector.detect("Windows 10", 8);

        assertTrue(topology.hybrid());
        assertEquals(4, topology.performanceProcessors());
    }

    @Test
    void linuxDispatchReadsFrequencyGroups() {
        final CpuTopologyDetector detector = new CpuTopologyDetector(
            () -> Map.of(39L * GHZ / 10, 4, 55L * GHZ / 10, 4),
            () -> {
                throw new AssertionError("the native probe must not be called on Linux");
            }
        );

        final CpuTopology topology = detector.detect("Linux", 8);

        assertTrue(topology.hybrid());
        assertEquals(4, topology.performanceProcessors());
    }

    @Test
    void otherOperatingSystemsStayUniformWithoutProbing() {
        final CpuTopologyDetector detector = new CpuTopologyDetector(
            () -> {
                throw new AssertionError("sysfs must not be read on macOS");
            },
            () -> {
                throw new AssertionError("the native probe must not be called on macOS");
            }
        );

        final CpuTopology topology = detector.detect("Mac OS X", 8);

        assertFalse(topology.hybrid());
        assertEquals(8, topology.logicalProcessors());
    }
}

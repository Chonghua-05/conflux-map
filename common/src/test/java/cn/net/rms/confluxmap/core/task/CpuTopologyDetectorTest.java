package cn.net.rms.confluxmap.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
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
    void hybridCoreTypesSplitPerformanceFromEfficiencyCores() {
        // A real hybrid desktop as CPUID labels it: 8 Core logical processors, 12 Atom.
        final int[] coreTypes = new int[20];
        Arrays.fill(coreTypes, 0x20);
        Arrays.fill(coreTypes, 0, 8, 0x40);

        final CpuTopology topology = CpuTopologyDetector.fromHybridCoreTypes(20, coreTypes);

        assertTrue(topology.hybrid());
        assertEquals(8, topology.performanceProcessors());
        assertEquals(20, topology.logicalProcessors());
    }

    @Test
    void oneCoreTypeIsUniform() {
        // P cores binned at different clocks still carry the same 0x40 label, so
        // they are one tier; only a genuine Core/Atom mix is hybrid.
        final CpuTopology topology = CpuTopologyDetector.fromHybridCoreTypes(
            8, new int[] {0x40, 0x40, 0x40, 0x40, 0x40, 0x40, 0x40, 0x40}
        );

        assertFalse(topology.hybrid());
    }

    @Test
    void unknownCoreTypesCountAsEfficiency() {
        // A future part may label a tier this build has never seen; only 0x40 is
        // known to be performance, so an unknown label stays out of the P count.
        final CpuTopology topology = CpuTopologyDetector.fromHybridCoreTypes(
            8, new int[] {0x40, 0x40, 0x30, 0x30, 0x20, 0x20, 0x20, 0x20}
        );

        assertTrue(topology.hybrid());
        assertEquals(2, topology.performanceProcessors());
    }

    @Test
    void windowsDispatchReadsEfficiencyClasses() {
        final CpuTopologyDetector detector = new CpuTopologyDetector(
            () -> {
                throw new AssertionError("sysfs must not be read on Windows");
            },
            () -> new int[] {1, 1, 1, 1, 0, 0, 0, 0},
            () -> {
                throw new AssertionError("the cpuid probe must not be called on Windows");
            }
        );

        final CpuTopology topology = detector.detect("Windows 10", 8);

        assertTrue(topology.hybrid());
        assertEquals(4, topology.performanceProcessors());
    }

    @Test
    void linuxDispatchPrefersCpuidCoreTypesOverSysfs() {
        final int[] coreTypes = new int[20];
        Arrays.fill(coreTypes, 0x20);
        Arrays.fill(coreTypes, 0, 8, 0x40);
        final CpuTopologyDetector detector = new CpuTopologyDetector(
            () -> {
                throw new AssertionError("sysfs must not be read when the CPU labels its cores");
            },
            () -> {
                throw new AssertionError("the windows probe must not be called on Linux");
            },
            () -> coreTypes
        );

        final CpuTopology topology = detector.detect("Linux", 20);

        assertTrue(topology.hybrid());
        assertEquals(8, topology.performanceProcessors());
        assertEquals(20, topology.logicalProcessors());
    }

    @Test
    void linuxFallsBackToSysfsWhenTheCpuLabelsNothing() {
        final CpuTopologyDetector detector = new CpuTopologyDetector(
            () -> Map.of(39L * GHZ / 10, 4, 55L * GHZ / 10, 4),
            () -> {
                throw new AssertionError("the windows probe must not be called on Linux");
            },
            () -> new int[0]
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
                throw new AssertionError("the windows probe must not be called on macOS");
            },
            () -> {
                throw new AssertionError("the cpuid probe must not be called on macOS");
            }
        );

        final CpuTopology topology = detector.detect("Mac OS X", 8);

        assertFalse(topology.hybrid());
        assertEquals(8, topology.logicalProcessors());
    }
}

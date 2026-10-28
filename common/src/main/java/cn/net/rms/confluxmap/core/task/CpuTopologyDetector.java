package cn.net.rms.confluxmap.core.task;

import cn.net.rms.confluxmap.nativepredict.NativeCpuTopology;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Detects {@link CpuTopology} once per client session, before the worker pool is
 * created. Linux reads each {@code /sys/devices/system/cpu/cpu<n>/cpufreq/cpuinfo_max_freq}
 * in pure Java and groups logical processors by maximum frequency; Windows asks the
 * bundled shim library for per-processor efficiency classes via JNI; every other
 * platform (macOS), and every failure along the way, yields a uniform topology so
 * startup is never affected.
 *
 * <p>The two OS probes are interfaces so tests can inject synthetic topologies
 * instead of reading the real sysfs or loading the real native library.
 */
public final class CpuTopologyDetector {
    private static final String SYSFS_CPU_ROOT = "/sys/devices/system/cpu";

    /**
     * Largest tolerated frequency gap, as a fraction of the top frequency, that is
     * still considered one tier: cores of the same tier routinely cap 100-300 MHz
     * apart (e.g. 5.5 and 5.3 GHz P cores), while real P/E separation is a GHz or
     * more. A tier split smaller than this is treated as uniform.
     */
    private static final long MIN_TIER_GAP_DIVIDEND = 10;

    /** Supplies per-max-frequency logical-processor counts (kHz); the real one reads sysfs. */
    public interface FrequencyGroups {
        /** Frequency in kHz to the number of logical processors reporting it; empty when unknown. */
        Map<Long, Integer> frequencyGroupCounts();
    }

    /** Supplies per-logical-processor efficiency classes; the real one calls the native shim. */
    public interface EfficiencyClasses {
        /** One class per logical processor (0 = efficiency, higher = performance); empty when unknown. */
        int[] efficiencyClasses();
    }

    private final FrequencyGroups frequencyGroups;
    private final EfficiencyClasses efficiencyClasses;

    public CpuTopologyDetector(final FrequencyGroups frequencyGroups, final EfficiencyClasses efficiencyClasses) {
        this.frequencyGroups = frequencyGroups;
        this.efficiencyClasses = efficiencyClasses;
    }

    /** Detector wired to the real sysfs reader and the bundled native library. */
    public static CpuTopologyDetector defaults() {
        return new CpuTopologyDetector(CpuTopologyDetector::readSysfsFrequencyGroups, NativeCpuTopology::efficiencyClasses);
    }

    public CpuTopology detect() {
        return detect(System.getProperty("os.name", ""), Runtime.getRuntime().availableProcessors());
    }

    CpuTopology detect(final String osName, final int logicalProcessors) {
        final String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return fromEfficiencyClasses(logicalProcessors, efficiencyClasses.efficiencyClasses());
        }
        if (os.contains("linux")) {
            return fromFrequencyGroups(logicalProcessors, frequencyGroups.frequencyGroupCounts());
        }
        return CpuTopology.uniform(
            logicalProcessors,
            logicalProcessors + " logical threads (no hybrid detection on this OS)"
        );
    }

    /**
     * Groups logical processors by their reported maximum frequency. The performance
     * tier is everything above the largest frequency gap, provided that gap is
     * significant relative to the top frequency; multiple P-core bins therefore merge
     * into one tier (a real machine reports e.g. 5.5 GHz and 5.3 GHz P cores next to
     * 3.9 GHz E cores).
     */
    static CpuTopology fromFrequencyGroups(
        final int logicalProcessors,
        final Map<Long, Integer> frequencyGroupCounts
    ) {
        final TreeMap<Long, Integer> sorted = new TreeMap<>(frequencyGroupCounts);
        if (sorted.size() < 2) {
            final String detail = sorted.isEmpty()
                ? "max frequency unknown"
                : "uniform max frequency " + ghz(sorted.lastKey());
            return CpuTopology.uniform(
                logicalProcessors,
                logicalProcessors + " logical threads (" + detail + ")"
            );
        }
        final long topFrequency = sorted.lastKey();
        long largestGap = 0;
        long tierFloor = topFrequency;
        long previous = sorted.firstKey();
        for (final long frequency : sorted.keySet()) {
            final long gap = frequency - previous;
            if (gap > largestGap) {
                largestGap = gap;
                tierFloor = frequency;
            }
            previous = frequency;
        }
        if (largestGap * MIN_TIER_GAP_DIVIDEND < topFrequency) {
            return CpuTopology.uniform(
                logicalProcessors,
                logicalProcessors + " logical threads (max frequency spread " + ghz(topFrequency) + ")"
            );
        }
        int performance = 0;
        int efficiency = 0;
        for (final Map.Entry<Long, Integer> group : sorted.entrySet()) {
            if (group.getKey() >= tierFloor) {
                performance += group.getValue();
            } else {
                efficiency += group.getValue();
            }
        }
        return CpuTopology.hybrid(
            logicalProcessors,
            performance,
            performance + "P+" + efficiency + "E logical threads (sysfs cpuinfo_max_freq)"
        );
    }

    /** Windows efficiency classes: 0 marks E cores, every higher class a P-core tier. */
    static CpuTopology fromEfficiencyClasses(final int logicalProcessors, final int[] classes) {
        int topClass = -1;
        for (final int clazz : classes) {
            if (clazz > topClass) {
                topClass = clazz;
            }
        }
        if (topClass <= 0) {
            return CpuTopology.uniform(
                logicalProcessors,
                logicalProcessors + " logical threads (windows efficiency classes report one tier)"
            );
        }
        int performance = 0;
        for (final int clazz : classes) {
            if (clazz >= topClass) {
                performance++;
            }
        }
        final int efficiency = classes.length - performance;
        return CpuTopology.hybrid(
            logicalProcessors,
            performance,
            performance + "P+" + efficiency + "E logical threads (windows efficiency classes)"
        );
    }

    /**
     * Reads one {@code cpuinfo_max_freq} (kHz) per {@code cpu<N>} directory. CPUs
     * without a readable cpufreq node are skipped; any failure yields an empty map,
     * which callers treat as "no data" rather than "one uniform tier".
     */
    private static Map<Long, Integer> readSysfsFrequencyGroups() {
        final TreeMap<Long, Integer> groups = new TreeMap<>();
        try (DirectoryStream<Path> cpus = Files.newDirectoryStream(
            Path.of(SYSFS_CPU_ROOT), "cpu[0-9]*"
        )) {
            for (final Path cpu : cpus) {
                final Path maxFreq = cpu.resolve("cpufreq").resolve("cpuinfo_max_freq");
                if (!Files.isReadable(maxFreq)) {
                    continue;
                }
                try {
                    groups.merge(Long.parseLong(Files.readString(maxFreq).trim()), 1, Integer::sum);
                } catch (final NumberFormatException | IOException e) {
                    // Unreadable or malformed entry; that CPU contributes nothing.
                }
            }
        } catch (final IOException | RuntimeException e) {
            return Map.of();
        }
        return groups;
    }

    private static String ghz(final long khz) {
        return String.format(Locale.ROOT, "%.1f GHz", khz / 1_000_000.0);
    }
}

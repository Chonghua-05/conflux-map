package cn.net.rms.confluxmap.nativepredict;

import java.util.function.ToIntFunction;

/**
 * Reads CPU topology labels through the bundled shim library, when that library
 * is loaded: Windows per-processor efficiency classes, Linux x86_64 CPUID hybrid
 * core types. Every failure mode — library absent, export missing from an older
 * {@code confluxnative} build, or the OS/CPU declining to answer — yields an
 * empty array instead of an exception, so the caller can treat the result as
 * "no data" and fall back without any risk to startup.
 */
public final class NativeCpuTopology {
    private NativeCpuTopology() {
    }

    /**
     * Per-logical-processor efficiency classes (0 = efficiency, higher =
     * performance) as reported by the shim, or an empty array when unavailable.
     */
    public static int[] efficiencyClasses() {
        return queryTopology(CubiomesNative::cfxCpuEfficiencyClasses);
    }

    /**
     * Per-logical-processor CPUID hybrid core types (0x40 = Core/P, 0x20 =
     * Atom/E) as reported by the shim, or an empty array when unavailable.
     */
    public static int[] hybridCoreTypes() {
        return queryTopology(CubiomesNative::cfxCpuHybridCoreTypes);
    }

    /**
     * Runs one shim export through its two-call protocol: the {@code null} call
     * sizes the answer, the array call fills it. Anything but a complete fill —
     * no library, a stale build without the export, a 0 count, a short write —
     * is "no data".
     */
    private static int[] queryTopology(final ToIntFunction<int[]> export) {
        if (!NativeLib.available()) {
            return new int[0];
        }
        try {
            final int total = export.applyAsInt(null);
            if (total <= 0) {
                return new int[0];
            }
            final int[] values = new int[total];
            return export.applyAsInt(values) == total ? values : new int[0];
        } catch (final UnsatisfiedLinkError e) {
            // A library predating this export; topology detection falls back.
            return new int[0];
        }
    }
}

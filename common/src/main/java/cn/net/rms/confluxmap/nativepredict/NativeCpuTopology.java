package cn.net.rms.confluxmap.nativepredict;

/**
 * Reads the Windows CPU efficiency-class topology through the bundled shim
 * library, when that library is loaded. Every failure mode — library absent,
 * export missing from an older {@code confluxnative.dll}, or the OS API
 * declining to answer — yields an empty array instead of an exception, so the
 * caller can treat the result as "no data" and fall back to a uniform topology
 * without any risk to startup.
 */
public final class NativeCpuTopology {
    private NativeCpuTopology() {
    }

    /**
     * Per-logical-processor efficiency classes (0 = efficiency, higher =
     * performance) as reported by the shim, or an empty array when unavailable.
     */
    public static int[] efficiencyClasses() {
        if (!NativeLib.available()) {
            return new int[0];
        }
        try {
            final int total = CubiomesNative.cfxCpuEfficiencyClasses(null);
            if (total <= 0) {
                return new int[0];
            }
            final int[] classes = new int[total];
            return CubiomesNative.cfxCpuEfficiencyClasses(classes) == total
                ? classes
                : new int[0];
        } catch (final UnsatisfiedLinkError e) {
            // A library predating this export; topology detection falls back to uniform.
            return new int[0];
        }
    }
}

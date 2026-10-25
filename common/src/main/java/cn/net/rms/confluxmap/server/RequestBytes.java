package cn.net.rms.confluxmap.server;

import java.util.Arrays;

/**
 * Spreads one request's wire bytes across its items so per-item delivery accounting attributes
 * every byte exactly once. Shared by the Fabric and Paper companions' request admission.
 */
public final class RequestBytes {
    private RequestBytes() {
    }

    /**
     * Evenly splits {@code totalBytes} over {@code items} slots; the first {@code totalBytes % items}
     * slots carry the remainder. Negative totals are clamped to zero.
     */
    public static int[] spread(final int totalBytes, final int items) {
        if (items <= 0) {
            return new int[0];
        }
        final int[] result = new int[items];
        Arrays.fill(result, Math.max(0, totalBytes) / items);
        int remaining = Math.max(0, totalBytes) % items;
        for (int i = 0; i < items && remaining > 0; i++, remaining--) {
            result[i]++;
        }
        return result;
    }
}

package cn.net.rms.confluxmap.core.util;

import java.math.BigDecimal;

/**
 * Coordinate text that never uses scientific notation. {@link Double#toString} switches to
 * "1.0E7" for magnitudes at or above 1e7 and below 1e-3 — both reachable inside the world
 * border — and vanilla command parsing accepts only digits, '.', and '-'. Finite
 * coordinates only.
 */
public final class CoordinateText {
    private CoordinateText() {
    }

    /** Exact plain-decimal digits with trailing zeros trimmed: 90.0 renders as "90". */
    public static String plain(final double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}

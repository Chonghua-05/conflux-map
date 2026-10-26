package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.core.util.CoordinateText;
import java.util.Locale;

/** Search matching shared by local and public waypoint lists. */
final class WaypointSearch {
    private WaypointSearch() {
    }

    static boolean matches(
        final String rawQuery,
        final String name,
        final String secondaryText,
        final String dimensionText,
        final double x,
        final double y,
        final double z
    ) {
        final String query = rawQuery.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            return true;
        }
        return contains(name, query)
            || contains(secondaryText, query)
            || contains(dimensionText, query)
            || CoordinateText.plain(x).contains(query)
            || CoordinateText.plain(y).contains(query)
            || CoordinateText.plain(z).contains(query);
    }

    private static boolean contains(final String value, final String query) {
        return value.toLowerCase(Locale.ROOT).contains(query);
    }
}

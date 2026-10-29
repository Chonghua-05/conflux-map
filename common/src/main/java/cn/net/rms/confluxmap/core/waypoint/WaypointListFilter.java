package cn.net.rms.confluxmap.core.waypoint;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.waypoint.list.WaypointRow;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Dimension filtering for waypoint-management lists. */
public final class WaypointListFilter {
    private WaypointListFilter() {
    }

    public static List<Waypoint> local(
        final List<Waypoint> waypoints,
        final DimensionId currentDimension,
        final WaypointDimensionFilter dimensionFilter
    ) {
        return filter(
            waypoints, waypoint -> waypoint.dimensionId, currentDimension, dimensionFilter
        );
    }

    public static List<SharedWaypoint> shared(
        final List<SharedWaypoint> waypoints,
        final DimensionId currentDimension,
        final WaypointDimensionFilter dimensionFilter
    ) {
        return filter(
            waypoints, SharedWaypoint::dimensionId, currentDimension, dimensionFilter
        );
    }

    public static List<SiblingWaypoint> siblings(
        final List<SiblingWaypoint> waypoints,
        final DimensionId currentDimension,
        final WaypointDimensionFilter dimensionFilter
    ) {
        return filter(
            waypoints, sibling -> sibling.waypoint().dimensionId, currentDimension, dimensionFilter
        );
    }

    /** Dimension filtering over the unified list template's row projection. */
    public static List<WaypointRow> rows(
        final List<WaypointRow> rows,
        final DimensionId currentDimension,
        final WaypointDimensionFilter dimensionFilter
    ) {
        return filter(rows, WaypointRow::dimensionId, currentDimension, dimensionFilter);
    }

    private static <T> List<T> filter(
        final List<T> waypoints,
        final Function<T, DimensionId> dimensionOf,
        final DimensionId currentDimension,
        final WaypointDimensionFilter dimensionFilter
    ) {
        Objects.requireNonNull(waypoints, "waypoints");
        Objects.requireNonNull(currentDimension, "currentDimension");
        Objects.requireNonNull(dimensionFilter, "dimensionFilter");
        return waypoints.stream()
            .filter(waypoint -> dimensionFilter.matches(
                dimensionOf.apply(waypoint), currentDimension
            ))
            .toList();
    }
}

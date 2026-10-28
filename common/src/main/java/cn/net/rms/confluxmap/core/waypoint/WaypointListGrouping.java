package cn.net.rms.confluxmap.core.waypoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Flattens pre-filtered waypoint rows into the set-grouped model used by the
 * waypoint management list: one collapsible header per set that still has rows,
 * followed by that set's rows in encounter order. Pure over its inputs, so the
 * management screen only supplies dimension/search filtering and sorting.
 */
public final class WaypointListGrouping {

    /** One flattened list row: either a set header or a member waypoint. */
    public interface Entry<T> {
    }

    /**
     * Header row for one set. {@code collapsed} reports the set's persisted
     * fold state as given, even when {@code expandAll} currently suspends it.
     */
    public record SetHeader<T>(String setName, boolean collapsed, int visibleCount) implements Entry<T> {
    }

    /** Member row, indented one level under its set header. */
    public record Member<T>(String setName, T row) implements Entry<T> {
    }

    private WaypointListGrouping() {
    }

    /**
     * Groups rows by set in {@code sets} order (default set first, then creation
     * order), preserving encounter order within each set. Sets without rows are
     * omitted; a collapsed set contributes only its header unless {@code expandAll}
     * suspends folding, which the caller does while a search filter is active so
     * matches inside folded sets stay visible. Rows whose group has no set
     * metadata are appended as extra expanded groups in first-encounter order -
     * defensive, since {@link WaypointStore} keeps every group materialized.
     */
    public static <T> List<Entry<T>> group(
        final List<WaypointSet> sets,
        final List<T> rows,
        final Function<T, String> groupOf,
        final boolean expandAll
    ) {
        final Map<String, List<T>> byGroup = new LinkedHashMap<>();
        for (final T row : rows) {
            byGroup.computeIfAbsent(groupOf.apply(row), name -> new ArrayList<>()).add(row);
        }
        final List<Entry<T>> out = new ArrayList<>();
        for (final WaypointSet set : sets) {
            final List<T> members = byGroup.remove(set.name());
            if (members == null) {
                continue;
            }
            out.add(new SetHeader<>(set.name(), set.collapsed(), members.size()));
            if (!set.collapsed() || expandAll) {
                for (final T row : members) {
                    out.add(new Member<>(set.name(), row));
                }
            }
        }
        for (final Map.Entry<String, List<T>> unknown : byGroup.entrySet()) {
            out.add(new SetHeader<>(unknown.getKey(), false, unknown.getValue().size()));
            for (final T row : unknown.getValue()) {
                out.add(new Member<>(unknown.getKey(), row));
            }
        }
        return out;
    }
}

package cn.net.rms.confluxmap.core.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class WaypointListGroupingTest {
    private static final List<WaypointSet> SETS = List.of(
        WaypointSet.DEFAULT,
        new WaypointSet("Bases"),
        new WaypointSet("Travel")
    );

    @Test
    void groupsRowsUnderHeadersInSetOrderPreservingEncounterOrder() {
        final List<String> rows = List.of("Travel spot", "Base camp", "Unassigned", "Second base");

        final List<WaypointListGrouping.Entry<String>> grouped = WaypointListGrouping.group(
            SETS, rows, row -> groupOf(row), false
        );

        assertEquals(List.of(
            "header: \"\" (1)",
            "member Unassigned -> \"\"",
            "header: \"Bases\" (2)",
            "member Base camp -> \"Bases\"",
            "member Second base -> \"Bases\"",
            "header: \"Travel\" (1)",
            "member Travel spot -> \"Travel\""
        ), render(grouped));
    }

    @Test
    void collapsedSetContributesOnlyItsHeader() {
        final List<WaypointSet> sets = List.of(
            WaypointSet.DEFAULT,
            new WaypointSet("Bases", true)
        );
        final List<String> rows = List.of("Base camp", "Unassigned");

        final List<WaypointListGrouping.Entry<String>> grouped = WaypointListGrouping.group(
            sets, rows, row -> groupOf(row), false
        );

        assertEquals(List.of(
            "header: \"\" (1)",
            "member Unassigned -> \"\"",
            "header: \"Bases\" [collapsed] (1)"
        ), render(grouped));
    }

    @Test
    void expandAllSuspendsCollapseButHeadersStillReportThePersistedState() {
        final List<WaypointSet> sets = List.of(new WaypointSet("Bases", true));
        final List<String> rows = List.of("Base camp");

        final List<WaypointListGrouping.Entry<String>> grouped = WaypointListGrouping.group(
            sets, rows, row -> groupOf(row), true
        );

        assertEquals(List.of(
            "header: \"Bases\" [collapsed] (1)",
            "member Base camp -> \"Bases\""
        ), render(grouped));
    }

    @Test
    void setsWithoutRowsAndEmptyInputProduceNothing() {
        assertEquals(
            List.of(),
            WaypointListGrouping.group(SETS, List.<String>of(), WaypointListGroupingTest::groupOf, false)
        );
        assertEquals(
            List.of(),
            WaypointListGrouping.group(
                List.of(new WaypointSet("Empty")), List.<String>of(), WaypointListGroupingTest::groupOf, false
            )
        );
    }

    @Test
    void rowsOutsideSetMetadataAppendAsExpandedGroupsInEncounterOrder() {
        final List<String> rows = List.of("Ghost two", "Base camp", "Ghost one");

        final List<WaypointListGrouping.Entry<String>> grouped = WaypointListGrouping.group(
            List.of(WaypointSet.DEFAULT, new WaypointSet("Bases")), rows, row -> groupOf(row), false
        );

        assertEquals(List.of(
            "header: \"Bases\" (1)",
            "member Base camp -> \"Bases\"",
            "header: \"Ghost\" (2)",
            "member Ghost two -> \"Ghost\"",
            "member Ghost one -> \"Ghost\""
        ), render(grouped));
    }

    private static String groupOf(final String row) {
        if (row.startsWith("Base") || row.startsWith("Second")) {
            return "Bases";
        }
        if (row.startsWith("Travel")) {
            return "Travel";
        }
        if (row.startsWith("Ghost")) {
            return "Ghost";
        }
        return "";
    }

    private static List<String> render(final List<WaypointListGrouping.Entry<String>> entries) {
        return entries.stream().map(entry -> {
            if (entry instanceof WaypointListGrouping.SetHeader<?> header) {
                return "header: \"" + header.setName() + "\""
                    + (header.collapsed() ? " [collapsed]" : "")
                    + " (" + header.visibleCount() + ")";
            }
            final WaypointListGrouping.Member<?> member = (WaypointListGrouping.Member<?>) entry;
            return "member " + member.row() + " -> \"" + member.setName() + "\"";
        }).toList();
    }
}

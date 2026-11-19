package cn.net.rms.confluxmap.core.waypoint.list;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The unified waypoint list template: one operation surface driven by two
 * models, local waypoints and server-shared waypoints. The management screen
 * renders rows, group headers, toolbars and edit forms purely from this
 * contract, so both sides keep feature parity by construction - an operation
 * this interface exposes is available on both tabs, gated only by the
 * {@link Permission} predicates each model derives from its own authority
 * (local store writability, shared-server policy and negotiated protocol).
 *
 * <p>All methods are main-thread only, matching the stores and network state
 * machines underneath. Mutating methods return whether the request was
 * accepted (applied locally or dispatched); shared-side results arrive
 * asynchronously through the server's result/delta flow and surface as
 * {@link #stateVersion()} changes.
 */
public interface WaypointListModel {
    /** Granted, or denied with a user-facing disabled-reason translation key. */
    record Permission(boolean granted, String reasonKey) {
        public Permission {
            if (granted && reasonKey != null) {
                throw new IllegalArgumentException("granted permission carries a reason key");
            }
            if (!granted && reasonKey == null) {
                throw new IllegalArgumentException("denied permission requires a reason key");
            }
        }

        public static Permission allow() {
            return new Permission(true, null);
        }

        public static Permission denied(final String reasonKey) {
            return new Permission(false, Objects.requireNonNull(reasonKey, "reasonKey"));
        }
    }

    /** What deleting a group does to its member waypoints. */
    enum GroupDeleteBehavior {
        /** Local sets own their members; deleting removes the waypoints too. */
        CASCADE_MEMBERS,
        /** Shared groups may hold other players' waypoints; deleting ungroups them. */
        UNGROUP_MEMBERS
    }

    /** All own-waypoint rows, unfiltered, in store/catalog order. */
    List<WaypointRow> waypoints();

    /**
     * Read-only rows from outside this model's ownership (local tab:
     * cross-world siblings). The screen decides when guest rows are shown;
     * they never participate in selection or mutation.
     */
    List<WaypointRow> guestWaypoints();

    /** Real groups in creation order, without the ungrouped bucket. */
    List<GroupRef> groups();

    /** The ungrouped bucket with its own persisted fold state. */
    GroupRef ungrouped();

    /** Groups in display order: real groups by creation order, ungrouped last. */
    default List<GroupRef> groupsForDisplay() {
        final List<GroupRef> all = new java.util.ArrayList<>(groups());
        all.add(ungrouped());
        return List.copyOf(all);
    }

    /** Resolves a group key (including {@link GroupRef#UNGROUPED_KEY}); null when unknown. */
    default GroupRef groupByKey(final String key) {
        for (final GroupRef group : groupsForDisplay()) {
            if (group.key().equals(key)) {
                return group;
            }
        }
        return null;
    }

    /** Count of waypoints currently assigned to one group, used for dropdown and confirm copy. */
    default int memberCount(final String groupKey) {
        int count = 0;
        for (final WaypointRow row : waypoints()) {
            if (row.groupKey().equals(groupKey)) {
                count++;
            }
        }
        return count;
    }

    /** Opaque value the screen compares per tick to detect any model change. */
    long stateVersion();

    /** True while a shared model still awaits its first authoritative snapshot. */
    boolean syncPending();

    Permission canCreateWaypoint();

    Permission canEditWaypoint(WaypointRow row);

    Permission canDeleteWaypoint(WaypointRow row);

    Permission canMoveWaypoint(WaypointRow row);

    Permission canToggleHidden(WaypointRow row);

    Permission canCreateGroup();

    Permission canRenameGroup(GroupRef group);

    Permission canDeleteGroup(GroupRef group);

    /** Whether batch moves are atomic on this model's backend. */
    boolean supportsBatchMove();

    /** Whether the edit form may assign the group directly (shared needs protocol minor 6). */
    boolean supportsInFormGroupSelection();

    GroupDeleteBehavior groupDeleteBehavior();

    boolean createWaypoint(EditDraft draft);

    boolean updateWaypoint(WaypointRow row, EditDraft draft);

    boolean deleteWaypoint(WaypointRow row);

    /** Moves rows into {@code target}; one transaction locally, one batch request for shared. */
    boolean moveWaypoints(List<WaypointRow> rows, GroupRef target);

    boolean toggleWaypointHidden(WaypointRow row);

    boolean toggleGroupCollapsed(GroupRef group);

    boolean createGroup(String name);

    boolean renameGroup(GroupRef group, String name);

    boolean deleteGroup(GroupRef group);

    /** Row lookup by id for routing gestures back from screen-held rows. */
    default WaypointRow rowById(final UUID id) {
        for (final WaypointRow row : waypoints()) {
            if (row.id().equals(id)) {
                return row;
            }
        }
        for (final WaypointRow row : guestWaypoints()) {
            if (row.id().equals(id)) {
                return row;
            }
        }
        return null;
    }
}

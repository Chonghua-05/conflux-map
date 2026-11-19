package cn.net.rms.confluxmap.core.net.shared;

import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.shared.SharedWaypointGroup;
import java.util.List;
import java.util.Objects;

/** Full shared-waypoint state at one server revision. */
public record SnapshotS2C(
    long revision,
    boolean operator,
    List<SharedWaypoint> list,
    List<SharedWaypointGroup> groups
) implements SharedWaypointMessage {
    public SnapshotS2C {
        Objects.requireNonNull(list, "list");
        Objects.requireNonNull(groups, "groups");
        list = List.copyOf(list);
        groups = List.copyOf(groups);
    }

    public SnapshotS2C(final long revision, final boolean operator, final List<SharedWaypoint> list) {
        this(revision, operator, list, List.of());
    }

    @Override
    public int typeId() {
        return SharedWaypointProto.MSG_SNAPSHOT_S2C;
    }
}

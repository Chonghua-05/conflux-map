package cn.net.rms.confluxmap.core.net.shared;

import cn.net.rms.confluxmap.core.shared.SharedWaypointGroup;
import java.util.Objects;

/** Adds or replaces one shared-waypoint group in a subscribed client cache. */
public record GroupUpsertS2C(long revision, SharedWaypointGroup group) implements SharedWaypointMessage {
    public GroupUpsertS2C {
        Objects.requireNonNull(group, "group");
    }

    @Override
    public int typeId() {
        return SharedWaypointProto.MSG_GROUP_UPSERT_S2C;
    }
}

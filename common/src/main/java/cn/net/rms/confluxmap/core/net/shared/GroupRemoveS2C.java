package cn.net.rms.confluxmap.core.net.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * Removes one shared-waypoint group from a subscribed client cache. Member waypoints
 * become ungrouped; clients clear the group reference locally instead of receiving
 * one waypoint upsert per member.
 */
public record GroupRemoveS2C(long revision, UUID groupId) implements SharedWaypointMessage {
    public GroupRemoveS2C {
        Objects.requireNonNull(groupId, "groupId");
    }

    @Override
    public int typeId() {
        return SharedWaypointProto.MSG_GROUP_REMOVE_S2C;
    }
}

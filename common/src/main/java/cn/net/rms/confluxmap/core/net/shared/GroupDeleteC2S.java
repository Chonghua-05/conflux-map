package cn.net.rms.confluxmap.core.net.shared;

import java.util.Objects;
import java.util.UUID;

/** Deletes one shared-waypoint group if its revision still matches; members become ungrouped. */
public record GroupDeleteC2S(UUID operationId, long expectedRevision, UUID groupId) implements SharedWaypointMessage {
    public GroupDeleteC2S {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(groupId, "groupId");
    }

    @Override
    public int typeId() {
        return SharedWaypointProto.MSG_GROUP_DELETE_C2S;
    }
}

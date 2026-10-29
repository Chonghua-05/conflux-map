package cn.net.rms.confluxmap.core.net.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * Moves one shared waypoint into a group, or out of every group when {@code groupId} is
 * null, if the waypoint's revision still matches.
 */
public record SetWaypointGroupC2S(UUID operationId, UUID waypointId, long expectedRevision, UUID groupId)
    implements SharedWaypointMessage {
    public SetWaypointGroupC2S {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(waypointId, "waypointId");
    }

    @Override
    public int typeId() {
        return SharedWaypointProto.MSG_SET_GROUP_C2S;
    }
}

package cn.net.rms.confluxmap.core.net.shared;

import java.util.Objects;
import java.util.UUID;

/** Creates one shared-waypoint group if the catalog revision still matches. */
public record GroupCreateC2S(UUID operationId, long expectedRevision, String name) implements SharedWaypointMessage {
    public GroupCreateC2S {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(name, "name");
    }

    @Override
    public int typeId() {
        return SharedWaypointProto.MSG_GROUP_CREATE_C2S;
    }
}

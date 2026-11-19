package cn.net.rms.confluxmap.core.net.shared;

import java.util.Objects;
import java.util.UUID;

/** Renames one shared-waypoint group if its revision still matches. */
public record GroupRenameC2S(UUID operationId, long expectedRevision, UUID groupId, String name)
    implements SharedWaypointMessage {
    public GroupRenameC2S {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(name, "name");
    }

    @Override
    public int typeId() {
        return SharedWaypointProto.MSG_GROUP_RENAME_C2S;
    }
}

package cn.net.rms.confluxmap.core.net.shared;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Moves several shared waypoints into one group (or out of every group with a
 * null {@code groupId}) as a single atomic request: the server validates every
 * entry first and rejects the whole batch on the first failure. Wire minor 6
 * and newer only; older peers fall back to per-waypoint
 * {@link SetWaypointGroupC2S} requests.
 *
 * @param expectedRevision the global snapshot revision the client based the
 *                         whole batch on, guarding against a stale catalog
 */
public record MoveBatchC2S(
    UUID operationId,
    long expectedRevision,
    List<Entry> entries
) implements SharedWaypointMessage {
    public MoveBatchC2S {
        Objects.requireNonNull(operationId, "operationId");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    /** One member of a batch move; {@code groupId} null means ungroup. */
    public record Entry(UUID waypointId, long expectedWaypointRevision, UUID groupId) {
        public Entry {
            Objects.requireNonNull(waypointId, "waypointId");
        }
    }

    @Override
    public int typeId() {
        return SharedWaypointProto.MSG_MOVE_BATCH_C2S;
    }
}

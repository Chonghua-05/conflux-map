package cn.net.rms.confluxmap.core.waypoint.list;

import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.shared.SharedWaypointGroup;
import java.util.List;
import java.util.UUID;

/**
 * Send-path boundary between {@link SharedWaypointListModel} and the
 * Minecraft-facing network client. The core model owns policy and state; the
 * adapter implementing this interface owns operation ids, pending-operation
 * bookkeeping and wire fallbacks (for example looping single-group requests
 * when the negotiated protocol predates the batch message).
 */
public interface SharedWaypointMutations {
    /**
     * Publishes a new shared waypoint. The gateway applies the client's
     * cross-dimension preferences once the server echo arrives.
     *
     * @param groupId target group, or null to create ungrouped
     */
    boolean create(EditDraft draft, UUID groupId);

    /** Updates one shared waypoint; {@code groupId} is the explicit new group (null = ungroup). */
    boolean update(SharedWaypoint original, EditDraft draft, UUID groupId);

    boolean delete(SharedWaypoint waypoint);

    /**
     * Moves waypoints into {@code groupId} (null = ungroup) as one batch when
     * the negotiated protocol allows, otherwise as per-waypoint requests.
     */
    boolean moveBatch(List<SharedWaypoint> waypoints, UUID groupId);

    boolean createGroup(String name);

    boolean renameGroup(SharedWaypointGroup group, String name);

    boolean deleteGroup(SharedWaypointGroup group);
}

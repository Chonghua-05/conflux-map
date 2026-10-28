package cn.net.rms.confluxmap.core.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * A server-owned named group (waypoint set) that shared waypoints can belong to.
 *
 * <p>Groups are global to the world, not split per publisher: one list of groups is
 * published to every player, and a waypoint references at most one group by id
 * ({@code null} means ungrouped). {@link #revision()} is the server-assigned revision
 * of the last mutation of this group, used for optimistic updates like waypoint
 * revisions.
 */
public record SharedWaypointGroup(
    UUID id,
    UUID creatorId,
    String name,
    long createdAtEpochMs,
    long revision
) {
    public SharedWaypointGroup {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(creatorId, "creatorId");
        Objects.requireNonNull(name, "name");
    }
}

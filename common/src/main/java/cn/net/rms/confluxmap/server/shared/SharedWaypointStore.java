package cn.net.rms.confluxmap.server.shared;

import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.shared.SharedWaypointGroup;
import cn.net.rms.confluxmap.core.shared.SharedWaypointLocationKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Authoritative in-memory shared-waypoint state for one world.
 *
 * <p>Mutations are prepared against a revision, persisted by {@link SharedWaypointService}, and
 * committed only after the durable write succeeds. This prevents a failed write from exposing
 * state that will disappear after restart.
 */
public final class SharedWaypointStore {
    public enum DeltaKind {
        UPSERT,
        REMOVE,
        GROUP_UPSERT,
        GROUP_REMOVE,
        NOOP
    }

    /** Immutable full-state view. */
    public record Snapshot(
        long revision,
        List<SharedWaypoint> waypoints,
        List<SharedWaypointGroup> groups
    ) {
        public Snapshot {
            if (revision < 0) {
                throw new IllegalArgumentException("revision must be non-negative");
            }
            waypoints = List.copyOf(Objects.requireNonNull(waypoints, "waypoints"));
            groups = List.copyOf(Objects.requireNonNull(groups, "groups"));
        }

        public Snapshot(final long revision, final List<SharedWaypoint> waypoints) {
            this(revision, waypoints, List.of());
        }
    }

    /** One wire-friendly state change; only the field relevant to {@link #kind()} is populated. */
    public record Delta(
        DeltaKind kind,
        long revision,
        SharedWaypoint waypoint,
        UUID removedId,
        SharedWaypointGroup group,
        UUID removedGroupId
    ) {
        public Delta {
            Objects.requireNonNull(kind, "kind");
        }

        public static Delta upsert(final SharedWaypoint waypoint, final long revision) {
            return new Delta(DeltaKind.UPSERT, revision, Objects.requireNonNull(waypoint, "waypoint"), null, null, null);
        }

        public static Delta remove(final UUID id, final long revision) {
            return new Delta(DeltaKind.REMOVE, revision, null, Objects.requireNonNull(id, "id"), null, null);
        }

        public static Delta groupUpsert(final SharedWaypointGroup group, final long revision) {
            return new Delta(
                DeltaKind.GROUP_UPSERT, revision, null, null,
                Objects.requireNonNull(group, "group"), null
            );
        }

        /** Group removal implicitly ungroups its member waypoints. */
        public static Delta groupRemove(final UUID groupId, final long revision) {
            return new Delta(
                DeltaKind.GROUP_REMOVE, revision, null, null, null, Objects.requireNonNull(groupId, "groupId")
            );
        }

        public static Delta noop(final long revision) {
            return new Delta(DeltaKind.NOOP, revision, null, null, null, null);
        }
    }

    static final class PreparedMutation {
        private final long baseRevision;
        private final Snapshot snapshot;
        private final Delta delta;

        private PreparedMutation(final long baseRevision, final Snapshot snapshot, final Delta delta) {
            this.baseRevision = baseRevision;
            this.snapshot = snapshot;
            this.delta = delta;
        }

        Snapshot snapshot() {
            return snapshot;
        }

        Delta delta() {
            return delta;
        }
    }

    private long revision;
    private final Map<UUID, SharedWaypoint> byId = new LinkedHashMap<>();
    private final Map<SharedWaypointLocationKey, UUID> idByLocation = new LinkedHashMap<>();
    private final Map<UUID, SharedWaypointGroup> groupsById = new LinkedHashMap<>();

    public SharedWaypointStore(final Snapshot initial) {
        Objects.requireNonNull(initial, "initial");
        revision = initial.revision();
        for (final SharedWaypointGroup group : initial.groups()) {
            requireGroupRevision(group, revision);
            if (groupsById.put(group.id(), group) != null) {
                throw new IllegalArgumentException("duplicate shared waypoint group id " + group.id());
            }
        }
        for (final SharedWaypoint waypoint : initial.waypoints()) {
            requireWaypointRevision(waypoint, revision);
            if (waypoint.groupId() != null && !groupsById.containsKey(waypoint.groupId())) {
                throw new IllegalArgumentException(
                    "shared waypoint " + waypoint.id() + " references unknown group " + waypoint.groupId()
                );
            }
            if (byId.put(waypoint.id(), waypoint) != null) {
                throw new IllegalArgumentException("duplicate shared waypoint id " + waypoint.id());
            }
            // Historical stores may already contain duplicate locations. Keep them readable, but
            // index one representative so every future create is rejected for that occupied block.
            idByLocation.putIfAbsent(SharedWaypointLocationKey.from(waypoint), waypoint.id());
        }
    }

    public static SharedWaypointStore empty() {
        return new SharedWaypointStore(new Snapshot(0, List.of()));
    }

    public synchronized Snapshot snapshot() {
        return snapshotOf(revision, byId, groupsById);
    }

    public synchronized Optional<SharedWaypoint> find(final UUID id) {
        return Optional.ofNullable(byId.get(Objects.requireNonNull(id, "id")));
    }

    public synchronized Optional<SharedWaypoint> findAt(final SharedWaypointLocationKey location) {
        final UUID id = idByLocation.get(Objects.requireNonNull(location, "location"));
        return Optional.ofNullable(id == null ? null : byId.get(id));
    }

    public synchronized List<SharedWaypointGroup> groups() {
        return List.copyOf(groupsById.values());
    }

    public synchronized Optional<SharedWaypointGroup> findGroup(final UUID id) {
        return Optional.ofNullable(groupsById.get(Objects.requireNonNull(id, "id")));
    }

    public synchronized int size() {
        return byId.size();
    }

    public synchronized int groupCount() {
        return groupsById.size();
    }

    public synchronized int countPublishedBy(final UUID publisherId) {
        int count = 0;
        for (final SharedWaypoint waypoint : byId.values()) {
            if (waypoint.publisherId().equals(publisherId)) {
                count++;
            }
        }
        return count;
    }

    synchronized PreparedMutation prepareCreate(final SharedWaypoint waypoint) {
        Objects.requireNonNull(waypoint, "waypoint");
        if (byId.containsKey(waypoint.id())) {
            throw new IllegalArgumentException("duplicate shared waypoint id " + waypoint.id());
        }
        if (waypoint.groupId() != null && !groupsById.containsKey(waypoint.groupId())) {
            throw new IllegalArgumentException("created waypoint references unknown group");
        }
        if (idByLocation.containsKey(SharedWaypointLocationKey.from(waypoint))) {
            throw new IllegalArgumentException("duplicate shared waypoint location");
        }
        final long nextRevision = Math.addExact(revision, 1);
        if (waypoint.revision() != nextRevision) {
            throw new IllegalArgumentException("created waypoint revision must equal next global revision");
        }
        final Map<UUID, SharedWaypoint> next = new LinkedHashMap<>(byId);
        next.put(waypoint.id(), waypoint);
        return new PreparedMutation(revision, snapshotOf(nextRevision, next, groupsById), Delta.upsert(waypoint, nextRevision));
    }

    synchronized PreparedMutation prepareDelete(final UUID id) {
        Objects.requireNonNull(id, "id");
        if (!byId.containsKey(id)) {
            throw new IllegalArgumentException("shared waypoint does not exist: " + id);
        }
        final long nextRevision = Math.addExact(revision, 1);
        final Map<UUID, SharedWaypoint> next = new LinkedHashMap<>(byId);
        next.remove(id);
        return new PreparedMutation(
            revision, snapshotOf(nextRevision, next, groupsById), Delta.remove(id, nextRevision)
        );
    }

    synchronized PreparedMutation prepareUpdate(final SharedWaypoint waypoint) {
        Objects.requireNonNull(waypoint, "waypoint");
        if (!byId.containsKey(waypoint.id())) {
            throw new IllegalArgumentException("shared waypoint does not exist: " + waypoint.id());
        }
        if (waypoint.groupId() != null && !groupsById.containsKey(waypoint.groupId())) {
            throw new IllegalArgumentException("updated waypoint references unknown group");
        }
        final long nextRevision = Math.addExact(revision, 1);
        if (waypoint.revision() != nextRevision) {
            throw new IllegalArgumentException("updated waypoint revision must equal next global revision");
        }
        final SharedWaypoint current = byId.get(waypoint.id());
        final SharedWaypointLocationKey currentLocation = SharedWaypointLocationKey.from(current);
        final SharedWaypointLocationKey updatedLocation = SharedWaypointLocationKey.from(waypoint);
        final UUID occupant = idByLocation.get(updatedLocation);
        if (!updatedLocation.equals(currentLocation)
            && occupant != null && !occupant.equals(waypoint.id())) {
            throw new IllegalArgumentException("duplicate shared waypoint location");
        }
        final Map<UUID, SharedWaypoint> next = new LinkedHashMap<>(byId);
        next.put(waypoint.id(), waypoint);
        return new PreparedMutation(
            revision,
            snapshotOf(nextRevision, next, groupsById),
            Delta.upsert(waypoint, nextRevision)
        );
    }

    synchronized PreparedMutation prepareGroupCreate(final SharedWaypointGroup group) {
        Objects.requireNonNull(group, "group");
        if (groupsById.containsKey(group.id())) {
            throw new IllegalArgumentException("duplicate shared waypoint group id " + group.id());
        }
        final long nextRevision = Math.addExact(revision, 1);
        if (group.revision() != nextRevision) {
            throw new IllegalArgumentException("created group revision must equal next global revision");
        }
        final Map<UUID, SharedWaypointGroup> nextGroups = new LinkedHashMap<>(groupsById);
        nextGroups.put(group.id(), group);
        return new PreparedMutation(
            revision, snapshotOf(nextRevision, byId, nextGroups), Delta.groupUpsert(group, nextRevision)
        );
    }

    synchronized PreparedMutation prepareGroupUpdate(final SharedWaypointGroup group) {
        Objects.requireNonNull(group, "group");
        if (!groupsById.containsKey(group.id())) {
            throw new IllegalArgumentException("shared waypoint group does not exist: " + group.id());
        }
        final long nextRevision = Math.addExact(revision, 1);
        if (group.revision() != nextRevision) {
            throw new IllegalArgumentException("renamed group revision must equal next global revision");
        }
        final Map<UUID, SharedWaypointGroup> nextGroups = new LinkedHashMap<>(groupsById);
        nextGroups.put(group.id(), group);
        return new PreparedMutation(
            revision, snapshotOf(nextRevision, byId, nextGroups), Delta.groupUpsert(group, nextRevision)
        );
    }

    /** Deleting a group keeps its member waypoints and clears their group reference. */
    synchronized PreparedMutation prepareGroupDelete(final UUID id) {
        Objects.requireNonNull(id, "id");
        if (!groupsById.containsKey(id)) {
            throw new IllegalArgumentException("shared waypoint group does not exist: " + id);
        }
        final long nextRevision = Math.addExact(revision, 1);
        final Map<UUID, SharedWaypointGroup> nextGroups = new LinkedHashMap<>(groupsById);
        nextGroups.remove(id);
        final Map<UUID, SharedWaypoint> next = new LinkedHashMap<>();
        for (final SharedWaypoint waypoint : byId.values()) {
            next.put(
                waypoint.id(),
                id.equals(waypoint.groupId()) ? waypoint.withGroupId(null) : waypoint
            );
        }
        return new PreparedMutation(
            revision,
            snapshotOf(nextRevision, next, nextGroups),
            Delta.groupRemove(id, nextRevision)
        );
    }

    synchronized void commit(final PreparedMutation mutation) {
        Objects.requireNonNull(mutation, "mutation");
        if (revision != mutation.baseRevision) {
            throw new IllegalStateException("prepared shared-waypoint mutation is stale");
        }
        revision = mutation.snapshot.revision();
        byId.clear();
        idByLocation.clear();
        for (final SharedWaypoint waypoint : mutation.snapshot.waypoints()) {
            byId.put(waypoint.id(), waypoint);
            idByLocation.putIfAbsent(SharedWaypointLocationKey.from(waypoint), waypoint.id());
        }
        groupsById.clear();
        for (final SharedWaypointGroup group : mutation.snapshot.groups()) {
            groupsById.put(group.id(), group);
        }
    }

    private static Snapshot snapshotOf(
        final long revision,
        final Map<UUID, SharedWaypoint> waypoints,
        final Map<UUID, SharedWaypointGroup> groups
    ) {
        return new Snapshot(
            revision, new ArrayList<>(waypoints.values()), new ArrayList<>(groups.values())
        );
    }

    private static void requireWaypointRevision(final SharedWaypoint waypoint, final long globalRevision) {
        Objects.requireNonNull(waypoint, "waypoint");
        if (waypoint.revision() < 1 || waypoint.revision() > globalRevision) {
            throw new IllegalArgumentException("waypoint revision is outside the global revision");
        }
    }

    private static void requireGroupRevision(final SharedWaypointGroup group, final long globalRevision) {
        Objects.requireNonNull(group, "group");
        if (group.revision() < 1 || group.revision() > globalRevision) {
            throw new IllegalArgumentException("group revision is outside the global revision");
        }
    }
}

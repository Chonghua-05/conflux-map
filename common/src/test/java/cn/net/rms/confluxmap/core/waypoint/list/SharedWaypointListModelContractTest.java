package cn.net.rms.confluxmap.core.waypoint.list;

import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.shared.SharedWaypointClientState;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.shared.SharedWaypointGroup;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Contract scenarios against the shared-server adapter with simulated server echoes. */
class SharedWaypointListModelContractTest extends WaypointListModelContract {
    private static final UUID LOCAL_PLAYER = UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210");
    private static final UUID FOREIGN_PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000042");

    private final AtomicReference<SharedWaypointClientState.View> view = new AtomicReference<>(
        new SharedWaypointClientState.View(
            SharedWaypointClientState.State.ENABLED, List.of(), false, 0L, "world", 512, 64,
            true, List.of()
        )
    );
    private final AtomicLong revisions = new AtomicLong(0L);
    private final InMemoryPreferences preferences = new InMemoryPreferences();
    private final EchoGateway gateway = new EchoGateway();
    private final SharedWaypointListModel model = new SharedWaypointListModel(
        view::get, () -> 6, () -> true, () -> LOCAL_PLAYER, preferences, gateway,
        (key, args) -> args.length == 0 ? key : key + ":" + args[0]
    );

    @Override
    protected WaypointListModel model() {
        return model;
    }

    @Override
    protected GroupRef seedGroup(final String name) {
        assertTrue(model.createGroup(name));
        return model.groupByKey(gateway.lastGroupId.toString());
    }

    @Override
    protected WaypointRow seedWaypoint(final String name) {
        assertTrue(model.createWaypoint(draft(name, GroupRef.UNGROUPED_KEY)));
        return model.rowById(gateway.lastWaypointId);
    }

    /** Shared denials are per publisher: a foreign waypoint stays unmovable for this player. */
    @Override
    protected WaypointListModel denialModel() {
        return model;
    }

    @Override
    protected WaypointRow unmovableRow() {
        gateway.echoForeignWaypoint("Foreign");
        return model.rowById(gateway.lastWaypointId);
    }

    /**
     * Applies the same state changes the server's snapshot/delta flow would,
     * so the model's read side observes each mutation like a real echo.
     */
    private final class EchoGateway implements SharedWaypointMutations {
        private UUID lastGroupId;
        private UUID lastWaypointId;

        @Override
        public boolean create(final EditDraft draft, final UUID groupId) {
            lastWaypointId = UUID.randomUUID();
            publish(new SharedWaypoint(
                lastWaypointId, LOCAL_PLAYER, "Player", draft.name(), draft.dimensionId(),
                draft.x(), draft.y(), draft.z(), draft.colorArgb(), draft.type(),
                draft.iconItemId(), draft.markerLabel(), 0L, nextRevision(), groupId
            ));
            return true;
        }

        @Override
        public boolean update(final SharedWaypoint original, final EditDraft draft, final UUID groupId) {
            publish(new SharedWaypoint(
                original.id(), original.publisherId(), original.publisherName(), draft.name(),
                draft.dimensionId(), draft.x(), draft.y(), draft.z(), draft.colorArgb(),
                draft.type(), draft.iconItemId(), draft.markerLabel(),
                original.createdAtEpochMs(), nextRevision(), groupId
            ));
            return true;
        }

        @Override
        public boolean delete(final SharedWaypoint waypoint) {
            final List<SharedWaypoint> next = new ArrayList<>(view.get().list());
            next.removeIf(candidate -> candidate.id().equals(waypoint.id()));
            publishAll(next);
            return true;
        }

        @Override
        public boolean moveBatch(final List<SharedWaypoint> waypoints, final UUID groupId) {
            final List<SharedWaypoint> next = new ArrayList<>(view.get().list());
            for (final SharedWaypoint waypoint : waypoints) {
                for (int i = 0; i < next.size(); i++) {
                    if (next.get(i).id().equals(waypoint.id())) {
                        next.set(i, next.get(i).withGroupId(groupId));
                    }
                }
            }
            publishAll(next);
            return true;
        }

        @Override
        public boolean createGroup(final String name) {
            lastGroupId = UUID.randomUUID();
            publishGroup(new SharedWaypointGroup(
                lastGroupId, LOCAL_PLAYER, name, 0L, nextRevision()
            ));
            return true;
        }

        @Override
        public boolean renameGroup(final SharedWaypointGroup group, final String name) {
            publishGroup(new SharedWaypointGroup(
                group.id(), group.creatorId(), name, group.createdAtEpochMs(), nextRevision()
            ));
            return true;
        }

        @Override
        public boolean deleteGroup(final SharedWaypointGroup group) {
            final List<SharedWaypointGroup> groups = new ArrayList<>(view.get().groups());
            groups.removeIf(candidate -> candidate.id().equals(group.id()));
            final List<SharedWaypoint> next = new ArrayList<>();
            for (final SharedWaypoint waypoint : view.get().list()) {
                next.add(group.id().equals(waypoint.groupId())
                    ? waypoint.withGroupId(null)
                    : waypoint);
            }
            publish(next, groups);
            return true;
        }

        private void echoForeignWaypoint(final String name) {
            lastWaypointId = UUID.randomUUID();
            publish(new SharedWaypoint(
                lastWaypointId, FOREIGN_PLAYER, "Foreign", name, DIMENSION,
                5d, 64d, 5d, 0xFFFFFFFF, cn.net.rms.confluxmap.core.waypoint.Waypoint.Type.NORMAL,
                "", "", 0L, nextRevision(), null
            ));
        }

        private void publish(final SharedWaypoint waypoint) {
            final List<SharedWaypoint> next = new ArrayList<>(view.get().list());
            next.add(waypoint);
            publishAll(next);
        }

        private void publishGroup(final SharedWaypointGroup group) {
            final List<SharedWaypointGroup> groups = new ArrayList<>(view.get().groups());
            groups.add(group);
            publish(view.get().list(), groups);
        }

        private void publishAll(final List<SharedWaypoint> waypoints) {
            publish(waypoints, view.get().groups());
        }

        private void publish(
            final List<SharedWaypoint> waypoints,
            final List<SharedWaypointGroup> groups
        ) {
            final SharedWaypointClientState.View current = view.get();
            view.set(new SharedWaypointClientState.View(
                current.state(), List.copyOf(waypoints), current.operator(),
                revisions.get(), current.worldId(), current.maxWorld(), current.maxPlayer(),
                true, List.copyOf(groups)
            ));
        }

        private long nextRevision() {
            return revisions.incrementAndGet();
        }
    }

}

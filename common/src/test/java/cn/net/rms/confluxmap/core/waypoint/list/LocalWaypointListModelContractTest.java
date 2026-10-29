package cn.net.rms.confluxmap.core.waypoint.list;

import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointStore;
import java.util.List;
import java.util.UUID;

/** Contract scenarios against the local store adapter. */
class LocalWaypointListModelContractTest extends WaypointListModelContract {
    private static final WorldIdentity WORLD = new WorldIdentity("singleplayer", "world");
    private static final UUID LOCKED_ID = UUID.randomUUID();

    private final WaypointStore store = new WaypointStore(WORLD, new WaypointStore.State(
        List.of(), List.of(), true
    ));
    private final LocalWaypointListModel model = new LocalWaypointListModel(
        () -> store, List::of, () -> false, (key, args) -> key
    );
    private final LocalWaypointListModel readOnlyModel = new LocalWaypointListModel(
        () -> readOnlyStore(), List::of, () -> false, (key, args) -> key
    );

    @Override
    protected WaypointListModel model() {
        return model;
    }

    @Override
    protected GroupRef seedGroup(final String name) {
        assertTrue(model.createGroup(name));
        return model.groupByKey(name);
    }

    @Override
    protected WaypointRow seedWaypoint(final String name) {
        assertTrue(model.createWaypoint(draft(name, GroupRef.UNGROUPED_KEY)));
        return model.rowById(store.list().stream()
            .filter(waypoint -> waypoint.name.equals(name))
            .findFirst().orElseThrow().id);
    }

    /** Local denials are store-wide: a read-only store makes every row unmovable. */
    @Override
    protected WaypointListModel denialModel() {
        return readOnlyModel;
    }

    @Override
    protected WaypointRow unmovableRow() {
        return readOnlyModel.rowById(LOCKED_ID);
    }

    private WaypointStore readOnlyStore() {
        return new WaypointStore(WORLD, new WaypointStore.State(
            List.of(), List.of(lockedWaypoint()), false
        ));
    }

    private static Waypoint lockedWaypoint() {
        return new Waypoint(
            LOCKED_ID, "Locked", DIMENSION, 9d, 64d, 9d,
            0xFFFFFFFF, "", true, false, Waypoint.Type.NORMAL, 0L
        );
    }
}

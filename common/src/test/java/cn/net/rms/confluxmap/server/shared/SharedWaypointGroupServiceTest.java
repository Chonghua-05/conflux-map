package cn.net.rms.confluxmap.server.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointProto;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.shared.SharedWaypointGroup;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;

/** Group lifecycle and membership behavior of {@link SharedWaypointService}. */
class SharedWaypointGroupServiceTest {
    private static final Logger LOGGER = LogManager.getLogger("SharedWaypointGroupServiceTest");

    private static final SharedWaypointService.Actor OPERATOR =
        new SharedWaypointService.Actor(uuid(1), "Op", true);
    private static final SharedWaypointService.Actor PLAYER =
        new SharedWaypointService.Actor(uuid(2), "Player", false);
    private static final SharedWaypointService.Actor OTHER_PLAYER =
        new SharedWaypointService.Actor(uuid(3), "Other", false);

    @Test
    void createGroupPersistsPublishesAndReplaysIdempotently() {
        final Fixture fixture = fixture(SharedWaypointService.AccessPolicy.OWNER_MANAGED);
        final UUID operationId = uuid(100);

        final SharedWaypointService.MutationResult created =
            fixture.service.createGroup(PLAYER, new SharedWaypointService.GroupCreateRequest(
                operationId, 0L, "  村庄  "
            ));

        assertTrue(created.applied());
        assertEquals(1L, created.snapshot().revision());
        assertEquals(SharedWaypointStore.DeltaKind.GROUP_UPSERT, created.delta().kind());
        assertEquals(1, created.snapshot().groups().size());
        assertEquals("村庄", created.snapshot().groups().get(0).name());
        assertEquals(PLAYER.playerId(), created.snapshot().groups().get(0).creatorId());
        assertEquals(1, fixture.persistence.saves);

        final SharedWaypointService.MutationResult replayed =
            fixture.service.createGroup(PLAYER, new SharedWaypointService.GroupCreateRequest(
                operationId, 0L, "  村庄  "
            ));
        assertTrue(replayed.applied());
        assertTrue(replayed.replayed());
        assertEquals(1, fixture.persistence.saves);
    }

    @Test
    void createGroupIsForbiddenForNonOperatorsUnderOperatorOnlyPolicy() {
        final Fixture fixture = fixture(SharedWaypointService.AccessPolicy.OPERATOR_ONLY);

        final SharedWaypointService.MutationResult rejected =
            fixture.service.createGroup(PLAYER, new SharedWaypointService.GroupCreateRequest(
                uuid(101), 0L, "Villages"
            ));

        assertFalse(rejected.applied());
        assertEquals(SharedWaypointService.MutationError.FORBIDDEN, rejected.error());
        assertTrue(fixture.service.snapshot().groups().isEmpty());
    }

    @Test
    void createGroupRejectsBlankNamesRevisionConflictsAndQuota() {
        final Fixture fixture = fixture(SharedWaypointService.AccessPolicy.OPERATOR_ONLY);

        assertEquals(SharedWaypointService.MutationError.INVALID_REQUEST,
            fixture.service.createGroup(OPERATOR, new SharedWaypointService.GroupCreateRequest(
                uuid(110), 0L, "   "
            )).error());
        assertEquals(SharedWaypointService.MutationError.REVISION_CONFLICT,
            fixture.service.createGroup(OPERATOR, new SharedWaypointService.GroupCreateRequest(
                uuid(111), 9L, "Villages"
            )).error());

        for (int i = 0; i < SharedWaypointProto.MAX_SNAPSHOT_GROUPS; i++) {
            fixture.clock.advanceMillis(2_100L);
            final SharedWaypointService.MutationResult created =
                fixture.service.createGroup(OPERATOR, new SharedWaypointService.GroupCreateRequest(
                    uuid(1_000 + i), fixture.service.snapshot().revision(), "g" + i
                ));
            assertTrue(created.applied(), "group " + i + " should be created");
        }
        fixture.clock.advanceMillis(2_100L);
        assertEquals(SharedWaypointService.MutationError.GROUP_QUOTA_EXCEEDED,
            fixture.service.createGroup(OPERATOR, new SharedWaypointService.GroupCreateRequest(
                uuid(2_000), fixture.service.snapshot().revision(), "one too many"
            )).error());
    }

    @Test
    void renameGroupAllowsOperatorAndCreatorButNotOtherPlayers() {
        final Fixture fixture = fixture(SharedWaypointService.AccessPolicy.OWNER_MANAGED);
        final SharedWaypointGroup group = createGroup(fixture, PLAYER, "Villages");

        assertEquals(SharedWaypointService.MutationError.FORBIDDEN,
            rename(fixture, OTHER_PLAYER, group, "Hax").error());
        assertEquals(SharedWaypointService.MutationError.REVISION_CONFLICT,
            rename(
                fixture, PLAYER,
                new SharedWaypointGroup(
                    group.id(), group.creatorId(), group.name(), group.createdAtEpochMs(), 99L
                ),
                "Renamed"
            ).error());

        final SharedWaypointService.MutationResult renamed = rename(fixture, PLAYER, group, "Renamed");
        assertTrue(renamed.applied());
        assertEquals("Renamed", fixture.service.snapshot().groups().get(0).name());

        final SharedWaypointService.MutationResult operatorRenamed =
            rename(fixture, OPERATOR, fixture.service.snapshot().groups().get(0), "OperatorRenamed");
        assertTrue(operatorRenamed.applied());
        assertEquals("OperatorRenamed", fixture.service.snapshot().groups().get(0).name());
    }

    @Test
    void deleteGroupKeepsMemberWaypointsAndClearsTheirMembership() {
        final Fixture fixture = fixture(SharedWaypointService.AccessPolicy.OWNER_MANAGED);
        final SharedWaypointGroup group = createGroup(fixture, PLAYER, "Villages");
        final SharedWaypoint waypoint = createWaypoint(fixture, PLAYER, "Home");
        assign(fixture, PLAYER, waypoint, group.id());
        assertNotNull(fixture.service.snapshot().waypoints().get(0).groupId());

        final SharedWaypointService.MutationResult deleted = fixture.service.deleteGroup(
            PLAYER, new SharedWaypointService.GroupDeleteRequest(uuid(120), group.revision(), group.id())
        );

        assertTrue(deleted.applied());
        assertEquals(SharedWaypointStore.DeltaKind.GROUP_REMOVE, deleted.delta().kind());
        assertTrue(fixture.service.snapshot().groups().isEmpty());
        assertEquals(1, fixture.service.snapshot().waypoints().size());
        assertNull(fixture.service.snapshot().waypoints().get(0).groupId());
        assertEquals("Home", fixture.service.snapshot().waypoints().get(0).name());
    }

    @Test
    void setWaypointGroupAssignsAndClearsMembershipWithWaypointPermissions() {
        final Fixture fixture = fixture(SharedWaypointService.AccessPolicy.OWNER_MANAGED);
        final SharedWaypointGroup group = createGroup(fixture, PLAYER, "Villages");
        final SharedWaypoint waypoint = createWaypoint(fixture, PLAYER, "Home");
        final SharedWaypoint otherWaypoint = createWaypoint(fixture, OTHER_PLAYER, "Theirs");

        assertEquals(SharedWaypointService.MutationError.FORBIDDEN,
            assign(fixture, OTHER_PLAYER, waypoint, group.id()).error());
        assertEquals(SharedWaypointService.MutationError.NOT_FOUND,
            assign(fixture, PLAYER, waypoint, uuid(999)).error());

        final SharedWaypointService.MutationResult assigned =
            assign(fixture, PLAYER, waypoint, group.id());
        assertTrue(assigned.applied());
        assertEquals(group.id(), findWaypoint(fixture, waypoint.id()).groupId());
        assertEquals(SharedWaypointStore.DeltaKind.UPSERT, assigned.delta().kind());

        // The first assign bumped the waypoint revision, so the clear must expect the fresh one.
        final SharedWaypointService.MutationResult cleared =
            assign(fixture, PLAYER, findWaypoint(fixture, waypoint.id()), null);
        assertTrue(cleared.applied());
        assertNull(findWaypoint(fixture, waypoint.id()).groupId());

        final SharedWaypointService.MutationResult operatorAssigned =
            assign(fixture, OPERATOR, otherWaypoint, group.id());
        assertTrue(operatorAssigned.applied());
        assertEquals(group.id(), findWaypoint(fixture, otherWaypoint.id()).groupId());
    }

    @Test
    void updateKeepsGroupMembershipAcrossContentChanges() {
        final Fixture fixture = fixture(SharedWaypointService.AccessPolicy.OWNER_MANAGED);
        final SharedWaypointGroup group = createGroup(fixture, PLAYER, "Villages");
        final SharedWaypoint waypoint = createWaypoint(fixture, PLAYER, "Home");
        assign(fixture, PLAYER, waypoint, group.id());
        final SharedWaypoint grouped = findWaypoint(fixture, waypoint.id());

        final SharedWaypointService.MutationResult renamed = fixture.service.update(
            PLAYER, new SharedWaypointService.UpdateRequest(
                operationId(fixture), grouped.revision(), grouped.id(), "Renamed",
                DimensionId.OVERWORLD, 30d, 64d, -8d, 0xFF112233, Waypoint.Type.NORMAL
            )
        );

        assertTrue(renamed.applied());
        assertEquals("Renamed", findWaypoint(fixture, waypoint.id()).name());
        assertEquals(group.id(), findWaypoint(fixture, waypoint.id()).groupId());
        // UpsertS2C broadcasts delta.waypoint() verbatim, so the group must survive there too.
        assertEquals(group.id(), renamed.delta().waypoint().groupId());
        assertEquals(group.id(), fixture.persistence.saved.waypoints().get(0).groupId());
    }

    @Test
    void groupPersistenceFailureLeavesStateUnchangedAndUncached() {
        final Fixture fixture = fixture(SharedWaypointService.AccessPolicy.OWNER_MANAGED);
        final UUID operationId = uuid(130);

        fixture.persistence.fail = true;
        final SharedWaypointService.MutationResult failed =
            fixture.service.createGroup(PLAYER, new SharedWaypointService.GroupCreateRequest(
                operationId, 0L, "Villages"
            ));
        fixture.persistence.fail = false;
        final SharedWaypointService.MutationResult retried =
            fixture.service.createGroup(PLAYER, new SharedWaypointService.GroupCreateRequest(
                operationId, 0L, "Villages"
            ));

        assertEquals(SharedWaypointService.MutationError.PERSISTENCE_FAILED, failed.error());
        assertTrue(failed.snapshot().groups().isEmpty());
        assertTrue(retried.applied());
        assertFalse(retried.replayed());
        assertEquals(1, fixture.service.snapshot().groups().size());
    }

    @Test
    void sanitizeLoadedKeepsGroupsWhileQuarantiningInvalidWaypoints() {
        final SharedWaypointGroup group = new SharedWaypointGroup(
            uuid(500), PLAYER.playerId(), "Villages", 1L, 1L
        );
        final SharedWaypoint valid = new SharedWaypoint(
            uuid(501), PLAYER.playerId(), PLAYER.playerName(), "Kept", DimensionId.OVERWORLD,
            8d, 70d, -4d, 0xFF33AA66, Waypoint.Type.NORMAL, 1L, 1L, group.id()
        );
        final SharedWaypoint invalidDimension = new SharedWaypoint(
            uuid(502), PLAYER.playerId(), PLAYER.playerName(), "Dropped", DimensionId.END,
            8d, 70d, -4d, 0xFF33AA66, Waypoint.Type.NORMAL, 2L, 2L, group.id()
        );
        final SharedWaypointValidator validator = new SharedWaypointValidator(Map.of(
            DimensionId.OVERWORLD, new SharedWaypointValidator.HeightRange(0, 256)
        ));

        final SharedWaypointStore.Snapshot sanitized = SharedWaypointService.sanitizeLoaded(
            new SharedWaypointStore.Snapshot(2L, List.of(valid, invalidDimension), List.of(group)),
            validator, LOGGER
        );

        assertEquals(List.of(valid), sanitized.waypoints());
        assertEquals(List.of(group), sanitized.groups());
    }

    private static SharedWaypoint findWaypoint(
        final Fixture fixture,
        final UUID id
    ) {
        return fixture.service.snapshot().waypoints().stream()
            .filter(waypoint -> waypoint.id().equals(id))
            .findFirst()
            .orElseThrow();
    }

    private static UUID operationId(final Fixture fixture) {
        return uuid(fixture.operationIds().getAndIncrement());
    }

    private static SharedWaypointService.MutationResult rename(
        final Fixture fixture,
        final SharedWaypointService.Actor actor,
        final SharedWaypointGroup group,
        final String name
    ) {
        return fixture.service.renameGroup(actor, new SharedWaypointService.GroupRenameRequest(
            operationId(fixture), group.revision(), group.id(), name
        ));
    }

    private static SharedWaypointService.MutationResult assign(
        final Fixture fixture,
        final SharedWaypointService.Actor actor,
        final SharedWaypoint waypoint,
        final UUID groupId
    ) {
        return fixture.service.setWaypointGroup(actor, new SharedWaypointService.SetGroupRequest(
            operationId(fixture), waypoint.revision(), waypoint.id(), groupId
        ));
    }

    private static SharedWaypointGroup createGroup(
        final Fixture fixture,
        final SharedWaypointService.Actor actor,
        final String name
    ) {
        final SharedWaypointService.MutationResult created = fixture.service.createGroup(
            actor, new SharedWaypointService.GroupCreateRequest(
                operationId(fixture), fixture.service.snapshot().revision(), name
            )
        );
        assertTrue(created.applied());
        return created.snapshot().groups().get(created.snapshot().groups().size() - 1);
    }

    private static SharedWaypoint createWaypoint(
        final Fixture fixture,
        final SharedWaypointService.Actor actor,
        final String name
    ) {
        final SharedWaypointService.MutationResult created = fixture.service.create(
            actor,
            new SharedWaypointService.CreateRequest(
                operationId(fixture), fixture.service.snapshot().revision(), name, DimensionId.OVERWORLD,
                actor.playerId().getLeastSignificantBits(), 64d, -8d, 0xFF33AA66, Waypoint.Type.NORMAL
            )
        );
        assertTrue(created.applied());
        return created.delta().waypoint();
    }

    private static Fixture fixture(final SharedWaypointService.AccessPolicy accessPolicy) {
        final SharedWaypointStore store = SharedWaypointStore.empty();
        final MemoryPersistence persistence = new MemoryPersistence();
        final MutableClock clock = new MutableClock(10_000L);
        final AtomicLong ids = new AtomicLong(10_000L);
        final SharedWaypointValidator validator = new SharedWaypointValidator(Map.of(
            DimensionId.OVERWORLD, new SharedWaypointValidator.HeightRange(0, 256)
        ));
        final SharedWaypointService service = new SharedWaypointService(
            store, persistence, validator, clock, () -> uuid(ids.getAndIncrement()),
            new SharedWaypointService.Limits(100, 50, 1_000), accessPolicy, event -> { }, LOGGER
        );
        return new Fixture(service, persistence, clock, new AtomicLong(300L));
    }

    private static UUID uuid(final long value) {
        return new UUID(0L, value);
    }

    private record Fixture(
        SharedWaypointService service,
        MemoryPersistence persistence,
        MutableClock clock,
        AtomicLong operationIds
    ) {
    }

    private static final class MemoryPersistence implements SharedWaypointPersistence {
        private int saves;
        private boolean fail;
        private SharedWaypointStore.Snapshot saved = new SharedWaypointStore.Snapshot(0, List.of());

        @Override
        public SharedWaypointStore.Snapshot load() {
            return saved;
        }

        @Override
        public void save(final SharedWaypointStore.Snapshot snapshot) throws IOException {
            saves++;
            if (fail) {
                throw new IOException("injected failure");
            }
            saved = snapshot;
        }
    }

    private static final class MutableClock extends Clock {
        private long epochMs;

        private MutableClock(final long epochMs) {
            this.epochMs = epochMs;
        }

        private void advanceMillis(final long millis) {
            epochMs += millis;
        }

        @Override
        public long millis() {
            return epochMs;
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return java.time.Instant.ofEpochMilli(epochMs);
        }
    }
}

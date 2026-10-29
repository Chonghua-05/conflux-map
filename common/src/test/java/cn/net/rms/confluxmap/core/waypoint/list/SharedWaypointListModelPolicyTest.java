package cn.net.rms.confluxmap.core.waypoint.list;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointClientState;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.shared.SharedWaypointGroup;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Permission policy shared by the model and the Minecraft-facing client. */
class SharedWaypointListModelPolicyTest {
    private static final UUID PUBLISHER = UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000042");

    @Test
    void managementIsAllowedForOperatorsAndEnabledOwners() {
        assertTrue(SharedWaypointListModel.canManage(waypoint(), false, true, PUBLISHER));
        assertTrue(SharedWaypointListModel.canManage(waypoint(), true, false, STRANGER));

        assertFalse(SharedWaypointListModel.canManage(waypoint(), false, false, PUBLISHER));
        assertFalse(SharedWaypointListModel.canManage(waypoint(), false, true, STRANGER));
        assertFalse(SharedWaypointListModel.canManage(waypoint(), false, true, null));
        assertFalse(SharedWaypointListModel.canManage(null, true, true, PUBLISHER));
    }

    @Test
    void operatorOnlyPolicyHasADedicatedCreateDenialReason() {
        assertEquals(
            "confluxmap.shared_waypoints.operator_only",
            SharedWaypointListModel.createDisabledReasonKey(
                SharedWaypointClientState.State.ENABLED, true, false, false
            )
        );
        assertEquals(
            "confluxmap.screen.waypoint.public_unavailable",
            SharedWaypointListModel.createDisabledReasonKey(
                SharedWaypointClientState.State.ENABLED, false, false, false
            )
        );
        assertEquals(
            "confluxmap.shared_waypoints.disabled_by_server",
            SharedWaypointListModel.createDisabledReasonKey(
                SharedWaypointClientState.State.SUPPORTED_DISABLED, false, false, false
            )
        );
        assertNull(SharedWaypointListModel.createDisabledReasonKey(
            SharedWaypointClientState.State.ENABLED, true, true, false
        ));
        assertNull(SharedWaypointListModel.createDisabledReasonKey(
            SharedWaypointClientState.State.ENABLED, true, false, true
        ));
    }

    @Test
    void enabledPlayersGetAnOwnerOnlyReasonForAnotherPlayersWaypoint() {
        assertEquals(
            "confluxmap.shared_waypoints.owner_only",
            SharedWaypointListModel.managementDisabledReasonKey(
                SharedWaypointClientState.State.ENABLED, true, false, true,
                waypoint(), STRANGER
            )
        );
        assertNull(
            SharedWaypointListModel.managementDisabledReasonKey(
                SharedWaypointClientState.State.ENABLED, true, false, true,
                waypoint(), PUBLISHER
            )
        );
    }

    @Test
    void groupManagementIsAllowedForOperatorsAndEnabledCreators() {
        final UUID creator = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        final SharedWaypointGroup group = new SharedWaypointGroup(
            UUID.fromString("0f0e0d0c-0b0a-4909-8877-665544332211"),
            creator,
            "Villages",
            1_721_555_200_000L,
            3L
        );

        assertTrue(SharedWaypointListModel.canManageGroup(group, false, true, creator));
        assertTrue(SharedWaypointListModel.canManageGroup(group, true, false, STRANGER));
        assertFalse(SharedWaypointListModel.canManageGroup(group, false, true, STRANGER));
        assertFalse(SharedWaypointListModel.canManageGroup(group, false, false, creator));
        assertFalse(SharedWaypointListModel.canManageGroup(null, true, true, creator));

        assertEquals(
            "confluxmap.shared_waypoints.group_owner_only",
            SharedWaypointListModel.groupManageDisabledReasonKey(
                SharedWaypointClientState.State.ENABLED, true, false, true, group, STRANGER
            )
        );
        assertNull(SharedWaypointListModel.groupManageDisabledReasonKey(
            SharedWaypointClientState.State.ENABLED, true, false, true, group, creator
        ));
        assertEquals(
            "confluxmap.screen.waypoint.public_unavailable",
            SharedWaypointListModel.groupManageDisabledReasonKey(
                SharedWaypointClientState.State.ENABLED, true, false, true, null, creator
            )
        );
    }

    private static SharedWaypoint waypoint() {
        return new SharedWaypoint(
            UUID.fromString("12345678-1234-5678-9abc-def012345678"),
            PUBLISHER,
            "Player",
            "Spawn",
            DimensionId.OVERWORLD,
            1d,
            64d,
            2d,
            0xFFFFFFFF,
            Waypoint.Type.NORMAL,
            1L,
            37L
        );
    }
}

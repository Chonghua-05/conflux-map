package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Regression coverage for location actions on uncaptured/void map columns. */
class FullscreenMapLocationMenuTest {
    @Test
    void waypointAndShareStayUsableWithoutAHeightEstimate() {
        assertTrue(FullscreenMapLocationMenu.actionEnabled(
            FullscreenMapLocationMenu.Action.SET_WAYPOINT, true, false, false
        ));
        assertTrue(FullscreenMapLocationMenu.actionEnabled(
            FullscreenMapLocationMenu.Action.SHARE_LOCATION, true, false, false
        ));
    }

    @Test
    void teleportOnlyDependsOnTheCommandTreeWhenHeightIsUnknown() {
        assertTrue(FullscreenMapLocationMenu.actionEnabled(
            FullscreenMapLocationMenu.Action.TELEPORT, true, false, true
        ));
    }
}

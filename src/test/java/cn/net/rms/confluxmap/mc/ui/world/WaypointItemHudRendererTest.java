package cn.net.rms.confluxmap.mc.ui.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class WaypointItemHudRendererTest {
    @Test
    void publishesAnImmutableFrameSnapshot() {
        final WaypointItemHudRenderer renderer = new WaypointItemHudRenderer(null, null);
        final List<WaypointItemHudRenderer.Label> source = new ArrayList<>();
        source.add(label());

        renderer.publish(source);
        source.clear();

        assertEquals(1, renderer.snapshot().size());
        assertThrows(
            UnsupportedOperationException.class,
            () -> renderer.snapshot().add(label())
        );
    }

    @Test
    void publishingANewFrameReplacesTheOldOne() {
        final WaypointItemHudRenderer renderer = new WaypointItemHudRenderer(null, null);
        renderer.publish(List.of(label()));

        renderer.publish(List.of());

        assertEquals(List.of(), renderer.snapshot());
    }

    @Test
    void playerLabelsCarryThePortraitIdentity() {
        final UUID playerId = UUID.randomUUID();
        final WaypointItemHudRenderer.Label base = label();

        final WaypointItemHudRenderer.Label player = new WaypointItemHudRenderer.Label(
            base.waypoint(),
            base.distance3d(),
            base.projectionDistance(),
            base.animationProgress(),
            base.visibilityAlpha(),
            base.selected(),
            playerId
        );

        assertEquals(playerId, player.playerId());
    }

    private static WaypointItemHudRenderer.Label label() {
        return new WaypointItemHudRenderer.Label(
            new WaypointRenderEntry(
                UUID.randomUUID(), "diamond", DimensionId.OVERWORLD,
                0.0, 64.0, 0.0, 0xFFFFFFFF,
                "minecraft:diamond", "", Waypoint.Type.NORMAL,
                WaypointRenderEntry.Source.LOCAL, false
            ),
            10.0,
            100.0,
            0f,
            1f,
            false
        );
    }

}

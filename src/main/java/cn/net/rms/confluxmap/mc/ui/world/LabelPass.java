package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;

/**
 * Owns the label pass's per-version state brackets and submit handles, dispatching each
 * label to {@link WaypointLabelPainter}. {@link #beginPass} and {@link #endPass} bracket
 * one label render: before 1.21.9 they reinstall the world projection and the view matrix
 * captured at LAST (the same END state repair the beam pass makes), while the in-pass
 * phases from 1.21.9 to 26.1 only save the global ModelView through {@link
 * RenderUtil#pushWorldHudModelView} so the billboard keeps the world view rotation vanilla
 * installed. Inside that bracket, {@link #beginLabels}/{@link #endLabels} wrap the label
 * loop: before 1.21.5 the labels disable depth testing (world content must never hide
 * player-facing navigation information) and the shared entity vertex consumers are flushed
 * once after the loop on every pre-26.2 version.
 */
final class LabelPass implements AutoCloseable {
    private final WaypointLabelPainter labelPainter;
    /** The pass frame, valid between {@link #beginPass} and {@link #endPass}. */
    private OverlayFrame frame;

    static LabelPass of(final MinecraftClient client, final ConfluxConfig config) {
        return new LabelPass(client, config);
    }

    private LabelPass(final MinecraftClient client, final ConfluxConfig config) {
        this.labelPainter = new WaypointLabelPainter(client, config);
    }

    void beginPass(final OverlayFrame frame) {
        this.frame = frame;
        //#if MC<12109
        RenderUtil.applyWorldProjection(frame.projectionMatrix);
        RenderUtil.pushWorldViewSnapshot();
        //#elseif MC<260200
        //$$ RenderUtil.pushWorldHudModelView();
        //#endif
    }

    void beginLabels() {
        //#if MC<12105
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        //#endif
    }

    /**
     * Draws one label. Layout, styling, and scaling stay in {@link WaypointLabelPainter};
     * this only hands over the version-correct submit handles and matrices.
     */
    void drawLabel(
        final double worldX,
        final double worldY,
        final double worldZ,
        final WaypointRenderEntry waypoint,
        final double distance3d,
        final double projectionDistance,
        final float animationProgress,
        final float visibilityAlpha,
        final boolean selected
    ) {
        //#if MC>=260200
        //$$ labelPainter.drawLabel(
        //$$     frame.matrices, frame.labelSubmits, frame.cameraRotation,
        //$$     frame.cameraPos, worldX, worldY, worldZ, waypoint,
        //$$     distance3d, projectionDistance, animationProgress,
        //$$     visibilityAlpha, selected
        //$$ );
        //#elseif MC>=260100
        //$$ labelPainter.drawLabel(
        //$$     frame.matrices, frame.immediate, frame.labelSubmits, frame.camera,
        //$$     frame.cameraPos, worldX, worldY, worldZ,
        //$$     waypoint, distance3d, projectionDistance, animationProgress,
        //$$     visibilityAlpha, selected
        //$$ );
        //#elseif MC>=12109
        //$$ labelPainter.drawLabel(
        //$$     frame.matrices, frame.immediate, frame.labelQueue, frame.camera,
        //$$     frame.cameraPos, worldX, worldY, worldZ,
        //$$     waypoint, distance3d, projectionDistance, animationProgress,
        //$$     visibilityAlpha, selected
        //$$ );
        //#else
        labelPainter.drawLabel(
            frame.matrices, frame.immediate, frame.camera,
            frame.cameraPos, worldX, worldY, worldZ,
            waypoint, distance3d, projectionDistance, animationProgress,
            visibilityAlpha, selected
        );
        //#endif
    }

    void endLabels() {
        //#if MC<260200
        frame.immediate.draw();
        //#endif
    }

    void endPass() {
        //#if MC<12105
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        //#endif
        //#if MC<12109
        RenderUtil.popModelView();
        RenderUtil.restoreProjection();
        //#elseif MC<260200
        //$$ RenderUtil.popModelView();
        //#endif
        frame = null;
    }

    /**
     * The pass brackets restore every state they touch inline, so nothing here outlives
     * {@link #endPass}; close keeps the seam closeable as per-version native state moves
     * in behind it.
     */
    @Override
    public void close() {
    }
}

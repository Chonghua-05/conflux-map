package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.mc.render.GuiShapes;
import cn.net.rms.confluxmap.mc.render.WorldMatrices;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.core.waypoint.WaypointVerticalRelation;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;

/**
 * Owns the label pass's per-version state brackets, billboard transform, and submissions.
 * {@link #beginPass} and {@link #endPass} bracket one label render: before 1.21.9 they
 * reinstall the world projection and the view matrix captured at LAST (the same END state
 * repair the beam pass makes), while the in-pass phases from 1.21.9 to 26.1 only save the
 * global ModelView through {@link WorldMatrices#pushWorldHudModelView} so the billboard keeps
 * the world view rotation vanilla installed. Inside that bracket, {@link #beginLabels}/
 * {@link #endLabels} wrap the label loop: before 1.21.5 the labels disable depth testing
 * (world content must never hide player-facing navigation information) and the shared
 * entity vertex consumers are flushed once after the loop on every pre-26.2 version. The
 * layout itself arrives from {@link WaypointLabelPainter}; this class applies the camera
 * rotation and submits each quad/text run through the version's pipeline.
 */
final class LabelPass implements WaypointLabelPainter.Sink {
    private final Minecraft client;
    private final WaypointLabelPainter labelPainter;
    /** The pass frame, valid between {@link #beginPass} and {@link #endPass}. */
    private OverlayFrame frame;

    static LabelPass of(final Minecraft client, final ConfluxConfig config) {
        return new LabelPass(client, config);
    }

    private LabelPass(final Minecraft client, final ConfluxConfig config) {
        this.client = client;
        this.labelPainter = new WaypointLabelPainter(client, config);
    }

    void beginPass(final OverlayFrame frame) {
        this.frame = frame;
        WorldMatrices.pushWorldHudModelView();
    }

    void beginLabels() {
    }

    void drawLabel(
        final double worldX,
        final double worldY,
        final double worldZ,
        final WaypointRenderEntry waypoint,
        final double distance3d,
        final double projectionDistance,
        final float animationProgress,
        final float visibilityAlpha,
        final boolean selected,
        final WaypointVerticalRelation verticalRelation
    ) {
        labelPainter.drawLabel(
            frame.matrices, frame.cameraPos, worldX, worldY, worldZ,
            waypoint, distance3d, projectionDistance, animationProgress,
            visibilityAlpha, selected, verticalRelation, this
        );
    }

    /**
     * The billboard rotation in its three forms - a matrix multiply on 26.3, which dropped
     * quaternion poses; a quaternion multiply on 26.2; the camera's rotation everywhere
     * earlier - then the label scale. Vanilla's modern billboard needs the positive X sign
     * from 1.21 on; earlier versions cull their quads unless X is negative.
     */
    @Override
    public void billboard(final PoseStack matrices, final float scale) {
        matrices.mulPose(frame.camera.rotation());
        matrices.scale(scale, -scale, scale);
    }

    @Override
    public void rect(final PoseStack matrices, final float x, final float y, final float width, final float height, final int color) {
        GuiShapes.fillRect3D(matrices, x, y, width, height, color);
    }

    /**
     * The height badge's triangles: immediate GuiShapes before 26.2, recorded like the
     * plates from 26.2 on. 26.3 dropped the plate's see-through render type, so its
     * fallback is debugQuads (position+color, unculled, depth-tested). Both windings are
     * emitted wherever the render type's culling is unknown.
     */
    @Override
    public void triangle(
        final PoseStack matrices,
        final float x0, final float y0,
        final float x1, final float y1,
        final float x2, final float y2,
        final int color
    ) {
        GuiShapes.fillTriangle(matrices, x0, y0, x1, y1, x2, y2, color);
    }

    @Override
    public void text(final PoseStack matrices, final String text, final float x, final float y, final int color) {
        drawSeeThroughText(
            client.font, text, x, y, color, matrices, frame.buffers,
            WaypointLabelStyle.LABEL_LIGHT
        );
    }

    void endLabels() {
        frame.buffers.endBatch();
    }

    void endPass() {
        WorldMatrices.popModelView();
        frame = null;
    }


    /** Draws fully-lit marker text through the versioned text-layer argument. */
    private static void drawSeeThroughText(
        final Font textRenderer,
        final String text,
        final float x,
        final float y,
        final int color,
        final PoseStack matrices,
        final MultiBufferSource.BufferSource immediate,
        final int light
    ) {
        textRenderer.drawInBatch(
            text, x, y, color, false, matrices.last().pose(), immediate,
            Font.DisplayMode.SEE_THROUGH, 0, light
        );
    }
}

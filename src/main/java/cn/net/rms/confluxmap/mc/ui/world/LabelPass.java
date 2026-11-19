package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.mc.render.WorldMatrices;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.core.waypoint.WaypointVerticalRelation;
import com.mojang.blaze3d.systems.RenderSystem;
import cn.net.rms.confluxmap.neoforge.compat.FabricOrderedSubmitNodeCollector;
import cn.net.rms.confluxmap.neoforge.compat.SubmitRenderPhases;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.Minecraft;
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
    private OrderedSubmitNodeCollector plateSubmits;
    private OrderedSubmitNodeCollector textSubmits;

    static LabelPass of(final Minecraft client, final ConfluxConfig config) {
        return new LabelPass(client, config);
    }

    private LabelPass(final Minecraft client, final ConfluxConfig config) {
        this.client = client;
        this.labelPainter = new WaypointLabelPainter(client, config);
    }

    void beginPass(final OverlayFrame frame) {
        this.frame = frame;
    }

    void beginLabels() {
        // The collector records instead of drawing: plates go into order(0) and text into
        // order(1) so both render this frame, matching the painter's layering.
        plateSubmits = frame.labelSubmits.order(0);
        textSubmits = frame.labelSubmits.order(1);
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
        matrices.mulPose(new org.joml.Matrix4f().rotation(frame.cameraRotation));
        matrices.scale(scale, -scale, scale);
    }

    @Override
    public void rect(final PoseStack matrices, final float x, final float y, final float width, final float height, final int color) {
        submitRect(matrices, x, y, width, height, color);
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
        plateSubmits.submitCustomGeometry(
            matrices, RenderTypes.debugQuads(),
            (pose, vertices) -> emitWoundTriangle(pose, vertices, x0, y0, x1, y1, x2, y2, color)
        );
    }

    @Override
    public void text(final PoseStack matrices, final String text, final float x, final float y, final int color) {
        submitText(matrices, text, x, y, color);
    }

    void endLabels() {
    }

    void endPass() {
        frame = null;
    }

    /** One triangle as two degenerate quads with opposite winding (QUADS topology). */
    private static void emitWoundTriangle(
        final PoseStack.Pose pose,
        final VertexConsumer vertices,
        final float x0, final float y0,
        final float x1, final float y1,
        final float x2, final float y2,
        final int color
    ) {
        vertices.addVertex(pose, x0, y0, 0f).setColor(color).setLight(WaypointLabelStyle.LABEL_LIGHT);
        vertices.addVertex(pose, x1, y1, 0f).setColor(color).setLight(WaypointLabelStyle.LABEL_LIGHT);
        vertices.addVertex(pose, x2, y2, 0f).setColor(color).setLight(WaypointLabelStyle.LABEL_LIGHT);
        vertices.addVertex(pose, x2, y2, 0f).setColor(color).setLight(WaypointLabelStyle.LABEL_LIGHT);
        vertices.addVertex(pose, x2, y2, 0f).setColor(color).setLight(WaypointLabelStyle.LABEL_LIGHT);
        vertices.addVertex(pose, x1, y1, 0f).setColor(color).setLight(WaypointLabelStyle.LABEL_LIGHT);
        vertices.addVertex(pose, x0, y0, 0f).setColor(color).setLight(WaypointLabelStyle.LABEL_LIGHT);
        vertices.addVertex(pose, x0, y0, 0f).setColor(color).setLight(WaypointLabelStyle.LABEL_LIGHT);
    }
    
    private void submitRect(
        final PoseStack matrices,
        final float x,
        final float y,
        final float width,
        final float height,
        final int color
    ) {
        // 26.2 has no background submit yet: the plate rides the vanilla text-background
        // render type through the custom-geometry path 26.3 replaced.
        plateSubmits.submitCustomGeometry(
            matrices,
            net.minecraft.client.renderer.rendertype.RenderTypes.textBackgroundSeeThrough(),
            (pose, buffer) -> {
                final float x1 = x + width;
                final float y1 = y + height;
                buffer.addVertex(pose, x, y, 0f).setColor(color)
                    .setLight(WaypointLabelStyle.LABEL_LIGHT);
                buffer.addVertex(pose, x, y1, 0f).setColor(color)
                    .setLight(WaypointLabelStyle.LABEL_LIGHT);
                buffer.addVertex(pose, x1, y1, 0f).setColor(color)
                    .setLight(WaypointLabelStyle.LABEL_LIGHT);
                buffer.addVertex(pose, x1, y1, 0f).setColor(color)
                    .setLight(WaypointLabelStyle.LABEL_LIGHT);
                buffer.addVertex(pose, x1, y, 0f).setColor(color)
                    .setLight(WaypointLabelStyle.LABEL_LIGHT);
                buffer.addVertex(pose, x, y, 0f).setColor(color)
                    .setLight(WaypointLabelStyle.LABEL_LIGHT);
            }
        );
    }
    
    private void submitText(
        final PoseStack matrices,
        final String text,
        final float x,
        final float y,
        final int color
    ) {
        textSubmits.submitText(
            matrices, x, y, Component.literal(text).getVisualOrderText(),
            false, Font.DisplayMode.SEE_THROUGH, WaypointLabelStyle.LABEL_LIGHT, color, 0, 0
        );
    }

}
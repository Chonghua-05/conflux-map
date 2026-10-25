package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
//#if MC>=260200
//$$ import net.fabricmc.fabric.api.client.rendering.v1.FabricOrderedSubmitNodeCollector;
//$$ import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases;
//$$ import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
//$$ import net.minecraft.client.renderer.rendertype.RenderTypes;
//#endif
//#if MC>=260300
//$$ import com.mojang.blaze3d.vertex.VertexConsumer;
//#endif
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** Stateless drawing of the in-world waypoint beams; all geometry arrives as parameters. */
final class WaypointBeamPainter {
    private static final double BEAM_HALF_WIDTH = 0.18;
    private static final float BEAM_CORE_ALPHA = 0.55f;
    /** Same near-camera fade-in constant as the label (waypoint-ux.md S6), applied to horizontal distance from the beam column. */
    private static final double BEAM_NEAR_FADE_BLOCKS = 5.0;
    /** Beam alpha never drops below this fraction of {@link #BEAM_CORE_ALPHA} even at the render-distance edge - "intensifies as you approach" without vanishing far away. */
    private static final float BEAM_FAR_FLOOR = 0.30f;

    private WaypointBeamPainter() {
    }

    /**
     * Square-tube beam (deliverable A's "4-sided prism" option) spanning the dimension's
     * full vertical range at the waypoint's X/Z, independent of the waypoint's own Y
     * (waypoint-ux.md S6: this is a deliberate simplification the reference implementation
     * also makes - a full-height column is trivial to draw and visible from anywhere at
     * that X/Z). Drawn double-sided (no back-face culling, see the caller) so the tube
     * reads correctly from inside or outside.
     */
    //#if MC>=260300
    //$$ static void drawBeam(
    //$$     final PoseStack matrices,
    //$$     final FabricOrderedSubmitNodeCollector beamSubmits,
    //$$     final Vec3 cameraPos,
    //#else
    static void drawBeam(
        final MatrixStack matrices,
        final Vec3d cameraPos,
    //#endif
        final double worldX,
        final double worldZ,
        final double bottomY,
        final double topY,
        final int colorArgb,
        final double horizontalDistance,
        final double maxDistance,
        final float visibilityAlpha
    ) {
        final float nearFade = (float) MathHelper.clamp(horizontalDistance / BEAM_NEAR_FADE_BLOCKS, 0.0, 1.0);
        final float farFactor = (float) MathHelper.clamp(1.0 - horizontalDistance / maxDistance, 0.0, 1.0);
        final float intensify = BEAM_FAR_FLOOR + (1f - BEAM_FAR_FLOOR) * farFactor;
        final float alpha = BEAM_CORE_ALPHA * nearFade * intensify * visibilityAlpha;
        if (alpha <= 0.01f) {
            return;
        }
        final int color = Argb.pack(Math.round(alpha * 255f), Argb.red(colorArgb), Argb.green(colorArgb), Argb.blue(colorArgb));

        matrices.push();
        matrices.translate(worldX - cameraPos.x, -cameraPos.y, worldZ - cameraPos.z);
        final float h = (float) BEAM_HALF_WIDTH;
        final float bottom = (float) bottomY;
        final float top = (float) topY;
        //#if MC>=260300
        //$$ final PoseStack.Pose pose = matrices.last().copy();
        //$$ submitBeamSide(beamSubmits, pose, -h, -h, h, -h, bottom, top, color);
        //$$ submitBeamSide(beamSubmits, pose, h, -h, h, h, bottom, top, color);
        //$$ submitBeamSide(beamSubmits, pose, h, h, -h, h, bottom, top, color);
        //$$ submitBeamSide(beamSubmits, pose, -h, h, -h, -h, bottom, top, color);
        //#else
        drawBeamSide(matrices, -h, -h, h, -h, bottom, top, color);
        drawBeamSide(matrices, h, -h, h, h, bottom, top, color);
        drawBeamSide(matrices, h, h, -h, h, bottom, top, color);
        drawBeamSide(matrices, -h, h, -h, -h, bottom, top, color);
        //#endif
        matrices.pop();
    }

    /** One side face of the beam tube, from local (x0,z0) to (x1,z1), spanning bottom..top. */
    private static void drawBeamSide(
        final MatrixStack matrices,
        final float x0, final float z0,
        final float x1, final float z1,
        final float bottom, final float top,
        final int color
    ) {
        RenderUtil.fillTriangle3D(matrices, x0, bottom, z0, x1, bottom, z1, x1, top, z1, color);
        RenderUtil.fillTriangle3D(matrices, x0, bottom, z0, x1, top, z1, x0, top, z0, color);
    }

    //#if MC>=260300
    //$$ /**
    //$$  * {@link #drawBeamSide} as a submitted custom-geometry node in the AFTER_TERRAIN phase:
    //$$  * 26.3 keeps one render pass open across classic-translucent terrain, so geometry must be
    //$$  * handed to vanilla's submit pipeline instead of drawing it on the spot. Consecutive nodes
    //$$  * batch into a single staged draw through the dragon-rays RenderType, the same pipeline the
    //$$  * immediate path draws with on older versions.
    //$$  */
    //$$ private static void submitBeamSide(
    //$$     final FabricOrderedSubmitNodeCollector beamSubmits,
    //$$     final PoseStack.Pose pose,
    //$$     final float x0, final float z0,
    //$$     final float x1, final float z1,
    //$$     final float bottom, final float top,
    //$$     final int color
    //$$ ) {
    //$$     beamSubmits.submitCustom(SubmitRenderPhases.AFTER_TERRAIN, new CustomFeatureRenderer.Submit(
    //$$         pose, RenderTypes.dragonRays(),
    //$$         (transform, vertices) -> {
    //$$             emitBeamTriangle(transform, vertices, x0, bottom, z0, x1, bottom, z1, x1, top, z1, color);
    //$$             emitBeamTriangle(transform, vertices, x0, bottom, z0, x1, top, z1, x0, top, z0, color);
    //$$         }
    //$$     ));
    //$$ }
    //$$
    //$$ /** One triangle written twice with opposite winding, the double-sided emission the immediate path uses. */
    //$$ private static void emitBeamTriangle(
    //$$     final PoseStack.Pose pose,
    //$$     final VertexConsumer vertices,
    //$$     final float x0, final float y0, final float z0,
    //$$     final float x1, final float y1, final float z1,
    //$$     final float x2, final float y2, final float z2,
    //$$     final int color
    //$$ ) {
    //$$     vertices.addVertex(pose, x0, y0, z0).setColor(color);
    //$$     vertices.addVertex(pose, x1, y1, z1).setColor(color);
    //$$     vertices.addVertex(pose, x2, y2, z2).setColor(color);
    //$$     vertices.addVertex(pose, x2, y2, z2).setColor(color);
    //$$     vertices.addVertex(pose, x1, y1, z1).setColor(color);
    //$$     vertices.addVertex(pose, x0, y0, z0).setColor(color);
    //$$ }
    //#endif
}

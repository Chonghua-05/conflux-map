package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.mc.render.RenderUtil;
import com.mojang.blaze3d.systems.RenderSystem;
//#if MC>=260300
//$$ import com.mojang.blaze3d.vertex.VertexConsumer;
//$$ import net.fabricmc.fabric.api.client.rendering.v1.FabricOrderedSubmitNodeCollector;
//$$ import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases;
//$$ import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
//$$ import net.minecraft.client.renderer.rendertype.RenderTypes;
//#endif
import net.minecraft.client.util.math.MatrixStack;

/**
 * Owns the beam pass's per-version state brackets and submission. {@link #beginPass} and
 * {@link #endPass} bracket one beam render: before 1.21.5 the beam needs depth-test-on,
 * depth-write-off, cull-off; before 1.21.9 it also reinstalls the world projection and the
 * view matrix captured at LAST (END runs after a deferred pack's finalize, which leaves the
 * global projection pointing at composite passes and after vanilla popped the world view);
 * every version draws additively through {@link RenderUtil#beginAdditiveTriangles()}, whose
 * 1.21.8 fog-slice bracket stays inside RenderUtil. The beam geometry itself arrives from
 * {@link WaypointBeamPainter}; this class only emits it - immediate triangles before 26.3,
 * submitted custom nodes in the AFTER_TERRAIN phase on 26.3, where the open translucent
 * pass forbids issuing draws on the spot.
 */
final class BeamPass implements AutoCloseable, WaypointBeamPainter.Sink {
    /** The pass frame, valid between {@link #beginPass} and {@link #endPass}. */
    private OverlayFrame frame;

    static BeamPass of() {
        return new BeamPass();
    }

    private BeamPass() {
    }

    void beginPass(final OverlayFrame frame) {
        this.frame = frame;
        //#if MC<12105
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        //#endif
        //#if MC<12109
        RenderUtil.applyWorldProjection(frame.projectionMatrix);
        RenderUtil.pushWorldViewSnapshot();
        //#endif
        RenderUtil.beginAdditiveTriangles();
    }

    void draw(
        final double worldX,
        final double worldZ,
        final double bottomY,
        final double topY,
        final int colorArgb,
        final double horizontalDistance,
        final double maxDistance,
        final float visibilityAlpha
    ) {
        WaypointBeamPainter.drawBeam(
            this, frame.matrices, frame.cameraPos, worldX, worldZ, bottomY, topY,
            colorArgb, horizontalDistance, maxDistance, visibilityAlpha
        );
    }

    /**
     * One tube face as two triangles with opposite winding, the double-sided emission the
     * immediate path uses; on 26.3 the two triangles ride one submitted node and vanilla
     * batches consecutive nodes into a single staged draw through the dragon-rays
     * RenderType, the same pipeline the immediate path draws with on older versions.
     */
    @Override
    public void beamSide(
        final MatrixStack matrices,
        final float x0, final float z0,
        final float x1, final float z1,
        final float bottom, final float top,
        final int color
    ) {
        //#if MC>=260300
        //$$ submitBeamSide(frame.beamSubmits, matrices.last().copy(), x0, z0, x1, z1, bottom, top, color);
        //#else
        RenderUtil.fillTriangle3D(matrices, x0, bottom, z0, x1, bottom, z1, x1, top, z1, color);
        RenderUtil.fillTriangle3D(matrices, x0, bottom, z0, x1, top, z1, x0, top, z0, color);
        //#endif
    }

    void endPass() {
        RenderUtil.restoreDefaultBlend();
        //#if MC<12109
        RenderUtil.popModelView();
        RenderUtil.restoreProjection();
        //#endif
        //#if MC<12105
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        //#endif
        frame = null;
    }

    //#if MC>=260300
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

    /**
     * The pass brackets restore every state they touch inline, so nothing here outlives
     * {@link #endPass}; close keeps the seam closeable as per-version native state moves
     * in behind it.
     */
    @Override
    public void close() {
    }
}

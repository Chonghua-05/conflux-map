package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.mc.render.RenderUtil;
import com.mojang.blaze3d.systems.RenderSystem;

/**
 * Owns the beam pass's per-version state brackets and dispatches each beam column to
 * {@link WaypointBeamPainter} with the version-correct parameters. {@link #beginPass} and
 * {@link #endPass} bracket one beam render: before 1.21.5 the beam needs depth-test-on,
 * depth-write-off, cull-off; before 1.21.9 it also reinstalls the world projection and the
 * view matrix captured at LAST (END runs after a deferred pack's finalize, which leaves the
 * global projection pointing at composite passes and after vanilla popped the world view);
 * every version draws additively through {@link RenderUtil#beginAdditiveTriangles()}, whose
 * 1.21.8 fog-slice bracket stays inside RenderUtil.
 */
final class BeamPass implements AutoCloseable {
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

    /**
     * Draws one beam column. The alpha math and geometry stay in {@link WaypointBeamPainter};
     * on 26.3 this only hands the pass its submit collector, because 26.3 keeps one render
     * pass open across the translucent hook and the beam must ride the submit pipeline
     * instead of issuing its own passes.
     */
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
        //#if MC>=260300
        //$$ WaypointBeamPainter.drawBeam(
        //$$     frame.matrices, frame.beamSubmits, frame.cameraPos, worldX, worldZ, bottomY, topY,
        //$$     colorArgb, horizontalDistance, maxDistance, visibilityAlpha
        //$$ );
        //#else
        WaypointBeamPainter.drawBeam(
            frame.matrices, frame.cameraPos, worldX, worldZ, bottomY, topY,
            colorArgb, horizontalDistance, maxDistance, visibilityAlpha
        );
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

    /**
     * The pass brackets restore every state they touch inline, so nothing here outlives
     * {@link #endPass}; close keeps the seam closeable as per-version native state moves
     * in behind it.
     */
    @Override
    public void close() {
    }
}

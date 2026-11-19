package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.core.util.Argb;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Pure beam geometry: the alpha falloff math and the square tube's four side faces emitted
 * as camera-relative triangles. Nothing here touches render state or submit pipelines -
 * each side goes to the {@link Sink} {@link BeamPass} provides, which owns the per-version
 * submission (see waypoint-ux.md S6 for the sizing rules the constants encode).
 */
final class WaypointBeamPainter {
    private static final double BEAM_HALF_WIDTH = 0.18;
    private static final float BEAM_CORE_ALPHA = 0.55f;
    /** Same near-camera fade-in constant as the label (waypoint-ux.md S6), applied to horizontal distance from the beam column. */
    private static final double BEAM_NEAR_FADE_BLOCKS = 5.0;
    /** Beam alpha never drops below this fraction of {@link #BEAM_CORE_ALPHA} even at the render-distance edge - "intensifies as you approach" without vanishing far away. */
    private static final float BEAM_FAR_FLOOR = 0.30f;

    /** One side face of the beam tube, drawn double-sided from local (x0,z0) to (x1,z1). */
    interface Sink {
        void beamSide(
            PoseStack matrices,
            float x0, float z0,
            float x1, float z1,
            float bottom, float top,
            int color
        );
    }

    private WaypointBeamPainter() {
    }

    /**
     * Square-tube beam (deliverable A's "4-sided prism" option) spanning the dimension's
     * full vertical range at the waypoint's X/Z, independent of the waypoint's own Y
     * (waypoint-ux.md S6: this is a deliberate simplification the reference implementation
     * also makes - a full-height column is trivial to draw and visible from anywhere at
     * that X/Z). Emitted double-sided so the tube reads correctly from inside or outside.
     */
    static void drawBeam(
        final Sink sink,
        final PoseStack matrices,
        final Vec3 cameraPos,
        final double worldX,
        final double worldZ,
        final double bottomY,
        final double topY,
        final int colorArgb,
        final double horizontalDistance,
        final double maxDistance,
        final float visibilityAlpha
    ) {
        final float nearFade = (float) Mth.clamp(horizontalDistance / BEAM_NEAR_FADE_BLOCKS, 0.0, 1.0);
        final float farFactor = (float) Mth.clamp(1.0 - horizontalDistance / maxDistance, 0.0, 1.0);
        final float intensify = BEAM_FAR_FLOOR + (1f - BEAM_FAR_FLOOR) * farFactor;
        final float alpha = BEAM_CORE_ALPHA * nearFade * intensify * visibilityAlpha;
        if (alpha <= 0.01f) {
            return;
        }
        final int color = Argb.pack(Math.round(alpha * 255f), Argb.red(colorArgb), Argb.green(colorArgb), Argb.blue(colorArgb));

        matrices.pushPose();
        matrices.translate(worldX - cameraPos.x, -cameraPos.y, worldZ - cameraPos.z);
        final float h = (float) BEAM_HALF_WIDTH;
        final float bottom = (float) bottomY;
        final float top = (float) topY;
        sink.beamSide(matrices, -h, -h, h, -h, bottom, top, color);
        sink.beamSide(matrices, h, -h, h, h, bottom, top, color);
        sink.beamSide(matrices, h, h, -h, h, bottom, top, color);
        sink.beamSide(matrices, -h, h, -h, -h, bottom, top, color);
        matrices.popPose();
    }
}
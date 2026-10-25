package cn.net.rms.confluxmap.mc.ui.world;

import net.minecraft.util.math.MathHelper;

/**
 * Distance and scale model shared by every waypoint label flavor: the 3D world-space
 * label, the item-icon HUD label, and the beam's visibility window. Pure math over
 * config and view distance - the beam only exists inside the vanilla view distance,
 * while labels obey {@code config.waypointRenderDistance} (including its unlimited
 * mode) with far labels pulled inside the camera's far plane along the same sight
 * line so they remain visible after their beam disappears. Label size follows
 * perspective: the legacy constant screen size at or inside the reference distance,
 * true inverse-distance shrink beyond it, and a far floor that keeps distant
 * navigation markers readable; aiming at or selecting a label zooms it back to full
 * apparent size ({@link #labelWorldScaleMult} is the single formula both label
 * paths share).
 */
final class WaypointLabelMetrics {
    static final double LABEL_Y_OFFSET = 1.5;
    /** Apparent label size stays at its maximum (the legacy constant screen size) at or closer than this. */
    static final double LABEL_REFERENCE_DISTANCE = 12.0;
    /** Perspective-shrink floor: apparent size never drops below this fraction of the maximum, however far. */
    static final float LABEL_FAR_SCREEN_FLOOR = 0.60f;
    /** Safety ceiling on the world-space multiplier so absurd view distances cannot blow up precision. */
    static final float LABEL_MAX_SCALE_MULT = 170.0f;
    /** Leaves enough room before the world projection's far plane for the complete billboard. */
    private static final double LABEL_FAR_PLANE_MARGIN = 0.90;

    private WaypointLabelMetrics() {
    }

    static double maxLabelDistance(final int configuredDistance) {
        return configuredDistance > 0 ? configuredDistance : Double.POSITIVE_INFINITY;
    }

    static double beamVisibleDistance(final int viewDistanceChunks) {
        return Math.max(0, viewDistanceChunks) * 16.0;
    }

    static double labelProjectionDistance(final int viewDistanceChunks) {
        return Math.max(16.0, beamVisibleDistance(viewDistanceChunks) * LABEL_FAR_PLANE_MARGIN);
    }

    static double projectedLabelDistance(
        final double actualDistance,
        final double projectionDistance
    ) {
        return Math.min(Math.max(0.0, actualDistance), Math.max(0.0, projectionDistance));
    }

    /** Apparent (post-perspective) label size as a fraction of the legacy constant screen size. */
    static float labelScreenScaleFactor(final double renderedDistance) {
        return (float) MathHelper.clamp(
            LABEL_REFERENCE_DISTANCE / Math.max(renderedDistance, 0.001),
            LABEL_FAR_SCREEN_FLOOR, 1.0
        );
    }

    /**
     * World-space scale multiplier for a label anchored at {@code renderedDistance}. At or inside
     * the reference distance it reproduces the legacy constant-screen-size growth (apparent size
     * at its maximum); beyond it the multiplier holds at 1 - a true perspective object - and then
     * grows only enough to hold the apparent size at {@link #LABEL_FAR_SCREEN_FLOOR} for far
     * labels that were pulled closer to stay inside the far plane. {@code easedTargetProgress}
     * (the eased aim/select animation, 0..1) lerps the apparent size back toward full so the
     * magnify-on-target effect is not dampened by the distance shrink. The item-icon HUD path
     * shares this so both label flavors size identically.
     */
    static float labelWorldScaleMult(final double renderedDistance, final float easedTargetProgress) {
        final double perspective = labelScreenScaleFactor(renderedDistance);
        final double screenFactor = MathHelper.lerp(easedTargetProgress, perspective, 1.0);
        return (float) MathHelper.clamp(
            (renderedDistance / LABEL_REFERENCE_DISTANCE) * screenFactor, 0.0, LABEL_MAX_SCALE_MULT
        );
    }
}

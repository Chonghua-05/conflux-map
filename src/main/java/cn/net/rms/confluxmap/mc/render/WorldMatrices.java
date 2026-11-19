package cn.net.rms.confluxmap.mc.render;

import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.FloatBuffer;
import com.mojang.blaze3d.vertex.PoseStack;

/**
 * Level-pass matrix snapshots and reinstall brackets shared by the waypoint beam and label
 * passes: the view matrix captured inside the world pass and reinstalled at END (pre-1.21.9)
 * or at 26.1's renderLevel RETURN, the perspective projection swap that survives deferred
 * shader packs, and the HUD-scope ModelView bracket for the in-pass phases.
 */
public final class WorldMatrices {

    // HUD item-label frame staging: the projection arrives where vanilla installs it (the
    // LAST context before 1.21.9, the GameRenderer renderWorld/renderLevel capture on later
    // lines), the view arrives from the world-pass hooks; the view is captured last in every
    // frame, so it commits the pair into the HUD snapshot.
    private static final double[] hudProjectionFrame = new double[16];
    private static final double[] hudViewFrame = new double[16];
    private static boolean hudProjectionCaptured;

    private WorldMatrices() {
    }

    /** Stages the world pass's projection for the HUD item-label snapshot (row-major doubles). */
    public static void captureHudProjection(final org.joml.Matrix4f projection) {
        writeRowMajor(projection, hudProjectionFrame);
        hudProjectionCaptured = true;
    }

    /**
     * Captures the live world-pass camera view and, once this frame's projection has been
     * staged, commits both into the HUD item-label snapshot. Called from a world-pass hook
     * that runs after the projection is installed and before the HUD phase of the same frame.
     */
    public static void captureHudView() {
        writeRowMajor(RenderSystem.getModelViewMatrixCopy(), hudViewFrame);
        if (hudProjectionCaptured) {
            WaypointHudFrameMatrices.update(hudProjectionFrame, hudViewFrame);
        }
    }

    /** Drops the HUD item-label snapshot and its staged projection (session/dimension change). */
    public static void clearHudFrame() {
        hudProjectionCaptured = false;
        WaypointHudFrameMatrices.clear();
    }

    static void writeRowMajor(final org.joml.Matrix4f matrix, final double[] out) {
        // JOML's mXY() getters address column X, row Y, so the getters must be read
        // transposed here; reading m01() as "row 0, column 1" stores the whole matrix
        // transposed and turns the projection's clip-W row into its translation column.
        out[0] = matrix.m00();
        out[1] = matrix.m10();
        out[2] = matrix.m20();
        out[3] = matrix.m30();
        out[4] = matrix.m01();
        out[5] = matrix.m11();
        out[6] = matrix.m21();
        out[7] = matrix.m31();
        out[8] = matrix.m02();
        out[9] = matrix.m12();
        out[10] = matrix.m22();
        out[11] = matrix.m32();
        out[12] = matrix.m03();
        out[13] = matrix.m13();
        out[14] = matrix.m23();
        out[15] = matrix.m33();
    }





    /** Restores the global model-view saved by the matching pushPose bracket (view snapshot or HUD scope). */
    public static void popModelView() {
        RenderSystem.getModelViewStack().popMatrix();
    }
}
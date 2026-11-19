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
    // Private copy: the live matrix RenderSystem.getModelViewMatrix() returns is a stack
    // slot the next frame's world pass mutates in place, so the snapshot must not alias it.
    private static final org.joml.Matrix4f worldViewMatrix = new org.joml.Matrix4f();
    private static boolean worldViewCaptured;

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
        writeRowMajor(RenderSystem.getModelViewMatrix(), hudViewFrame);
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

    /**
     * Captures the world pass's global ModelView: vanilla installs the view matrix (camera
     * rotation, plus view bobbing/hurt tilt) on the global stack at the start of world
     * rendering and pops it again right before the render method returns - which is exactly
     * where {@code WorldRenderEvents.END} fires. A read-only listener (still inside the world
     * pass) calls this so the post-pass draws can reinstall the exact matrix.
     */
    public static void snapshotWorldViewModelView() {
        worldViewMatrix.set(RenderSystem.getModelViewMatrix());
        worldViewCaptured = true;
    }

    /**
     * Installs the view matrix captured by {@link #snapshotWorldViewModelView()} as the global
     * ModelView. Level-anchored waypoint geometry drawn at {@code WorldRenderEvents.END}
     * transforms through it, so without this the view rotation is missing and the geometry
     * lands in the wrong place on screen. Falls back to identity before the first capture.
     * Pairs with {@link #popModelView()}.
     */
    public static void pushWorldViewSnapshot() {
        RenderSystem.getModelViewStack().pushMatrix().identity();
        if (worldViewCaptured) {
            RenderSystem.getModelViewStack().mul(worldViewMatrix);
        }
    }



    /**
     * Saves the global ModelView around the in-pass label phase so the billboard keeps
     * the world view rotation vanilla installed. The pre-1.21.9 lines never reach this:
     * their END pass reinstalls the captured view through {@link #pushWorldViewSnapshot()}
     * instead. Pairs with {@link #popModelView()}.
     */
    public static void pushWorldHudModelView() {
        RenderSystem.getModelViewStack().pushMatrix();
    }

    /** Restores the global model-view saved by the matching pushPose bracket (view snapshot or HUD scope). */
    public static void popModelView() {
        RenderSystem.getModelViewStack().popMatrix();
    }
}
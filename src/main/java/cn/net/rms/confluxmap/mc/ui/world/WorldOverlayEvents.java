package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.mc.render.WorldMatrices;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import cn.net.rms.confluxmap.neoforge.compat.FabricOrderedSubmitNodeCollector;
import cn.net.rms.confluxmap.neoforge.compat.LevelRenderContext;
import cn.net.rms.confluxmap.neoforge.compat.LevelRenderEvents;
import net.minecraft.client.Minecraft;
// Camera exists on every line (relocated on 26.x); MultiBufferSource is pre-26.2 only.
import net.minecraft.client.Camera;
import com.mojang.blaze3d.vertex.PoseStack;

/**
 * The single version-facing seam for the in-world waypoint overlay: registers the render
 * hook for the running Minecraft line and adapts its native context into an {@link
 * OverlayFrame} every pass, so {@link WaypointWorldRenderer} stays a version-free
 * orchestrator and the passes never see a native context.
 *
 * <p>Hook phases by line. 1.21.11 registers {@code WorldRenderEvents.BEFORE_TRANSLUCENT}
 * for the beam and {@code END_MAIN} for the labels. 26.1 records the world view at
 * {@code LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN} for the beam and keeps the labels on
 * {@code END_MAIN}: vanilla consumes no submit nodes after the terrain events on that line
 * (custom geometry renders before translucent terrain and the submit storage is cleared right
 * after the hook), so {@code WorldRendererMixin} draws the beam at {@code renderLevel}'s
 * RETURN instead - after a deferred pack's finalize, the same slot the pre-1.21.9 END passes
 * use. 26.2 submits the beam through the level's submit collector at
 * {@code AFTER_TRANSLUCENT_TERRAIN} and moves the labels to {@code COLLECT_SUBMITS}; 26.3
 * registers both passes on
 * {@code COLLECT_SUBMITS}, because it renders classic-translucent terrain inside one render
 * pass that stays open for the whole translucent hook, so the beam can no longer issue its
 * own passes there - submitting the beam geometry instead rides executeTranslucentAfterTerrain,
 * which is still after translucent terrain - the same slot the event hook used to provide.
 * The beam pipeline writes depth, so on 26.1+ it must run after translucent terrain; drawing
 * it first makes water fail its depth test and disappear where the beam crosses it. On
 * 1.21.9-1.21.10 Fabric API ships no world render events at all (the world renderer was
 * rewritten in 1.21.9 and the events only returned, under {@code rendering.v1.world}, in
 * 1.21.11); {@code WorldRendererMixin} drives {@link #onBeforeTranslucent}/{@link
 * #onEndMain} instead, so only the plumbing differs from every other version.
 *
 * <p>Before 1.21.9 both passes run at {@code WorldRenderEvents.END}, after the whole world
 * pass. A deferred shader pack (Photon and family) finalizes from {@code
 * LevelRenderer.render}'s RETURN and rebuilds the color attachment from its gbuffers. That
 * erases the draws that bypassed render pipelines: the pack captures pipeline-drawn geometry
 * into its gbuffers, where it is shaded and survives into the final image, while raw
 * Tesselator draws write into whatever framebuffer happens to be bound mid-pass and are
 * overwritten by the rebuild. Both of this mod's passes are raw draws, so drawing at END -
 * which fires at that same RETURN, after the pack's finalize - puts the waypoints on the
 * finished frame. The beam still participates in world occlusion by depth-testing against
 * the completed scene depth buffer, while the labels draw afterwards with depth testing
 * disabled so world content cannot hide player-facing navigation information. From 1.21.9
 * on, released Iris builds exist for every line (1.21.9/1.21.10 ship 1.9.x, 1.21.11 ships
 * 1.10.x, 26.1-26.3 ship 1.11.x), and all of their hook phases fire inside the pack's
 * world-pass capture window - no Fabric world event fires after the pack's finalize on those
 * lines - so the beam still rides a terrain-adjacent phase there, drawn through the submit
 * pipeline that the capture rule keeps alive.
 *
 * <p>The LAST listener (before 1.21.9 only) exists because END fires after vanilla popped
 * the world view matrix off the global ModelView: the listener captures it read-only for the
 * END passes to reinstall, and it draws nothing, so the deferred erase cannot affect it.
 */
public final class WorldOverlayEvents {
    private WorldOverlayEvents() {
    }


    /**
     * The version-specific builders below are preprocessor-comment lines whose identifiers
     * cannot rely on remapping; they reach the client through this plain helper.
     */
    private static Minecraft client() {
        return Minecraft.getInstance();
    }

    /**
     * Registers the render hooks for the running version line. Listener order matters: the
     * beam pass must draw before the label pass wherever both share one phase.
     */
    public static void register(final WaypointWorldRenderer renderer) {
        LevelRenderEvents.COLLECT_SUBMITS.register(context ->
            captureWorldView());
        LevelRenderEvents.COLLECT_SUBMITS.register(context ->
            renderer.renderBeams(beamFrame(context)));
        LevelRenderEvents.COLLECT_SUBMITS.register(context ->
            renderer.renderHud(labelFrame(context)));
    }

    /**
     * Level-pass capture listener: pairs the live camera view with this frame's already
     * staged projection into the HUD item-label snapshot (see WorldMatrices), and on the
     * pre-1.21.9 lines additionally records the view matrix for the post-pass END draws
     * (see {@link WorldMatrices#snapshotWorldViewModelView}).
     */
    private static void captureWorldView() {
        WorldMatrices.captureHudView();
    }





    // 26.x is unobfuscated and this region is uncommented verbatim: identifiers here
    // are official names - do not write Yarn names.
    private static OverlayFrame beamFrame(final LevelRenderContext context) {
        final Camera camera = client().gameRenderer.mainCamera();
        return new OverlayFrame(
            context.poseStack(),
            camera.position(),
            null,
            null,
            FabricOrderedSubmitNodeCollector.of(context.submitNodeCollector()),
            camera.yRot(),
            camera.xRot(),
            tickDelta()
        );
    }
    
    private static OverlayFrame labelFrame(final LevelRenderContext context) {
        final var cameraState = context.levelState().cameraRenderState;
        return new OverlayFrame(
            context.poseStack(),
            cameraState.pos,
            context.submitNodeCollector(),
            cameraState.orientation,
            FabricOrderedSubmitNodeCollector.of(context.submitNodeCollector()),
            cameraState.yRot,
            cameraState.xRot,
            tickDelta()
        );
    }

    private static float tickDelta() {
        return client().getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }



    /**
     * One rate-limited diagnostics line per waypoint render pass: camera state, the view
     * matrix in force for this mod's draws, and where the given camera-relative anchor
     * projects to under the pass's matrices. Compare lines across passes (or against where
     * the waypoint actually shows on screen) to place render bugs without screenshot loops.
     * {@code anchorRel} is camera-relative; {@code null} skips the anchor projection.
     *
     * <p>Only driven on the pre-1.21.9 lines: the LAST-capture/END-reinstall ladder this
     * logs does not exist once the passes run inside the world pass.
     */
    static void renderState(final String phase, final OverlayFrame frame, final double[] anchorRel) {
    }

    /**
     * Waypoint world-render diagnostics switch: {@code -Dconfluxmap.debugWaypointRender=true}
     * or {@code CONFLUXMAP_DEBUG_RENDER=1} (Gradle launches inherit env vars, not -D flags).
     */
    static boolean debugRenderEnabled() {
        return Boolean.getBoolean("confluxmap.debugWaypointRender")
            || "1".equals(System.getenv("CONFLUXMAP_DEBUG_RENDER"));
    }

    /** Per-phase rate limit: a shared timestamp would let the first phase of a frame starve the rest. */
    private static final Map<String, Long> lastDebugLogNanos = new HashMap<>();
}
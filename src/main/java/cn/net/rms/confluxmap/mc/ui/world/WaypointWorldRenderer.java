package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.compat.MinecraftAccess;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.radar.ServerPlayerRadarState;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderCatalog;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import cn.net.rms.confluxmap.mc.ui.WaypointMarkerRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
//#if MC>=260100
//$$ import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
//$$ import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
//#elseif MC>=12111
//$$ import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
//$$ import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
//#elseif MC>=12109
//$$ // 1.21.9's Fabric API has no world render events; WorldRendererMixin supplies them instead.
//#else
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
//#endif
//#if MC>=260200
//$$ import net.fabricmc.fabric.api.client.rendering.v1.FabricOrderedSubmitNodeCollector;
//#endif
import net.minecraft.client.MinecraftClient;
//#if MC>=12109 && MC<260100
//$$ import net.minecraft.client.render.command.OrderedRenderCommandQueue;
//#endif
import net.minecraft.client.render.Camera;
//#if MC<260200
import net.minecraft.client.render.VertexConsumerProvider;
//#endif
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Gives waypoints an in-world presence (user feedback driving this slice: "waypoints
 * have NO in-world presence"): a vertical translucent beam at each visible waypoint's
 * column, and a camera-facing name/distance label floating above it.
 *
 * <p>This class is the version-conditional event plumbing and per-frame orchestration:
 * it registers the render hooks, collects the waypoints to render (including highlight
 * ghosts and selected-location synthetic entries), selects which labels exist, and
 * animates the aim/select reveal. The beam geometry lives in {@link WaypointBeamPainter},
 * the 3D label billboard in {@link WaypointLabelPainter}, and the shared distance/scale
 * math and label styling in {@link WaypointLabelMetrics}/{@link WaypointLabelStyle}.
 *
 * <p>On versions with the classic {@code WorldRenderEvents} (before 1.21.9) both the beam and
 * the HUD marker run at {@code WorldRenderEvents.END}, after the whole world pass: deferred
 * shader packs (Photon and family) finalize from {@code LevelRenderer.render}'s RETURN and
 * rewrite the color attachment from the gbuffer, erasing anything drawn inside the world pass,
 * so drawing at END puts the waypoints on the finished frame. The beam still participates in
 * world occlusion by depth-testing against the completed scene depth buffer, while the labels
 * draw afterwards with depth testing disabled so world content cannot hide player-facing
 * navigation information. On 1.21.9 and later the events moved into main-pass positions the
 * packs rewrite, and no Iris dev environment exists for those lines yet, so the beam still
 * rides a terrain-adjacent phase there. In fabric-rendering-v1 1.10.1 (bundled with the
 * installed fabric-api 0.46.1+1.17), {@link WorldRenderContext#consumers()} is {@code
 * null} at this phase and its matrix stack carries no pre-existing camera translation -
 * both are handled here: the beam is drawn with plain {@code Tessellator}/{@code
 * BufferBuilder} calls ({@code RenderUtil#fillTriangle3D}) instead of a vertex consumer,
 * and every position is explicitly translated by {@code worldPos - camera.getPos()}
 * before drawing. The label's text/background instead opens its own {@link
 * VertexConsumerProvider.Immediate} (the same one vanilla uses for entity nametags,
 * {@code client.getBufferBuilders().getEntityVertexConsumers()}) which works at any
 * phase and is flushed once at the end of this render pass.
 *
 * <p>The beam and HUD label have deliberately separate distance rules (see
 * {@link WaypointLabelMetrics}). Label size follows perspective, and aiming at or
 * selecting a label zooms it back to full apparent size.
 */
public final class WaypointWorldRenderer {
    private static final int SELECTED_LOCATION_COLOR = 0xFFFFE066;

    /**
     * Waypoint world-render diagnostics switch: {@code -Dconfluxmap.debugWaypointRender=true}
     * or {@code CONFLUXMAP_DEBUG_RENDER=1} (Gradle launches inherit env vars, not -D flags).
     * Emits rate-limited logs of the camera, the global matrices, and where the first
     * waypoint lands on screen, so render placement bugs can be located from the log
     * instead of screenshot-by-screenshot.
     */
    private static final boolean DEBUG_RENDER =
        Boolean.getBoolean("confluxmap.debugWaypointRender")
            || "1".equals(System.getenv("CONFLUXMAP_DEBUG_RENDER"));
    /** Per-phase rate limit: a shared timestamp would let the first phase of a frame starve the rest. */
    private static final Map<String, Long> lastDebugLogNanos = new HashMap<>();

    private final MinecraftClient client;
    private final ConfluxConfig config;
    private final GameBridge gameBridge;
    private final WaypointRenderCatalog waypointRenderCatalog;
    private final WaypointHighlightState waypointHighlightState;
    private final ServerPlayerRadarState serverPlayerRadar;
    private final WaypointItemHudRenderer waypointItemHudRenderer;
    private final WaypointLabelPainter labelPainter;
    private final Map<UUID, Float> labelAnimationProgress = new HashMap<>();
    private long lastAnimationNanos;

    public WaypointWorldRenderer(
        final MinecraftClient client,
        final ConfluxConfig config,
        final GameBridge gameBridge,
        final WaypointRenderCatalog waypointRenderCatalog,
        final WaypointHighlightState waypointHighlightState,
        final ServerPlayerRadarState serverPlayerRadar,
        final WaypointItemHudRenderer waypointItemHudRenderer
    ) {
        this.client = client;
        this.config = config;
        this.gameBridge = gameBridge;
        this.waypointRenderCatalog = waypointRenderCatalog;
        this.waypointHighlightState = waypointHighlightState;
        this.serverPlayerRadar = serverPlayerRadar;
        this.waypointItemHudRenderer = waypointItemHudRenderer;
        this.labelPainter = new WaypointLabelPainter(client, config);
    }

    //#if MC>=12109 && MC<12111
    //$$ /**
    //$$  * The 1.21.9 line is the one Minecraft generation Fabric API ships no world render events
    //$$  * for: the world renderer was rewritten in 1.21.9 and the events only came back, under
    //$$  * {@code rendering.v1.world}, in 1.21.11. WorldRendererMixin drives these two entry points
    //$$  * instead, so only the plumbing differs from every other version.
    //$$  */
    //$$ private static WaypointWorldRenderer active;
    //$$
    //$$ public static void onBeforeTranslucent(final MatrixStack matrices) {
    //$$     if (active != null) {
    //$$         active.renderBeams(matrices);
    //$$     }
    //$$ }
    //$$
    //$$ public static void onEndMain(
    //$$     final MatrixStack matrices,
    //$$     final OrderedRenderCommandQueue commandQueue
    //$$ ) {
    //$$     if (active != null) {
    //$$         active.renderHud(matrices, commandQueue);
    //$$     }
    //$$ }
    //#endif

    public void register() {
        //#if MC>=260100
        //$$ // The beam pipeline writes depth, so it must run after translucent terrain; drawing it
        //$$ // first makes water fail its depth test and disappear where the beam crosses it.
        //#endif
        //#if MC>=260300
        //$$ // 26.3 renders classic-translucent terrain inside one render pass that stays open for
        //$$ // the whole translucent hook, so the beam can no longer issue its own passes there.
        //$$ // Submitting the beam geometry instead rides executeTranslucentAfterTerrain, which is
        //$$ // still after translucent terrain - the same slot the event hook used to provide.
        //$$ LevelRenderEvents.COLLECT_SUBMITS.register(this::renderBeams);
        //$$ LevelRenderEvents.COLLECT_SUBMITS.register(this::renderHud);
        //#elseif MC>=260200
        //$$ LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(this::renderBeams);
        //$$ LevelRenderEvents.COLLECT_SUBMITS.register(this::renderHud);
        //#elseif MC>=260100
        //$$ LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(this::renderBeams);
        //$$ LevelRenderEvents.END_MAIN.register(this::renderHud);
        //#elseif MC>=12111
        //$$ WorldRenderEvents.BEFORE_TRANSLUCENT.register(this::renderBeams);
        //$$ WorldRenderEvents.END_MAIN.register(this::renderHud);
        //#elseif MC>=12109
        //$$ active = this;
        //#else
        // Deferred shading packs (Photon and family) run their finalize passes from
        // LevelRenderer.render's RETURN and rewrite the whole color attachment from the
        // gbuffer, erasing anything drawn inside the world pass (AFTER_TRANSLUCENT/LAST) -
        // including this beam and its labels. WorldRenderEvents.END fires at that same
        // RETURN, after the pack's finalize, so both draw on the finished frame instead:
        // the beam depth-tests against the completed scene depth buffer, and the labels
        // draw after it with depth testing disabled. Vanilla pops the world view matrix
        // off the global ModelView just before that same RETURN, so the LAST listener
        // (still inside the world pass) captures it read-only for the END passes to
        // reinstall; it draws nothing, so the deferred erase cannot affect it.
        WorldRenderEvents.LAST.register(this::captureWorldView);
        WorldRenderEvents.END.register(this::renderBeams);
        WorldRenderEvents.END.register(this::renderHud);
        //#endif
    }

    //#if MC<12109
    /** Read-only LAST listener: records the world view matrix for the END passes (see RenderUtil). */
    private void captureWorldView(final WorldRenderContext context) {
        RenderUtil.snapshotWorldViewModelView();
        debugRenderState("last-capture", null, null, null, null);
    }
    //#endif

    //#if MC<12109
    /**
     * One rate-limited diagnostics line per waypoint render pass: camera state, the view
     * matrix in force for this mod's draws, and the screen position the given camera-relative
     * anchor projects to under the pass's matrices. Compare lines across passes (or against
     * where the waypoint actually shows on screen) to place render bugs without screenshot
     * loops. {@code projection} is the world projection the caller installed; {@code Object}
     * keeps the signature compiling on versions without JOML.
     */
    private void debugRenderState(
        final String phase,
        final Camera camera,
        final MatrixStack matrices,
        final Object projection,
        final double[] anchorRel
    ) {
        if (!DEBUG_RENDER) {
            return;
        }
        final long now = System.nanoTime();
        final Long lastLog = lastDebugLogNanos.get(phase);
        if (lastLog != null && now - lastLog < 2_000_000_000L) {
            return;
        }
        lastDebugLogNanos.put(phase, now);
        final StringBuilder line = new StringBuilder(384).append("[wp-render] ").append(phase);
        if (camera != null) {
            line.append(String.format(Locale.ROOT,
                " cam=(%.2f, %.2f, %.2f) yaw=%.1f pitch=%.1f",
                camera.getPos().x, camera.getPos().y, camera.getPos().z,
                camera.getYaw(), camera.getPitch()));
        }
        //#if MC>=11904 && MC<12109
        //$$ final org.joml.Matrix4f view = RenderUtil.capturedWorldViewMatrix();
        //$$ if (view != null) {
        //$$     line.append(" view=").append(RenderUtil.debugMatrix(view));
        //$$ }
        //$$ if (matrices != null && projection != null && view != null && anchorRel != null) {
        //$$     line.append(RenderUtil.debugProjectAnchor(
        //$$         (org.joml.Matrix4f) projection, view,
        //$$         anchorRel[0], anchorRel[1], anchorRel[2], client));
        //$$ }
        //#endif
        ConfluxMapMod.LOGGER.info("{}", line);
    }
    //#endif

    //#if MC>=12109 && MC<12111
    //$$ private void renderBeams(final MatrixStack matrices) {
    //#else
    //#if MC>=260100
    //$$ private void renderBeams(final LevelRenderContext context) {
    //#else
    private void renderBeams(final WorldRenderContext context) {
    //#endif
    //#endif
        if (!config.waypointBeamsEnabled) {
            return;
        }
        if (!gameBridge.session().active()) {
            return;
        }
        //#if MC>=12109 && MC<12111
        //$$ final Optional<PlayerView> playerViewOpt = gameBridge.player(tickDelta());
        //#else
        final Optional<PlayerView> playerViewOpt = gameBridge.player(tickDelta(context));
        //#endif
        if (playerViewOpt.isEmpty()) {
            return;
        }
        final PlayerView player = playerViewOpt.get();
        final DimensionId currentDimension = gameBridge.session().dimension();
        //#if MC>=260200
        //$$ final Camera camera = client.gameRenderer.mainCamera();
        //$$ final Vec3 cameraPos = camera.position();
        //$$ final PoseStack matrices = context.poseStack();
        //#elseif MC>=12111
        //$$ final Camera camera = client.gameRenderer.getCamera();
        //$$ final Vec3d cameraPos = camera.getCameraPos();
        //#if MC>=260100
        //$$ final PoseStack matrices = context.poseStack();
        //#else
        //$$ final MatrixStack matrices = context.matrices();
        //#endif
        //#elseif MC>=12109
        //$$ final Camera camera = client.gameRenderer.getCamera();
        //$$ final Vec3d cameraPos = camera.getCameraPos();
        //#else
        final Camera camera = context.camera();
        final Vec3d cameraPos = camera.getPos();
        final MatrixStack matrices = context.matrixStack();
        //#endif
        //#if MC>=260300
        //$$ final FabricOrderedSubmitNodeCollector beamSubmits =
        //$$     (FabricOrderedSubmitNodeCollector) context.submitNodeCollector();
        //#endif
        final double maxDistance = WaypointLabelMetrics.beamVisibleDistance(
            MinecraftAccess.viewDistance(client)
        );
        if (maxDistance <= 0.0) {
            return;
        }
        final double bottomY = client.world.getBottomY();
        final double topY = client.world.getTopY();
        final List<WaypointRenderEntry> waypoints = waypointsForRender(currentDimension);
        final boolean hasHighlight = waypointHighlightState.hasRenderableTarget(
            waypoints, currentDimension
        ) || serverPlayerRadar.highlightedPlayerId().isPresent();

        //#if MC<12105
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        //#endif
        //#if MC<12109
        // END runs after the pack's finalize, which leaves the global projection pointing at
        // its composite passes, and after vanilla popped the world view matrix off the global
        // ModelView; reinstall the projection and the view captured at LAST for the beam and
        // put both back afterwards.
        RenderUtil.applyWorldProjection(context.projectionMatrix());
        RenderUtil.pushWorldViewSnapshot();
        //#endif
        //#if MC<12109
        debugRenderState("end-beam", camera, matrices, context.projectionMatrix(), null);
        //#endif
        RenderUtil.beginAdditiveTriangles();

        for (final WaypointRenderEntry waypoint : waypoints) {
            final double worldX = waypoint.x();
            final double worldZ = waypoint.z();
            final double dx = worldX - player.x();
            final double dz = worldZ - player.z();
            final double renderDistance = waypointHighlightState.renderDistance(
                waypoint, currentDimension, player.x(), player.y(), player.z()
            );
            if (renderDistance > maxDistance) {
                continue;
            }
            final double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
            final boolean selected = isSelected(waypoint, currentDimension);
            //#if MC>=260300
            //$$ WaypointBeamPainter.drawBeam(
            //$$     matrices, beamSubmits, cameraPos, worldX, worldZ, bottomY, topY,
            //$$     waypoint.colorArgb(), horizontalDistance, maxDistance,
            //$$     highlightVisibilityAlpha(selected, hasHighlight)
            //$$         * playerHighlightAlpha(waypoint, currentDimension)
            //$$ );
            //#else
            WaypointBeamPainter.drawBeam(
                matrices, cameraPos, worldX, worldZ, bottomY, topY,
                waypoint.colorArgb(), horizontalDistance, maxDistance,
                highlightVisibilityAlpha(selected, hasHighlight)
                    * playerHighlightAlpha(waypoint, currentDimension)
            );
            //#endif
        }

        RenderUtil.restoreDefaultBlend();
        //#if MC<12109
        RenderUtil.popModelView();
        RenderUtil.restoreProjection();
        //#endif
        //#if MC<12105
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        //#endif
    }

    //#if MC>=12109 && MC<12111
    //$$ private void renderHud(
    //$$     final MatrixStack matrices,
    //$$     final OrderedRenderCommandQueue commandQueue
    //$$ ) {
    //#else
    //#if MC>=260100
    //$$ private void renderHud(final LevelRenderContext context) {
    //#else
    private void renderHud(final WorldRenderContext context) {
    //#endif
    //#endif
        if (!config.waypointLabelsEnabled) {
            labelAnimationProgress.clear();
            waypointItemHudRenderer.publish(List.of());
            return;
        }
        if (!gameBridge.session().active()) {
            waypointItemHudRenderer.publish(List.of());
            return;
        }
        //#if MC>=12109 && MC<12111
        //$$ final float tickDelta = tickDelta();
        //#else
        final float tickDelta = tickDelta(context);
        //#endif
        final Optional<PlayerView> playerViewOpt = gameBridge.player(tickDelta);
        if (playerViewOpt.isEmpty()) {
            waypointItemHudRenderer.publish(List.of());
            return;
        }
        final PlayerView player = playerViewOpt.get();
        final DimensionId currentDimension = gameBridge.session().dimension();
        //#if MC>=260200
        //$$ final var cameraState = context.levelState().cameraRenderState;
        //$$ final Vec3 cameraPos = cameraState.pos;
        //$$ final float cameraYaw = cameraState.yRot;
        //$$ final float cameraPitch = cameraState.xRot;
        //$$ final PoseStack matrices = context.poseStack();
        //#elseif MC>=12111
        //$$ final Camera camera = client.gameRenderer.getCamera();
        //$$ final Vec3d cameraPos = camera.getCameraPos();
        //$$ final float cameraYaw = camera.getYaw();
        //$$ final float cameraPitch = camera.getPitch();
        //#if MC>=260100
        //$$ final PoseStack matrices = context.poseStack();
        //#else
        //$$ final MatrixStack matrices = context.matrices();
        //#endif
        //#elseif MC>=12109
        //$$ final Camera camera = client.gameRenderer.getCamera();
        //$$ final Vec3d cameraPos = camera.getCameraPos();
        //$$ final float cameraYaw = camera.getYaw();
        //$$ final float cameraPitch = camera.getPitch();
        //#else
        final Camera camera = context.camera();
        final Vec3d cameraPos = camera.getPos();
        final float cameraYaw = camera.getYaw();
        final float cameraPitch = camera.getPitch();
        final MatrixStack matrices = context.matrixStack();
        //#endif
        final double maxDistance = WaypointLabelMetrics.maxLabelDistance(config.waypointRenderDistance);
        final double labelProjectionDistance = WaypointLabelMetrics.labelProjectionDistance(
            MinecraftAccess.viewDistance(client)
        );
        final List<WaypointRenderEntry> waypoints = waypointsForRender(currentDimension);
        final LabelSelection labelSelection = selectLabels(
            waypoints,
            cameraYaw,
            cameraPitch,
            cameraPos,
            player.x(),
            player.y(),
            player.z(),
            maxDistance,
            waypointHighlightState,
            currentDimension,
            serverPlayerRadar.highlightedPlayerId().orElse(null)
        );
        final float animationDeltaSeconds = animationDeltaSeconds();
        final Set<UUID> visibleWaypointIds = new HashSet<>(labelSelection.candidates().size());
        final List<WaypointItemHudRenderer.Label> itemLabels = new ArrayList<>();

        if (!labelSelection.candidates().isEmpty()) {
            //#if MC<12109
            // Same state repair as the beam pass: reinstall the world projection and the view
            // matrix vanilla popped before END, so world-anchored labels keep their rotation.
            RenderUtil.applyWorldProjection(context.projectionMatrix());
            RenderUtil.pushWorldViewSnapshot();
            final LabelCandidate first = labelSelection.candidates().get(0);
            debugRenderState("end-hud", camera, matrices, context.projectionMatrix(), new double[] {
                first.waypoint().x() - cameraPos.x,
                first.waypoint().y() + WaypointLabelMetrics.LABEL_Y_OFFSET - cameraPos.y,
                first.waypoint().z() - cameraPos.z
            });
            //#elseif MC<260200
            //$$ RenderUtil.pushWorldHudModelView();
            //#endif
            try {
                //#if MC<12105
                RenderSystem.disableDepthTest();
                RenderSystem.depthMask(false);
                //#endif
                //#if MC<260200
                final VertexConsumerProvider.Immediate immediate = client.getBufferBuilders().getEntityVertexConsumers();
                //#endif
                for (final LabelCandidate candidate : labelSelection.candidates()) {
                    final WaypointRenderEntry waypoint = candidate.waypoint();
                    visibleWaypointIds.add(waypoint.id());
                    final double renderDistance = candidate.selected()
                        ? waypointHighlightState.renderDistance(
                            waypoint, currentDimension, player.x(), player.y(), player.z()
                        )
                        : candidate.distance();
                    final boolean targeted = candidate.selected()
                        || waypoint.id().equals(labelSelection.targetedWaypointId());
                    final float progress = updateLabelAnimation(waypoint.id(), targeted, animationDeltaSeconds);
                    final UUID playerId = serverPlayerRadar.isHighlighted(waypoint.id())
                        ? waypoint.id() : null;
                    if (playerId != null
                        || !WaypointMarkerRenderer.itemIcon(waypoint.iconItemId()).isEmpty()) {
                        itemLabels.add(new WaypointItemHudRenderer.Label(
                            waypoint,
                            renderDistance,
                            labelProjectionDistance,
                            progress,
                            highlightVisibilityAlpha(
                                candidate.selected(), labelSelection.hasHighlight()
                            ) * playerHighlightAlpha(waypoint, currentDimension),
                            candidate.selected(),
                            playerId
                        ));
                        continue;
                    }
                    //#if MC>=260200
                    //$$ labelPainter.drawLabel(
                    //$$     matrices, context.submitNodeCollector(), cameraState.orientation,
                    //$$     cameraPos, waypoint.x(), waypoint.y(), waypoint.z(), waypoint,
                    //$$     renderDistance, labelProjectionDistance, progress,
                    //$$     highlightVisibilityAlpha(candidate.selected(), labelSelection.hasHighlight())
                    //$$         * playerHighlightAlpha(waypoint, currentDimension),
                    //$$     candidate.selected()
                    //$$ );
                    //#elseif MC>=260100
                    //$$ labelPainter.drawLabel(
                    //$$     matrices, immediate, context.submitNodeCollector(), camera,
                    //$$     cameraPos, waypoint.x(), waypoint.y(), waypoint.z(),
                    //$$     waypoint, renderDistance, labelProjectionDistance, progress,
                    //$$     highlightVisibilityAlpha(candidate.selected(), labelSelection.hasHighlight())
                    //$$         * playerHighlightAlpha(waypoint, currentDimension),
                    //$$     candidate.selected()
                    //$$ );
                    //#elseif MC>=12111
                    //$$ labelPainter.drawLabel(
                    //$$     matrices, immediate, context.commandQueue(), camera,
                    //$$     cameraPos, waypoint.x(), waypoint.y(), waypoint.z(),
                    //$$     waypoint, renderDistance, labelProjectionDistance, progress,
                    //$$     highlightVisibilityAlpha(candidate.selected(), labelSelection.hasHighlight())
                    //$$         * playerHighlightAlpha(waypoint, currentDimension),
                    //$$     candidate.selected()
                    //$$ );
                    //#elseif MC>=12109
                    //$$ labelPainter.drawLabel(
                    //$$     matrices, immediate, commandQueue, camera,
                    //$$     cameraPos, waypoint.x(), waypoint.y(), waypoint.z(),
                    //$$     waypoint, renderDistance, labelProjectionDistance, progress,
                    //$$     highlightVisibilityAlpha(candidate.selected(), labelSelection.hasHighlight())
                    //$$         * playerHighlightAlpha(waypoint, currentDimension),
                    //$$     candidate.selected()
                    //$$ );
                    //#else
                    labelPainter.drawLabel(
                        matrices, immediate, camera, cameraPos, waypoint.x(), waypoint.y(), waypoint.z(),
                        waypoint, renderDistance, labelProjectionDistance, progress,
                        highlightVisibilityAlpha(candidate.selected(), labelSelection.hasHighlight())
                            * playerHighlightAlpha(waypoint, currentDimension),
                        candidate.selected()
                    );
                    //#endif
                }
                //#if MC<260200
                immediate.draw();
                //#endif
            } finally {
                //#if MC<12105
                RenderSystem.depthMask(true);
                RenderSystem.enableDepthTest();
                //#endif
                //#if MC<12109
                RenderUtil.popModelView();
                RenderUtil.restoreProjection();
                //#elseif MC<260200
                //$$ RenderUtil.popModelView();
                //#endif
            }
        }

        waypointItemHudRenderer.publish(itemLabels);
        labelAnimationProgress.keySet().retainAll(visibleWaypointIds);
    }

    private List<WaypointRenderEntry> waypointsForRender(final DimensionId dimension) {
        final List<WaypointRenderEntry> base = waypointRenderCatalog.snapshot(dimension);
        final Optional<ServerPlayerRadarState.HighlightView> playerTarget =
            serverPlayerRadar.highlightedIn(
                dimension,
                System.currentTimeMillis(),
                config.radarPlayerHighlightGhostSeconds * 1_000L
            );
        final Optional<WaypointHighlightState.Target> target =
            waypointHighlightState.locationTargetIn(dimension);
        if (target.isEmpty() && playerTarget.isEmpty()) {
            return base;
        }
        final List<WaypointRenderEntry> result = new ArrayList<>(base.size() + 2);
        result.addAll(base);
        target.ifPresent(value -> result.add(selectedTargetEntry(value, dimension)));
        playerTarget.ifPresent(value -> result.add(new WaypointRenderEntry(
            value.player().playerId(),
            value.player().name(),
            dimension,
            value.player().x(),
            value.player().y(),
            value.player().z(),
            SELECTED_LOCATION_COLOR,
            Waypoint.Type.NORMAL,
            WaypointRenderEntry.Source.LOCAL
        )));
        return result;
    }

    private boolean isSelected(
        final WaypointRenderEntry waypoint,
        final DimensionId dimension
    ) {
        return waypointHighlightState.matchesEntry(waypoint, dimension)
            || serverPlayerRadar.isHighlighted(waypoint.id());
    }

    private float playerHighlightAlpha(
        final WaypointRenderEntry waypoint,
        final DimensionId dimension
    ) {
        if (!serverPlayerRadar.isHighlighted(waypoint.id())) {
            return 1f;
        }
        return serverPlayerRadar.highlightedIn(
            dimension,
            System.currentTimeMillis(),
            config.radarPlayerHighlightGhostSeconds * 1_000L
        ).filter(ServerPlayerRadarState.HighlightView::ghost).isPresent() ? 0.5f : 1f;
    }

    private WaypointRenderEntry selectedTargetEntry(
        final WaypointHighlightState.Target target,
        final DimensionId displayedDimension
    ) {
        return WaypointHighlightState.locationEntry(
            target,
            WaypointHighlightState.locationDisplayName(target, displayedDimension),
            selectedLocationY(target),
            SELECTED_LOCATION_COLOR
        );
    }

    private float highlightVisibilityAlpha(
        final boolean selected,
        final boolean hasHighlight
    ) {
        return selected || !hasHighlight
            ? 1f
            : config.waypointHighlightDimOpacity / 100f;
    }

    private double selectedLocationY(final WaypointHighlightState.Target target) {
        final double y = target.yKnown() ? target.y() : WaypointHighlightState.DEFAULT_LOCATION_Y;
        return MathHelper.clamp(y, client.world.getBottomY(), client.world.getTopY() - 1.0);
    }

    static LabelSelection selectLabels(
        final List<WaypointRenderEntry> waypoints,
        final float cameraYaw,
        final float cameraPitch,
        final Vec3d cameraPos,
        final double playerX,
        final double playerY,
        final double playerZ,
        final double maxDistance,
        final WaypointHighlightState waypointHighlightState,
        final DimensionId currentDimension
    ) {
        return selectLabels(
            waypoints, cameraYaw, cameraPitch, cameraPos,
            playerX, playerY, playerZ, maxDistance,
            waypointHighlightState, currentDimension, null
        );
    }

    static LabelSelection selectLabels(
        final List<WaypointRenderEntry> waypoints,
        final float cameraYaw,
        final float cameraPitch,
        final Vec3d cameraPos,
        final double playerX,
        final double playerY,
        final double playerZ,
        final double maxDistance,
        final WaypointHighlightState waypointHighlightState,
        final DimensionId currentDimension,
        final UUID highlightedPlayerId
    ) {
        final double maxDistanceSquared = maxDistance * maxDistance;
        final List<LabelCandidate> candidates = new ArrayList<>();
        WaypointRenderEntry targetedWaypoint = null;
        boolean hasHighlight = false;
        double bestAlignment = -1.0;
        double bestDistance = Double.POSITIVE_INFINITY;

        for (final WaypointRenderEntry waypoint : waypoints) {
            final double playerDx = waypoint.x() - playerX;
            final double playerDy = waypoint.y() - playerY;
            final double playerDz = waypoint.z() - playerZ;
            final double playerDistanceSquared =
                playerDx * playerDx + playerDy * playerDy + playerDz * playerDz;
            final boolean selected = waypointHighlightState.matchesEntry(
                waypoint, currentDimension
            ) || waypoint.id().equals(highlightedPlayerId);
            hasHighlight |= selected;
            // The configured cutoff hides a label entirely, even when aimed at; only a
            // selected/highlighted waypoint renders regardless of distance.
            if (playerDistanceSquared > maxDistanceSquared && !selected) {
                continue;
            }
            candidates.add(new LabelCandidate(waypoint, playerDistanceSquared, selected));

            final double dx = waypoint.x() - cameraPos.x;
            final double dy = waypoint.y() + WaypointLabelMetrics.LABEL_Y_OFFSET - cameraPos.y;
            final double dz = waypoint.z() - cameraPos.z;
            final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance <= 0.001) {
                continue;
            }
            final double alignment = WaypointHudMotion.alignment(
                cameraYaw, cameraPitch, dx, dy, dz, distance
            );
            if (!WaypointHudMotion.insideTargetCone(alignment, distance)) {
                continue;
            }
            if (alignment > bestAlignment || (alignment == bestAlignment && distance < bestDistance)) {
                targetedWaypoint = waypoint;
                bestAlignment = alignment;
                bestDistance = distance;
            }
        }

        return new LabelSelection(
            List.copyOf(candidates),
            targetedWaypoint == null ? null : targetedWaypoint.id(),
            hasHighlight
        );
    }

    static record LabelCandidate(
        WaypointRenderEntry waypoint,
        double distanceSquared,
        boolean selected
    ) {
        double distance() {
            return Math.sqrt(distanceSquared);
        }
    }

    static record LabelSelection(
        List<LabelCandidate> candidates,
        UUID targetedWaypointId,
        boolean hasHighlight
    ) {
    }

    private float animationDeltaSeconds() {
        final long now = System.nanoTime();
        if (lastAnimationNanos == 0L) {
            lastAnimationNanos = now;
            return 0.0f;
        }
        final float delta = MathHelper.clamp((now - lastAnimationNanos) / 1_000_000_000.0f, 0.0f, 0.1f);
        lastAnimationNanos = now;
        return delta;
    }

    private float updateLabelAnimation(final UUID waypointId, final boolean targeted, final float deltaSeconds) {
        final float current = labelAnimationProgress.getOrDefault(waypointId, 0.0f);
        final float next = WaypointHudMotion.advance(current, targeted, deltaSeconds);
        if (next <= 0.0f && !targeted) {
            labelAnimationProgress.remove(waypointId);
        } else {
            labelAnimationProgress.put(waypointId, next);
        }
        return next;
    }

    //#if MC>=12109 && MC<12111
    //$$ private static float tickDelta() {
    //#else
    //#if MC>=260100
    //$$ private static float tickDelta(final LevelRenderContext context) {
    //#else
    private static float tickDelta(final WorldRenderContext context) {
    //#endif
    //#endif
        //#if MC>=12109
        //$$ return MinecraftClient.getInstance().getRenderTickCounter().getTickProgress(false);
        //#elseif MC>=12100
        //$$ return context.tickCounter().getTickDelta(false);
        //#else
        return context.tickDelta();
        //#endif
    }
}

package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.radar.ServerPlayerRadarState;
import cn.net.rms.confluxmap.core.util.Argb;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import cn.net.rms.confluxmap.neoforge.compat.LevelRenderContext;
import cn.net.rms.confluxmap.neoforge.compat.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Gives waypoints an in-world presence (user feedback driving this slice: "waypoints
 * have NO in-world presence"): a vertical translucent beam at each visible waypoint's
 * column, and a camera-facing name/distance label floating above it.
 *
 * <p>The beam runs after translucent terrain through the NeoForge world-render event, so it
 * participates in world occlusion. The HUD marker runs at the final main-world phase and draws
 * with depth testing disabled so world content cannot hide player-facing navigation information.
 * The event context may not provide a pre-existing camera translation -
 * both are handled here: the beam is drawn with plain {@code Tessellator}/{@code
 * BufferBuilder} calls ({@link RenderUtil#fillTriangle3D}) instead of a vertex consumer,
 * and every position is explicitly translated by {@code worldPos - camera.getPos()}
 * before drawing. The label's text/background instead opens its own {@link
 * MultiBufferSource.BufferSource} (the same one vanilla uses for entity nametags,
 * {@code client.getBufferBuilders().getEntityVertexConsumers()}) which works at any
 * phase and is flushed once at the end of this render pass.
 *
 * <p>The beam and HUD label have deliberately separate distance rules. Beam geometry only
 * exists inside the vanilla view distance. Labels use {@code config.waypointRenderDistance}
 * as their cutoff, including its unlimited mode, and far labels are pulled inside the camera's
 * far plane along the same sight line so they remain visible after their beam disappears.
 */
public final class WaypointWorldRenderer {
    private static final int SELECTED_LOCATION_COLOR = 0xFFFFE066;
    private static final double BEAM_HALF_WIDTH = 0.18;
    private static final float BEAM_CORE_ALPHA = 0.55f;
    /** Same near-camera fade-in constant as the label (waypoint-ux.md S6), applied to horizontal distance from the beam column. */
    private static final double BEAM_NEAR_FADE_BLOCKS = 5.0;
    /** Beam alpha never drops below this fraction of {@link #BEAM_CORE_ALPHA} even at the render-distance edge - "intensifies as you approach" without vanishing far away. */
    private static final float BEAM_FAR_FLOOR = 0.30f;

    static final double LABEL_Y_OFFSET = 1.5;
    /** waypoint-ux.md S6 "distance fade-in": alpha ramps 0 -> 1 over the nearest ~5 blocks so the label doesn't pop in right next to the camera. */
    static final double LABEL_NEAR_FADE_BLOCKS = 5.0;
    static final float LABEL_BASE_SCALE = 0.06f;
    static final double LABEL_REFERENCE_DISTANCE = 12.0;
    static final float LABEL_MIN_SCALE_MULT = 0.35f;
    static final float LABEL_MAX_SCALE_MULT = 170.0f;
    static final float LABEL_ICON_COLLAPSED_SIZE = 12.0f;
    static final float LABEL_ICON_EXPANDED_SIZE = 18.0f;
    static final float LABEL_PANEL_HEIGHT = 20.0f;
    static final float LABEL_PANEL_PADDING = 3.0f;
    static final float LABEL_PANEL_GAP = 1.0f;
    static final float LABEL_TEXT_REVEAL_START = 0.72f;
    /** Leaves enough room before the world projection's far plane for the complete billboard. */
    private static final double LABEL_FAR_PLANE_MARGIN = 0.90;
    static final int LABEL_BACKGROUND_COLOR = 0xC0101010;
    static final int LABEL_LOCAL_OUTLINE_COLOR = 0xFF101010;
    static final int LABEL_SHARED_OUTLINE_COLOR = 0xFF55DDE0;
    static final int LABEL_NAME_COLOR = 0xFFFFFFFF;
    static final int LABEL_DISTANCE_COLOR = 0xFFC8C8C8;
    /** LightmapTextureManager.pack(15, 15) - always fully lit, like other UI-ish world markers. */
    private static final int LABEL_LIGHT = 0xF000F0;

    private final Minecraft client;
    private final ConfluxConfig config;
    private final GameBridge gameBridge;
    private final WaypointRenderCatalog waypointRenderCatalog;
    private final WaypointHighlightState waypointHighlightState;
    private final ServerPlayerRadarState serverPlayerRadar;
    private final WaypointItemHudRenderer waypointItemHudRenderer;
    private final Map<UUID, Float> labelAnimationProgress = new HashMap<>();
    private long lastAnimationNanos;

    public WaypointWorldRenderer(
        final Minecraft client,
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
    }

    public void register() {
        // The beam pipeline writes depth, so it must run after translucent terrain; drawing it
        // first makes water fail its depth test and disappear where the beam crosses it.
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(this::renderBeams);
        LevelRenderEvents.END_MAIN.register(this::renderHud);
    }
    private void renderBeams(final LevelRenderContext context) {
        if (!config.waypointBeamsEnabled) {
            return;
        }
        if (!gameBridge.session().active()) {
            return;
        }
        final Optional<PlayerView> playerViewOpt = gameBridge.player(tickDelta(context));
        if (playerViewOpt.isEmpty()) {
            return;
        }
        final PlayerView player = playerViewOpt.get();
        final DimensionId currentDimension = gameBridge.session().dimension();
        final Camera camera = client.gameRenderer.getMainCamera();
        final Vec3 cameraPos = camera.position();
        final PoseStack matrices = context.poseStack();
        final double maxDistance = beamVisibleDistance(MinecraftAccess.viewDistance(client));
        if (maxDistance <= 0.0) {
            return;
        }
        final double bottomY = client.level.getMinY();
        final double topY = client.level.getMaxY();
        final List<WaypointRenderEntry> waypoints = waypointsForRender(currentDimension);
        final boolean hasHighlight = waypointHighlightState.hasRenderableTarget(
            waypoints, currentDimension
        ) || serverPlayerRadar.highlightedPlayerId().isPresent();
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
            drawBeam(
                matrices, cameraPos, worldX, worldZ, bottomY, topY,
                waypoint.colorArgb(), horizontalDistance, maxDistance,
                highlightVisibilityAlpha(selected, hasHighlight)
                    * playerHighlightAlpha(waypoint, currentDimension)
            );
        }

        RenderUtil.restoreDefaultBlend();
    }
    private void renderHud(final LevelRenderContext context) {
        if (!config.waypointLabelsEnabled) {
            labelAnimationProgress.clear();
            waypointItemHudRenderer.publish(List.of());
            return;
        }
        if (!gameBridge.session().active()) {
            waypointItemHudRenderer.publish(List.of());
            return;
        }
        final float tickDelta = tickDelta(context);
        final Optional<PlayerView> playerViewOpt = gameBridge.player(tickDelta);
        if (playerViewOpt.isEmpty()) {
            waypointItemHudRenderer.publish(List.of());
            return;
        }
        final PlayerView player = playerViewOpt.get();
        final DimensionId currentDimension = gameBridge.session().dimension();
        final Camera camera = client.gameRenderer.getMainCamera();
        final Vec3 cameraPos = camera.position();
        final float cameraYaw = camera.yRot();
        final float cameraPitch = camera.xRot();
        final PoseStack matrices = context.poseStack();
        final double maxDistance = maxLabelDistance(config.waypointRenderDistance);
        final double labelProjectionDistance = labelProjectionDistance(
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
            // Modern LAST keeps the camera view rotation in ModelView while its context stack is
            // local identity. Legacy LAST needs that stale global transform cleared instead.
            RenderUtil.pushWorldHudModelView();
            try {
                final MultiBufferSource.BufferSource immediate = client.renderBuffers().bufferSource();
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
                    drawLabel(
                        matrices, immediate, context.submitNodeCollector(), camera,
                        cameraPos, waypoint.x(), waypoint.y(), waypoint.z(),
                        waypoint, renderDistance, labelProjectionDistance, progress,
                        highlightVisibilityAlpha(candidate.selected(), labelSelection.hasHighlight())
                            * playerHighlightAlpha(waypoint, currentDimension),
                        candidate.selected()
                    );
                }
                immediate.endBatch();
            } finally {
                RenderUtil.popModelView();
            }
        }

        waypointItemHudRenderer.publish(itemLabels);
        labelAnimationProgress.keySet().retainAll(visibleWaypointIds);
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

    private List<WaypointRenderEntry> waypointsForRender(final DimensionId dimension) {
        final List<WaypointRenderEntry> base = waypointRenderCatalog.snapshot(dimension);
        final Optional<ServerPlayerRadarState.HighlightView> playerTarget =
            serverPlayerRadar.highlightedIn(
                dimension,
                System.currentTimeMillis(),
                config.radarPlayerHighlightGhostSeconds * 1_000L
            );
        final Optional<WaypointHighlightState.Target> target = waypointHighlightState.target()
            .filter(value -> value.waypointId() == null && value.dimension().equals(dimension));
        if (target.isEmpty() && playerTarget.isEmpty()) {
            return base;
        }
        final List<WaypointRenderEntry> result = new ArrayList<>(base.size() + 2);
        result.addAll(base);
        target.ifPresent(value -> result.add(selectedTargetEntry(value)));
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

    private WaypointRenderEntry selectedTargetEntry(final WaypointHighlightState.Target target) {
        return WaypointHighlightState.locationEntry(
            target,
            Texts.translatable(
                WaypointHighlightState.SELECTED_LOCATION_TRANSLATION_KEY
            ).getString(),
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
        return Mth.clamp(y, client.level.getMinY(), client.level.getMaxY() - 1.0);
    }

    static LabelSelection selectLabels(
        final List<WaypointRenderEntry> waypoints,
        final float cameraYaw,
        final float cameraPitch,
        final Vec3 cameraPos,
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
        final Vec3 cameraPos,
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
        double targetedDistanceSquared = 0.0;
        boolean targetedWaypointVisible = false;
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
            final boolean withinDistance = playerDistanceSquared <= maxDistanceSquared;
            if (withinDistance || selected) {
                candidates.add(new LabelCandidate(waypoint, playerDistanceSquared, selected));
            }

            final double dx = waypoint.x() - cameraPos.x;
            final double dy = waypoint.y() + LABEL_Y_OFFSET - cameraPos.y;
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
                targetedDistanceSquared = playerDistanceSquared;
                targetedWaypointVisible = withinDistance || selected;
                bestAlignment = alignment;
                bestDistance = distance;
            }
        }

        if (targetedWaypoint != null && !targetedWaypointVisible) {
            candidates.add(new LabelCandidate(
                targetedWaypoint, targetedDistanceSquared, false
            ));
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
        final float delta = Mth.clamp((now - lastAnimationNanos) / 1_000_000_000.0f, 0.0f, 0.1f);
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

    /**
     * Square-tube beam (deliverable A's "4-sided prism" option) spanning the dimension's
     * full vertical range at the waypoint's X/Z, independent of the waypoint's own Y
     * (waypoint-ux.md S6: this is a deliberate simplification the reference implementation
     * also makes - a full-height column is trivial to draw and visible from anywhere at
     * that X/Z). Drawn double-sided (no back-face culling, see the caller) so the tube
     * reads correctly from inside or outside.
     */
    private void drawBeam(
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
        drawBeamSide(matrices, -h, -h, h, -h, bottom, top, color);
        drawBeamSide(matrices, h, -h, h, h, bottom, top, color);
        drawBeamSide(matrices, h, h, -h, h, bottom, top, color);
        drawBeamSide(matrices, -h, h, -h, -h, bottom, top, color);
        matrices.popPose();
    }

    /** One side face of the beam tube, from local (x0,z0) to (x1,z1), spanning bottom..top. */
    private void drawBeamSide(
        final PoseStack matrices,
        final float x0, final float z0,
        final float x1, final float z1,
        final float bottom, final float top,
        final int color
    ) {
        RenderUtil.fillTriangle3D(matrices, x0, bottom, z0, x1, bottom, z1, x1, top, z1, color);
        RenderUtil.fillTriangle3D(matrices, x0, bottom, z0, x1, top, z1, x0, top, z0, color);
    }

    /** Camera-facing marker with an interruptible, right-expanding detail panel. */
    private void drawLabel(
        final PoseStack matrices,
        final MultiBufferSource.BufferSource immediate,
        final SubmitNodeCollector submits,
        final Camera camera,
        final Vec3 cameraPos,
        final double worldX,
        final double worldY,
        final double worldZ,
        final WaypointRenderEntry waypoint,
        final double distance3d,
        final double projectionDistance,
        final float animationProgress,
        final float visibilityAlpha,
        final boolean selected
    ) {
        final float nearFade = (float) Mth.clamp(distance3d / LABEL_NEAR_FADE_BLOCKS, 0.0, 1.0);
        if (nearFade <= 0.01f) {
            return;
        }
        final double anchorX = worldX - cameraPos.x;
        final double anchorY = worldY + LABEL_Y_OFFSET - cameraPos.y;
        final double anchorZ = worldZ - cameraPos.z;
        final double anchorDistance = Math.sqrt(
            anchorX * anchorX + anchorY * anchorY + anchorZ * anchorZ
        );
        final double renderedDistance = projectedLabelDistance(anchorDistance, projectionDistance);
        final double projectionScale = anchorDistance > 0.001
            ? renderedDistance / anchorDistance
            : 1.0;

        // Scale against the projected distance so pulling a far marker closer does not enlarge it.
        final float scaleMult = (float) Mth.clamp(
            renderedDistance / LABEL_REFERENCE_DISTANCE, LABEL_MIN_SCALE_MULT, LABEL_MAX_SCALE_MULT
        );
        // Applied last so the user factor is a plain multiplier on apparent size at every distance.
        final float scale = LABEL_BASE_SCALE * scaleMult * config.waypointLabelScalePercent / 100f;
        final float easedProgress = WaypointHudMotion.smoothStep(animationProgress);

        final Font textRenderer = client.font;
        final String name = waypoint.name();
        final String distanceText = Math.round(distance3d) + " m";
        final int nameWidth = textRenderer.width(name);
        final int distanceWidth = textRenderer.width(distanceText);
        final float panelFullWidth = Math.max(nameWidth, distanceWidth) + LABEL_PANEL_PADDING * 2f;
        final float iconSize = Mth.lerp(
            easedProgress, LABEL_ICON_COLLAPSED_SIZE, LABEL_ICON_EXPANDED_SIZE
        );
        final float iconHalfSize = iconSize / 2f;
        final float panelX = iconHalfSize + LABEL_PANEL_GAP;
        final float panelReveal = Mth.clamp(easedProgress / LABEL_TEXT_REVEAL_START, 0f, 1f);
        final float panelWidth = panelFullWidth * panelReveal;
        // 26.1 collects item submits separately so they render in the current frame.
        matrices.pushPose();
        matrices.translate(
            anchorX * projectionScale,
            anchorY * projectionScale,
            anchorZ * projectionScale
        );
        matrices.mulPose(camera.rotation());
        matrices.scale(scale, -scale, scale);

        if (panelWidth > 0.5f) {
            RenderUtil.fillRect3D(
                matrices, panelX, -LABEL_PANEL_HEIGHT / 2f,
                panelWidth, LABEL_PANEL_HEIGHT, withAlpha(LABEL_BACKGROUND_COLOR, nearFade * visibilityAlpha)
            );
        }
        drawIcon(
            matrices, textRenderer, immediate, submits, waypoint, iconHalfSize,
            nearFade * config.waypointIconOpacity
                / (float) ConfluxConfig.MAX_WAYPOINT_ICON_OPACITY * visibilityAlpha,
            selected
        );

        final float textReveal = Mth.clamp(
            (easedProgress - LABEL_TEXT_REVEAL_START) / (1f - LABEL_TEXT_REVEAL_START), 0f, 1f
        );
        if (textReveal > 0.01f) {
            final float textX = panelX + LABEL_PANEL_PADDING + (1f - textReveal) * 4f;
            final float textAlpha = nearFade * textReveal * visibilityAlpha;
            RenderUtil.drawSeeThroughText(
                textRenderer, name, textX, -9f, withAlpha(LABEL_NAME_COLOR, textAlpha),
                matrices, immediate, LABEL_LIGHT
            );
            RenderUtil.drawSeeThroughText(
                textRenderer, distanceText, textX, 1f, withAlpha(LABEL_DISTANCE_COLOR, textAlpha),
                matrices, immediate, LABEL_LIGHT
            );
        }
        matrices.popPose();
    }
    private void drawIcon(
        final PoseStack matrices,
        final Font textRenderer,
        final MultiBufferSource.BufferSource immediate,
        final SubmitNodeCollector submits,
        final WaypointRenderEntry waypoint,
        final float halfSize,
        final float alpha,
        final boolean selected
    ) {
        final float size = halfSize * 2f;
        RenderUtil.fillRect3D(
            matrices, -halfSize - 1f, -halfSize - 1f, size + 2f, size + 2f,
            withAlpha(selected ? 0xFFFFE066 : outlineColor(waypoint), alpha)
        );
        RenderUtil.fillRect3D(
            matrices, -halfSize, -halfSize, size, size,
            withAlpha(waypoint.colorArgb() | 0xFF000000, alpha)
        );

        final String markerText = WaypointMarkerRenderer.markerText(
            waypoint.name(),
            waypoint.markerLabel()
        );
        final int initialWidth = textRenderer.width(markerText);
        final float available = Math.max(1f, size - 3f);
        final float textScale = Math.min(1f, available / Math.max(initialWidth, textRenderer.lineHeight));
        matrices.pushPose();
        matrices.scale(textScale, textScale, 1f);
        RenderUtil.drawSeeThroughText(
            textRenderer, markerText, -initialWidth / 2f, -textRenderer.lineHeight / 2f,
            withAlpha(WaypointMarkerRenderer.textColorFor(waypoint.colorArgb()), alpha),
            matrices, immediate, LABEL_LIGHT
        );
        matrices.popPose();
    }

    static int withAlpha(final int argb, final float alpha) {
        final int a = Math.round(Argb.alpha(argb) * Mth.clamp(alpha, 0f, 1f));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    static int outlineColor(final WaypointRenderEntry waypoint) {
        if (!waypoint.shared()) {
            return LABEL_LOCAL_OUTLINE_COLOR;
        }
        return LABEL_SHARED_OUTLINE_COLOR;
    }
    private static float tickDelta(final LevelRenderContext context) {
        return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }
}

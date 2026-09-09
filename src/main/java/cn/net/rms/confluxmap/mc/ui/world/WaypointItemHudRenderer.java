package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.WaypointMarkerRenderer;
import cn.net.rms.confluxmap.mc.radar.EntityIconManager;
import cn.net.rms.confluxmap.mc.radar.RadarMarkerRenderer;
import java.util.List;
import java.util.UUID;
import cn.net.rms.confluxmap.neoforge.compat.HudElementRegistry;
import cn.net.rms.confluxmap.neoforge.compat.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.DeltaTracker;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Draws complete item-backed waypoint labels in one current-frame flat HUD pass. */
public final class WaypointItemHudRenderer {
    private final Minecraft client;
    private final ConfluxConfig config;
    private final EntityIconManager iconManager;
    private List<Label> labels = List.of();

    public WaypointItemHudRenderer(final Minecraft client, final ConfluxConfig config) {
        this(client, config, null);
    }

    public WaypointItemHudRenderer(
        final Minecraft client,
        final ConfluxConfig config,
        final EntityIconManager iconManager
    ) {
        this.client = client;
        this.config = config;
        this.iconManager = iconManager;
    }

    public void register() {
        HudElementRegistry.attachElementBefore(
            VanillaHudElements.CROSSHAIR,
            cn.net.rms.confluxmap.compat.Ids.of("confluxmap", "waypoint_items"),
            this::render
        );
    }

    public void publish(final List<Label> labels) {
        this.labels = List.copyOf(labels);
    }

    List<Label> snapshot() {
        return labels;
    }
    private void render(final GuiGraphicsExtractor context, final DeltaTracker tickCounter) {
        draw(GuiDraw.of(context), 0f);
    }

    private void draw(final GuiDraw draw, final float tickDelta) {
        final Camera camera = camera();
        final Vec3 cameraPos = cameraPosition(camera);
        final float cameraYaw = cameraYaw(camera);
        final float cameraPitch = cameraPitch(camera);
        final double verticalFov = verticalFov(camera, tickDelta);
        final int screenWidth = client.getWindow().getGuiScaledWidth();
        final int screenHeight = client.getWindow().getGuiScaledHeight();

        for (final Label label : labels) {
            final WaypointRenderEntry waypoint = label.waypoint();
            final double dx = waypoint.x() - cameraPos.x;
            final double dy = waypoint.y() + WaypointWorldRenderer.LABEL_Y_OFFSET - cameraPos.y;
            final double dz = waypoint.z() - cameraPos.z;
            final double anchorDistance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            final double renderedDistance = WaypointWorldRenderer.projectedLabelDistance(
                anchorDistance, label.projectionDistance()
            );
            final float easedProgress = WaypointHudMotion.smoothStep(label.animationProgress());
            final float iconSize = Mth.lerp(
                easedProgress,
                WaypointWorldRenderer.LABEL_ICON_COLLAPSED_SIZE,
                WaypointWorldRenderer.LABEL_ICON_EXPANDED_SIZE
            );
            WaypointHudItemProjection.project(
                cameraYaw,
                cameraPitch,
                dx,
                dy,
                dz,
                screenWidth,
                screenHeight,
                verticalFov,
                renderedDistance,
                iconSize,
                config.waypointLabelScalePercent
            ).ifPresent(placement -> drawLabel(draw, label, placement, easedProgress));
        }
    }

    private void drawLabel(
        final GuiDraw draw,
        final Label label,
        final WaypointHudItemProjection.Placement placement,
        final float easedProgress
    ) {
        final WaypointRenderEntry waypoint = label.waypoint();
        final float nearFade = (float) Mth.clamp(
            label.distance3d() / WaypointWorldRenderer.LABEL_NEAR_FADE_BLOCKS,
            0.0,
            1.0
        );
        if (nearFade <= 0.01f) {
            return;
        }
        final float unitScale = placement.unitScale();
        final float iconSize = placement.size();
        final float iconHalfSize = iconSize / 2f;
        final float centerX = placement.centerX();
        final float centerY = placement.centerY();
        final String name = waypoint.name();
        final String distanceText = Math.round(label.distance3d()) + " m";
        final Font textRenderer = textRenderer();
        final int nameWidth = textRenderer.width(name);
        final int distanceWidth = textRenderer.width(distanceText);
        final float panelFullWidth = Math.max(nameWidth, distanceWidth)
            + WaypointWorldRenderer.LABEL_PANEL_PADDING * 2f;
        final float panelReveal = Mth.clamp(
            easedProgress / WaypointWorldRenderer.LABEL_TEXT_REVEAL_START, 0f, 1f
        );
        final float panelWidth = panelFullWidth * panelReveal * unitScale;
        final float panelX = centerX + iconHalfSize
            + WaypointWorldRenderer.LABEL_PANEL_GAP * unitScale;
        final float visibilityAlpha = nearFade * label.visibilityAlpha();
        if (panelWidth > 0.5f) {
            fill(
                draw,
                panelX,
                centerY - WaypointWorldRenderer.LABEL_PANEL_HEIGHT * unitScale / 2f,
                panelX + panelWidth,
                centerY + WaypointWorldRenderer.LABEL_PANEL_HEIGHT * unitScale / 2f,
                WaypointWorldRenderer.withAlpha(
                    WaypointWorldRenderer.LABEL_BACKGROUND_COLOR, visibilityAlpha
                )
            );
        }

        final float plateAlpha = visibilityAlpha * config.waypointIconOpacity
            / (float) ConfluxConfig.MAX_WAYPOINT_ICON_OPACITY;
        fill(
            draw,
            centerX - iconHalfSize - unitScale,
            centerY - iconHalfSize - unitScale,
            centerX + iconHalfSize + unitScale,
            centerY + iconHalfSize + unitScale,
            WaypointWorldRenderer.withAlpha(
                label.selected() ? 0xFFFFE066 : WaypointWorldRenderer.outlineColor(waypoint),
                plateAlpha
            )
        );
        fill(
            draw,
            centerX - iconHalfSize,
            centerY - iconHalfSize,
            centerX + iconHalfSize,
            centerY + iconHalfSize,
            WaypointWorldRenderer.withAlpha(waypoint.colorArgb() | 0xFF000000, plateAlpha)
        );
        if (label.playerId() != null) {
            RadarMarkerRenderer.drawPlayerPortrait(
                draw,
                client,
                iconManager,
                label.playerId(),
                centerX,
                centerY,
                iconSize,
                plateAlpha,
                label.selected()
            );
        } else {
            final ItemStack stack = WaypointMarkerRenderer.itemIcon(waypoint.iconItemId());
            if (!stack.isEmpty()) {
                draw.drawItemIcon(client, stack, centerX, centerY, iconSize);
            }
        }

        final float textReveal = Mth.clamp(
            (easedProgress - WaypointWorldRenderer.LABEL_TEXT_REVEAL_START)
                / (1f - WaypointWorldRenderer.LABEL_TEXT_REVEAL_START),
            0f,
            1f
        );
        if (textReveal <= 0.01f) {
            return;
        }
        final float textX = iconSize / (2f * unitScale)
            + WaypointWorldRenderer.LABEL_PANEL_GAP
            + WaypointWorldRenderer.LABEL_PANEL_PADDING
            + (1f - textReveal) * 4f;
        final float textAlpha = visibilityAlpha * textReveal;
        draw.pushTransform();
        draw.translate(centerX, centerY);
        draw.scale(unitScale, unitScale);
        draw.drawTextWithShadow(
            textRenderer,
            name,
            textX,
            -9f,
            WaypointWorldRenderer.withAlpha(WaypointWorldRenderer.LABEL_NAME_COLOR, textAlpha)
        );
        draw.drawTextWithShadow(
            textRenderer,
            distanceText,
            textX,
            1f,
            WaypointWorldRenderer.withAlpha(WaypointWorldRenderer.LABEL_DISTANCE_COLOR, textAlpha)
        );
        draw.popTransform();
    }

    private Font textRenderer() {
        return client.font;
    }

    private static void fill(
        final GuiDraw draw,
        final float x1,
        final float y1,
        final float x2,
        final float y2,
        final int color
    ) {
        draw.fill(
            Mth.floor(x1),
            Mth.floor(y1),
            Mth.ceil(x2),
            Mth.ceil(y2),
            color
        );
    }

    private Camera camera() {
        return client.gameRenderer.getMainCamera();
    }

    private static Vec3 cameraPosition(final Camera camera) {
        return camera.position();
    }

    private static float cameraYaw(final Camera camera) {
        return camera.yRot();
    }

    private static float cameraPitch(final Camera camera) {
        return camera.xRot();
    }

    private double verticalFov(final Camera camera, final float tickDelta) {
        return camera.getFov();
    }

    public record Label(
        WaypointRenderEntry waypoint,
        double distance3d,
        double projectionDistance,
        float animationProgress,
        float visibilityAlpha,
        boolean selected,
        UUID playerId
    ) {
        public Label(
            final WaypointRenderEntry waypoint,
            final double distance3d,
            final double projectionDistance,
            final float animationProgress,
            final float visibilityAlpha,
            final boolean selected
        ) {
            this(
                waypoint, distance3d, projectionDistance,
                animationProgress, visibilityAlpha, selected, null
            );
        }
    }
}

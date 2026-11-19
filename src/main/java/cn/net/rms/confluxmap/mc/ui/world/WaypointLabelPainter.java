package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.core.waypoint.WaypointVerticalRelation;
import cn.net.rms.confluxmap.mc.ui.WaypointMarkerRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Pure label layout: one camera-facing marker with an interruptible, right-expanding detail
 * panel, placed camera-relative and sized by perspective. Sizing, colors, and reveal timing
 * come from {@link WaypointLabelStyle}/{@link WaypointLabelMetrics}, shared with the
 * item-icon HUD flavor so both read identically. Submission and the billboard rotation are
 * version-specific and arrive through the {@link Sink} {@link LabelPass} provides.
 */
final class WaypointLabelPainter {
    /** The version-specific submissions one label needs; implemented by {@link LabelPass}. */
    interface Sink {
        /** Applies the billboard rotation and the label scale (with the version's scale sign). */
        void billboard(PoseStack matrices, float scale);

        /** One flat colored quad in label space, after the billboard transform. */
        void rect(PoseStack matrices, float x, float y, float width, float height, int color);

        /** One flat colored triangle in label space, after the billboard transform. */
        void triangle(
            PoseStack matrices, float x0, float y0, float x1, float y1, float x2, float y2, int color
        );

        /** One see-through text run in label space, after the billboard transform. */
        void text(PoseStack matrices, String text, float x, float y, int color);
    }

    private final Minecraft client;
    private final ConfluxConfig config;

    WaypointLabelPainter(final Minecraft client, final ConfluxConfig config) {
        this.client = client;
        this.config = config;
    }

    void drawLabel(
        final PoseStack matrices,
        final Vec3 cameraPos,
        final double worldX,
        final double worldY,
        final double worldZ,
        final WaypointRenderEntry waypoint,
        final double distance3d,
        final double projectionDistance,
        final float animationProgress,
        final float visibilityAlpha,
        final boolean selected,
        final WaypointVerticalRelation verticalRelation,
        final Sink sink
    ) {
        final float nearFade = (float) Mth.clamp(
            distance3d / WaypointLabelStyle.LABEL_NEAR_FADE_BLOCKS, 0.0, 1.0
        );
        if (nearFade <= 0.01f) {
            return;
        }
        final float labelOpacity = WaypointLabelStyle.labelOpacityFactor(config);
        final double anchorX = worldX - cameraPos.x;
        final double anchorY = worldY + WaypointLabelMetrics.LABEL_Y_OFFSET - cameraPos.y;
        final double anchorZ = worldZ - cameraPos.z;
        final double anchorDistance = Math.sqrt(
            anchorX * anchorX + anchorY * anchorY + anchorZ * anchorZ
        );
        final double renderedDistance = WaypointLabelMetrics.projectedLabelDistance(
            anchorDistance, projectionDistance
        );
        final double projectionScale = anchorDistance > 0.001
            ? renderedDistance / anchorDistance
            : 1.0;

        final float easedProgress = WaypointHudMotion.smoothStep(animationProgress);
        // Perspective sizing: apparent size peaks at the legacy constant-screen-size look at or
        // inside the reference distance and shrinks with true 1/d perspective beyond it, floored
        // far away; the aim/select animation zooms the label back to full apparent size. Scaling
        // against the projected distance also keeps pulling a far marker closer (far-plane
        // clamping) from enlarging it.
        final float scaleMult = WaypointLabelMetrics.labelWorldScaleMult(renderedDistance, easedProgress);
        // Applied last so the user factor is a plain multiplier on apparent size at every distance.
        final float scale = WaypointLabelStyle.LABEL_BASE_SCALE
            * scaleMult * config.waypointLabelScalePercent / 100f;

        final Font font = client.font;
        final WaypointLabelStyle.Panel panel = WaypointLabelStyle.Panel.of(
            font, waypoint, distance3d, easedProgress
        );
        final float iconSize = Mth.lerp(
            easedProgress,
            WaypointLabelStyle.LABEL_ICON_COLLAPSED_SIZE,
            WaypointLabelStyle.LABEL_ICON_EXPANDED_SIZE
        );
        final float iconHalfSize = iconSize / 2f;
        final float panelX = iconHalfSize + WaypointLabelStyle.LABEL_PANEL_GAP;
        final float panelWidth = panel.width();

        matrices.pushPose();
        matrices.translate(
            anchorX * projectionScale,
            anchorY * projectionScale,
            anchorZ * projectionScale
        );
        sink.billboard(matrices, scale);

        if (panelWidth > 0.5f) {
            sink.rect(
                matrices, panelX, -WaypointLabelStyle.LABEL_PANEL_HEIGHT / 2f,
                panelWidth, WaypointLabelStyle.LABEL_PANEL_HEIGHT,
                WaypointLabelStyle.withAlpha(
                    WaypointLabelStyle.LABEL_BACKGROUND_COLOR,
                    nearFade * visibilityAlpha * labelOpacity
                )
            );
        }
        drawIcon(
            matrices, font, waypoint, iconHalfSize,
            nearFade * labelOpacity * visibilityAlpha,
            selected, verticalRelation, sink
        );

        if (panel.textReveal() > 0.01f) {
            final float textX = panel.textX(panelX);
            final float textAlpha = panel.textAlpha(nearFade * visibilityAlpha * labelOpacity);
            sink.text(
                matrices, panel.name(), textX, WaypointLabelStyle.LABEL_NAME_TEXT_Y,
                WaypointLabelStyle.withAlpha(WaypointLabelStyle.LABEL_NAME_COLOR, textAlpha)
            );
            if (!panel.nameSuffix().isEmpty()) {
                // Plain locals: the mapping engine misses chained accessor args (see WaypointListScreen).
                final String panelName = panel.name();
                final int panelNameWidth = font.width(panelName);
                matrices.pushPose();
                matrices.translate(
                    textX + panelNameWidth + 1f,
                    WaypointLabelStyle.LABEL_NAME_TEXT_Y,
                    0f
                );
                matrices.scale(0.5f, 0.5f, 1f);
                sink.text(
                    matrices, panel.nameSuffix(), 0f, 0f,
                    WaypointLabelStyle.withAlpha(WaypointLabelStyle.LABEL_DISTANCE_COLOR, textAlpha)
                );
                matrices.popPose();
            }
            sink.text(
                matrices, panel.distanceText(), textX, WaypointLabelStyle.LABEL_DISTANCE_TEXT_Y,
                WaypointLabelStyle.withAlpha(WaypointLabelStyle.LABEL_DISTANCE_COLOR, textAlpha)
            );
        }
        matrices.popPose();
    }

    private void drawIcon(
        final PoseStack matrices,
        final Font font,
        final WaypointRenderEntry waypoint,
        final float halfSize,
        final float alpha,
        final boolean selected,
        final WaypointVerticalRelation verticalRelation,
        final Sink sink
    ) {
        final float size = halfSize * 2f;
        sink.rect(
            matrices, -halfSize - 1f, -halfSize - 1f, size + 2f, size + 2f,
            WaypointLabelStyle.withAlpha(
                selected ? 0xFFFFE066 : WaypointLabelStyle.outlineColor(waypoint), alpha
            )
        );
        sink.rect(
            matrices, -halfSize, -halfSize, size, size,
            WaypointLabelStyle.withAlpha(waypoint.colorArgb() | 0xFF000000, alpha)
        );

        final String markerText = WaypointMarkerRenderer.markerText(
            waypoint.name(),
            waypoint.markerLabel()
        );
        final int initialWidth = font.width(markerText);
        final float available = Math.max(1f, size - 3f);
        final float textScale = Math.min(1f, available / Math.max(initialWidth, font.lineHeight));
        matrices.pushPose();
        matrices.scale(textScale, textScale, 1f);
        sink.text(
            matrices, markerText, -initialWidth / 2f, -font.lineHeight / 2f,
            WaypointLabelStyle.withAlpha(
                WaypointMarkerRenderer.textColorFor(waypoint.colorArgb()), alpha
            )
        );
        matrices.popPose();
        drawHeightBadge(verticalRelation, halfSize, alpha, matrices, sink);
        // Same "T" badge as the map markers, via the sink: this plate shares no GUI primitives.
        if (waypoint.type() == Waypoint.Type.TEMPORARY && waypoint.local()) {
            final float badgeHalf = WaypointMarkerRenderer.TEMPORARY_BADGE_SIZE / 2f;
            sink.rect(
                matrices, halfSize - badgeHalf, -halfSize - badgeHalf,
                WaypointMarkerRenderer.TEMPORARY_BADGE_SIZE,
                WaypointMarkerRenderer.TEMPORARY_BADGE_SIZE,
                WaypointLabelStyle.withAlpha(WaypointMarkerRenderer.TEMPORARY_BADGE_OUTLINE, alpha)
            );
            final int letterWidth = font.width(WaypointMarkerRenderer.TEMPORARY_BADGE_LETTER);
            matrices.pushPose();
            matrices.translate(halfSize, -halfSize, 0f);
            matrices.scale(0.5f, 0.5f, 1f);
            sink.text(
                matrices, WaypointMarkerRenderer.TEMPORARY_BADGE_LETTER,
                -letterWidth / 2f, -font.lineHeight / 2f,
                WaypointLabelStyle.withAlpha(WaypointMarkerRenderer.TEMPORARY_BADGE_TEXT, alpha)
            );
            matrices.popPose();
        }
    }

    /**
     * The label icon's height badge at its top-left corner; one geometry source with the
     * map markers via {@link WaypointMarkerRenderer#emitHeightBadge}.
     */
    static void drawHeightBadge(
        final WaypointVerticalRelation relation,
        final float halfSize,
        final float alpha,
        final PoseStack matrices,
        final Sink sink
    ) {
        if (relation == WaypointVerticalRelation.NONE) {
            return;
        }
        WaypointMarkerRenderer.emitHeightBadge(
            relation, -halfSize, -halfSize, alpha,
            new WaypointMarkerRenderer.HeightBadgeConsumer() {
                @Override
                public void rect(final float x, final float y, final float w, final float h, final int color) {
                    sink.rect(matrices, x, y, w, h, color);
                }

                @Override
                public void triangle(
                    final float x0, final float y0, final float x1, final float y1,
                    final float x2, final float y2, final int color
                ) {
                    sink.triangle(matrices, x0, y0, x1, y1, x2, y2, color);
                }
            }
        );
    }
}
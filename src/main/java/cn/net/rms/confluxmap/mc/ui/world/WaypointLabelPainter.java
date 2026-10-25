package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import cn.net.rms.confluxmap.mc.ui.WaypointMarkerRenderer;
//#if MC>=260200
//$$ import net.fabricmc.fabric.api.client.rendering.v1.FabricOrderedSubmitNodeCollector;
//$$ import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases;
//$$ import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
//$$ import net.minecraft.client.renderer.feature.TextFeatureRenderer;
//$$ import net.minecraft.client.renderer.rendertype.RenderTypes;
//$$ import net.minecraft.network.chat.Component;
//$$ import org.joml.Matrix4f;
//$$ import org.joml.Quaternionf;
//#if MC>=260300
//$$ import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
//#endif
//#endif
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
//#if MC>=260100
//$$ import net.minecraft.client.renderer.SubmitNodeCollector;
//#endif
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
 * Draws one in-world waypoint label: a camera-facing marker with an interruptible,
 * right-expanding detail panel. Sizing, colors, and reveal timing come from
 * {@link WaypointLabelStyle}/{@link WaypointLabelMetrics}, shared with the item-icon
 * HUD flavor so both read identically.
 */
final class WaypointLabelPainter {
    private final MinecraftClient client;
    private final ConfluxConfig config;

    WaypointLabelPainter(final MinecraftClient client, final ConfluxConfig config) {
        this.client = client;
        this.config = config;
    }

    //#if MC>=260200
    //$$ void drawLabel(
    //$$     final PoseStack matrices,
    //$$     final SubmitNodeCollector submits,
    //$$     final Quaternionf cameraRotation,
    //#else
    void drawLabel(
        final MatrixStack matrices,
        final VertexConsumerProvider.Immediate immediate,
    //#if MC>=260100
        //$$ final SubmitNodeCollector submits,
    //#elseif MC>=12109
        //$$ final OrderedRenderCommandQueue commandQueue,
    //#endif
        final Camera camera,
    //#endif
        final Vec3d cameraPos,
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
        final float nearFade = (float) MathHelper.clamp(
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

        final TextRenderer textRenderer = client.textRenderer;
        final String name = waypoint.name();
        final String distanceText = Math.round(distance3d) + " m";
        final int nameWidth = textRenderer.getWidth(name);
        final int distanceWidth = textRenderer.getWidth(distanceText);
        final float panelFullWidth = Math.max(nameWidth, distanceWidth)
            + WaypointLabelStyle.LABEL_PANEL_PADDING * 2f;
        final float iconSize = MathHelper.lerp(
            easedProgress,
            WaypointLabelStyle.LABEL_ICON_COLLAPSED_SIZE,
            WaypointLabelStyle.LABEL_ICON_EXPANDED_SIZE
        );
        final float iconHalfSize = iconSize / 2f;
        final float panelX = iconHalfSize + WaypointLabelStyle.LABEL_PANEL_GAP;
        final float panelReveal = MathHelper.clamp(
            easedProgress / WaypointLabelStyle.LABEL_TEXT_REVEAL_START, 0f, 1f
        );
        final float panelWidth = panelFullWidth * panelReveal;

        //#if MC>=260100 && MC<260200
        //$$ // 26.1 collects item submits separately so they render in the current frame.
        //#endif
        matrices.push();
        matrices.translate(
            anchorX * projectionScale,
            anchorY * projectionScale,
            anchorZ * projectionScale
        );
        //#if MC>=260300
        //$$ // 26.3 dropped mulPose(Quaternionf); rotate through a matrix.
        //$$ matrices.mulPose(new Matrix4f().rotation(cameraRotation));
        //#elseif MC>=260200
        //$$ matrices.mulPose(cameraRotation);
        //#else
        matrices.multiply(camera.getRotation());
        //#endif
        //#if MC>=12100
        //$$ matrices.scale(scale, -scale, scale);
        //#else
        matrices.scale(-scale, -scale, scale);
        //#endif

        //#if MC>=260300
        //$$ final OrderedSubmitNodeCollector plates = submits.order(0);
        //$$ final OrderedSubmitNodeCollector text = submits.order(1);
        //#elseif MC>=260200
        //$$ final FabricOrderedSubmitNodeCollector plates =
        //$$     (FabricOrderedSubmitNodeCollector) submits.order(0);
        //$$ final FabricOrderedSubmitNodeCollector text =
        //$$     (FabricOrderedSubmitNodeCollector) submits.order(1);
        //#endif

        if (panelWidth > 0.5f) {
            //#if MC>=260200
            //$$ submitRect(
            //$$     plates, matrices, panelX, -WaypointLabelStyle.LABEL_PANEL_HEIGHT / 2f,
            //$$     panelWidth, WaypointLabelStyle.LABEL_PANEL_HEIGHT,
            //$$     WaypointLabelStyle.withAlpha(
            //$$         WaypointLabelStyle.LABEL_BACKGROUND_COLOR,
            //$$         nearFade * visibilityAlpha * labelOpacity
            //$$     )
            //$$ );
            //#else
            RenderUtil.fillRect3D(
                matrices, panelX, -WaypointLabelStyle.LABEL_PANEL_HEIGHT / 2f,
                panelWidth, WaypointLabelStyle.LABEL_PANEL_HEIGHT,
                WaypointLabelStyle.withAlpha(
                    WaypointLabelStyle.LABEL_BACKGROUND_COLOR,
                    nearFade * visibilityAlpha * labelOpacity
                )
            );
            //#endif
        }
        //#if MC>=260200
        //$$ drawIcon(
        //$$     matrices, textRenderer, plates, text, submits, waypoint, iconHalfSize,
        //$$     nearFade * labelOpacity * visibilityAlpha,
        //$$     selected
        //$$ );
        //#elseif MC>=260100
        //$$ drawIcon(
        //$$     matrices, textRenderer, immediate, submits, waypoint, iconHalfSize,
        //$$     nearFade * labelOpacity * visibilityAlpha,
        //$$     selected
        //$$ );
        //#elseif MC>=12109
        //$$ drawIcon(
        //$$     matrices, textRenderer, immediate, commandQueue, waypoint, iconHalfSize,
        //$$     nearFade * labelOpacity * visibilityAlpha,
        //$$     selected
        //$$ );
        //#else
        drawIcon(
            matrices, textRenderer, immediate, waypoint, iconHalfSize,
            nearFade * labelOpacity * visibilityAlpha,
            selected
        );
        //#endif

        final float textReveal = MathHelper.clamp(
            (easedProgress - WaypointLabelStyle.LABEL_TEXT_REVEAL_START)
                / (1f - WaypointLabelStyle.LABEL_TEXT_REVEAL_START),
            0f, 1f
        );
        if (textReveal > 0.01f) {
            final float textX = panelX + WaypointLabelStyle.LABEL_PANEL_PADDING + (1f - textReveal) * 4f;
            final float textAlpha = nearFade * textReveal * visibilityAlpha * labelOpacity;
            //#if MC>=260200
            //$$ submitText(
            //$$     text, matrices, name, textX, -9f,
            //$$     WaypointLabelStyle.withAlpha(WaypointLabelStyle.LABEL_NAME_COLOR, textAlpha)
            //$$ );
            //$$ submitText(
            //$$     text, matrices, distanceText, textX, 1f,
            //$$     WaypointLabelStyle.withAlpha(WaypointLabelStyle.LABEL_DISTANCE_COLOR, textAlpha)
            //$$ );
            //#else
            RenderUtil.drawSeeThroughText(
                textRenderer, name, textX, -9f,
                WaypointLabelStyle.withAlpha(WaypointLabelStyle.LABEL_NAME_COLOR, textAlpha),
                matrices, immediate, WaypointLabelStyle.LABEL_LIGHT
            );
            RenderUtil.drawSeeThroughText(
                textRenderer, distanceText, textX, 1f,
                WaypointLabelStyle.withAlpha(WaypointLabelStyle.LABEL_DISTANCE_COLOR, textAlpha),
                matrices, immediate, WaypointLabelStyle.LABEL_LIGHT
            );
            //#endif
        }
        matrices.pop();
    }

    //#if MC>=260200
    //$$ private void drawIcon(
    //$$     final PoseStack matrices,
    //$$     final Font textRenderer,
    //#if MC>=260300
    //$$     final OrderedSubmitNodeCollector plates,
    //$$     final OrderedSubmitNodeCollector text,
    //#else
    //$$     final FabricOrderedSubmitNodeCollector plates,
    //$$     final FabricOrderedSubmitNodeCollector text,
    //#endif
    //$$     final SubmitNodeCollector submits,
    //#else
    private void drawIcon(
        final MatrixStack matrices,
        final TextRenderer textRenderer,
        final VertexConsumerProvider.Immediate immediate,
    //#if MC>=260100
        //$$ final SubmitNodeCollector submits,
    //#elseif MC>=12109
        //$$ final OrderedRenderCommandQueue commandQueue,
    //#endif
    //#endif
        final WaypointRenderEntry waypoint,
        final float halfSize,
        final float alpha,
        final boolean selected
    ) {
        final float size = halfSize * 2f;
        //#if MC>=260200
        //$$ submitRect(
        //$$     plates, matrices, -halfSize - 1f, -halfSize - 1f, size + 2f, size + 2f,
        //$$     WaypointLabelStyle.withAlpha(
        //$$         selected ? 0xFFFFE066 : WaypointLabelStyle.outlineColor(waypoint), alpha
        //$$     )
        //$$ );
        //$$ submitRect(
        //$$     plates, matrices, -halfSize, -halfSize, size, size,
        //$$     WaypointLabelStyle.withAlpha(waypoint.colorArgb() | 0xFF000000, alpha)
        //$$ );
        //#else
        RenderUtil.fillRect3D(
            matrices, -halfSize - 1f, -halfSize - 1f, size + 2f, size + 2f,
            WaypointLabelStyle.withAlpha(
                selected ? 0xFFFFE066 : WaypointLabelStyle.outlineColor(waypoint), alpha
            )
        );
        RenderUtil.fillRect3D(
            matrices, -halfSize, -halfSize, size, size,
            WaypointLabelStyle.withAlpha(waypoint.colorArgb() | 0xFF000000, alpha)
        );
        //#endif

        final String markerText = WaypointMarkerRenderer.markerText(
            waypoint.name(),
            waypoint.markerLabel()
        );
        final int initialWidth = textRenderer.getWidth(markerText);
        final float available = Math.max(1f, size - 3f);
        final float textScale = Math.min(1f, available / Math.max(initialWidth, textRenderer.fontHeight));
        matrices.push();
        matrices.scale(textScale, textScale, 1f);
        //#if MC>=260200
        //$$ submitText(
        //$$     text, matrices, markerText, -initialWidth / 2f, -textRenderer.lineHeight / 2f,
        //$$     WaypointLabelStyle.withAlpha(
        //$$         WaypointMarkerRenderer.textColorFor(waypoint.colorArgb()), alpha
        //$$     )
        //$$ );
        //#else
        RenderUtil.drawSeeThroughText(
            textRenderer, markerText, -initialWidth / 2f, -textRenderer.fontHeight / 2f,
            WaypointLabelStyle.withAlpha(
                WaypointMarkerRenderer.textColorFor(waypoint.colorArgb()), alpha
            ),
            matrices, immediate, WaypointLabelStyle.LABEL_LIGHT
        );
        //#endif
        matrices.pop();
    }

    //#if MC>=260200
    //#if MC>=260300
    //$$ private static void submitRect(
    //$$     final OrderedSubmitNodeCollector submits,
    //$$     final PoseStack matrices,
    //$$     final float x,
    //$$     final float y,
    //$$     final float width,
    //$$     final float height,
    //$$     final int color
    //$$ ) {
    //$$     // 26.3 removed RenderTypes.textBackgroundSeeThrough and rebuilt the text Submit
    //$$     // records around Content objects; the collector's own background submit replaces
    //$$     // the custom-geometry path entirely.
    //$$     submits.submitTextBackground(
    //$$         matrices, x, y, x + width, y + height, color,
    //$$         Font.DisplayMode.SEE_THROUGH, WaypointLabelStyle.LABEL_LIGHT
    //$$     );
    //$$ }
    //$$
    //$$ private static void submitText(
    //$$     final OrderedSubmitNodeCollector submits,
    //$$     final PoseStack matrices,
    //$$     final String text,
    //$$     final float x,
    //$$     final float y,
    //$$     final int color
    //$$ ) {
    //$$     submits.submitText(
    //$$         matrices, x, y, Component.literal(text).getVisualOrderText(),
    //$$         false, Font.DisplayMode.SEE_THROUGH, WaypointLabelStyle.LABEL_LIGHT, color, 0, 0
    //$$     );
    //$$ }
    //#else
    //$$ private static void submitRect(
    //$$     final FabricOrderedSubmitNodeCollector submits,
    //$$     final PoseStack matrices,
    //$$     final float x,
    //$$     final float y,
    //$$     final float width,
    //$$     final float height,
    //$$     final int color
    //$$ ) {
    //$$     submits.submitCustom(
    //$$         SubmitRenderPhases.ALWAYS_ON_TOP,
    //$$         new CustomFeatureRenderer.Submit(
    //$$             matrices.last().copy(),
    //$$             RenderTypes.textBackgroundSeeThrough(),
    //$$             (pose, vertices) -> {
    //$$                 vertices.addVertex(pose, x, y + height, 0f).setColor(color).setLight(WaypointLabelStyle.LABEL_LIGHT);
    //$$                 vertices.addVertex(pose, x + width, y + height, 0f).setColor(color).setLight(WaypointLabelStyle.LABEL_LIGHT);
    //$$                 vertices.addVertex(pose, x + width, y, 0f).setColor(color).setLight(WaypointLabelStyle.LABEL_LIGHT);
    //$$                 vertices.addVertex(pose, x, y, 0f).setColor(color).setLight(WaypointLabelStyle.LABEL_LIGHT);
    //$$             }
    //$$         )
    //$$     );
    //$$ }
    //$$
    //$$ private static void submitText(
    //$$     final FabricOrderedSubmitNodeCollector submits,
    //$$     final PoseStack matrices,
    //$$     final String text,
    //$$     final float x,
    //$$     final float y,
    //$$     final int color
    //$$ ) {
    //$$     submits.submitCustom(
    //$$         SubmitRenderPhases.ALWAYS_ON_TOP,
    //$$         new TextFeatureRenderer.Submit(
    //$$             new Matrix4f(matrices.last().pose()),
    //$$             x,
    //$$             y,
    //$$             Component.literal(text).getVisualOrderText(),
    //$$             false,
    //$$             Font.DisplayMode.SEE_THROUGH,
    //$$             WaypointLabelStyle.LABEL_LIGHT,
    //$$             color,
    //$$             0,
    //$$             0
    //$$         )
    //$$     );
    //$$ }
    //#endif
    //#endif
}

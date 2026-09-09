package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.mc.render.RenderUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix3x2fStack;
import org.joml.Matrix4f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font.DisplayMode;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Component;

/** Version-neutral GUI draw state shared by screens, HUD callbacks, and marker renderers. */
public final class GuiDraw {
    private final PoseStack matrices;
    private final GuiGraphicsExtractor context;
    private GuiDraw(final GuiGraphicsExtractor context) {
        this.context = context;
        RenderUtil.setGuiState(context.guiRenderState);
        this.matrices = new PoseStack();
        final var source = context.pose();
        this.matrices.last().pose()
            .m00(source.m00()).m01(source.m01())
            .m10(source.m10()).m11(source.m11())
            .m30(source.m20()).m31(source.m21());
    }
    public static GuiDraw of(final GuiGraphicsExtractor context) {
        return new GuiDraw(context);
    }

    public PoseStack matrices() {
        return matrices;
    }

    public void pushTransform() {
        matrices.pushPose();
    }

    public void translate(final float x, final float y) {
        matrices.translate(x, y, 0f);
    }

    public void scale(final float x, final float y) {
        matrices.scale(x, y, 1f);
    }

    public void popTransform() {
        matrices.popPose();
    }

    /** Draws the item's normal 16px GUI model, scaled and centered on one radar marker. */
    public void drawItemIcon(
        final Minecraft client,
        final ItemStack stack,
        final float centerX,
        final float centerY,
        final float size
    ) {
        final float scale = size / 16f;
        final float left = centerX - size / 2f;
        final float top = centerY - size / 2f;
        final Matrix3x2fStack pose = context.pose();
        pose.pushMatrix();
        try {
            pose.translate(left, top);
            pose.scale(scale, scale);
            context.item(stack, 0, 0);
        } finally {
            pose.popMatrix();
        }
    }

    public void renderBackground(
        final Screen screen,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        // 1.21.6 hoisted the background out of Screen.render into Screen.renderWithTooltip,
        // which already ran it before renderContents; drawing it again doubles applyBlur.
    }

    public void drawTooltip(
        final Screen screen,
        final Font renderer,
        final Component text,
        final int mouseX,
        final int mouseY
    ) {
        context.setTooltipForNextFrame(renderer, text, mouseX, mouseY);
    }

    public void fill(final int x1, final int y1, final int x2, final int y2, final int color) {
        context.fill(x1, y1, x2, y2, color);
    }
    public GuiGraphicsExtractor context() {
        return context;
    }
    /**
     * Runs {@code draw} with this mod's accumulated transform installed as the context's own 2D
     * pose, the glyph origin folded into it.
     *
     * <p>From 1.21.6 text is recorded rather than drawn, so it has to go through the context to
     * be layered against the rest of the GUI at all - and the context carries a 2D pose instead
     * of a MatrixStack, so a rotated or scaled caller (minimap markers, waypoint labels) would
     * otherwise lose its transform. Folding the origin into the pose also keeps the sub-pixel
     * placement that the context's integer coordinates drop.
     */
    private void withTextPose(final float x, final float y, final Runnable draw) {
        final Matrix3x2fStack pose = context.pose();
        pose.pushMatrix();
        try {
            final Matrix4f model = matrices.last().pose();
            pose.set(model.m00(), model.m01(), model.m10(), model.m11(), model.m30(), model.m31());
            pose.translate(x, y);
            draw.run();
        } finally {
            pose.popMatrix();
        }
    }

    public int drawTextWithShadow(
        final Font renderer,
        final String text,
        final float x,
        final float y,
        final int color
    ) {
        withTextPose(x, y, () -> context.text(renderer, text, 0, 0, color));
        return (int) x + renderer.width(text);
    }

    public int drawTextWithShadow(
        final Font renderer,
        final Component text,
        final float x,
        final float y,
        final int color
    ) {
        withTextPose(x, y, () -> context.text(renderer, text, 0, 0, color));
        return (int) x + renderer.width(text);
    }

    public int drawTextWithShadow(
        final Font renderer,
        final FormattedCharSequence text,
        final float x,
        final float y,
        final int color
    ) {
        withTextPose(x, y, () -> context.text(renderer, text, 0, 0, color));
        return (int) x + renderer.width(text);
    }
}

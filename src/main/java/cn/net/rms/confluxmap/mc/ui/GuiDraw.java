package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.mc.render.GuiShapes;
import cn.net.rms.confluxmap.mc.render.RenderTargetState;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.List;
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
    /**
     * Reveal alpha for the interface transitions, applied by {@link #beginReveal} around one
     * screen's render pass. The value lives in {@link GuiShapes} so the shape primitives
     * scale the same colors this class's own context-routed outlets do; 1 keeps every
     * outlet on its original fast path.
     */
    private static final float FULL_ALPHA = 1.0f;
    /** Untinted textured-draw tint; matching it keeps those draws on their fast path. */
    private static final int OPAQUE_TINT = 0xFFFFFFFF;

    private final PoseStack matrices;
    private final GuiGraphicsExtractor context;

    private GuiDraw(final GuiGraphicsExtractor context) {
        this.context = context;
        RenderTargetState.setGuiState(((cn.net.rms.confluxmap.mixin.GuiGraphicsExtractorAccessor) context).confluxmap$guiRenderState());
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
        // popTransform pops the context pose too, so pushPose its level here or the popPose runs
        // off the bottom of the JOML stack; the absolute fold keeps nesting idempotent.
        context.pose().pushMatrix();
        foldContextPose();
    }

    public void translate(final float x, final float y) {
        matrices.translate(x, y, 0f);
        // Re-fold: mid-scope transforms must reach context-routed draws too.
        foldContextPose();
    }

    public void scale(final float x, final float y) {
        matrices.scale(x, y, 1f);
        // Re-fold: mid-scope transforms must reach context-routed draws too.
        foldContextPose();
    }

    public void popTransform() {
        context.pose().popMatrix();
        matrices.popPose();
    }

    /**
     * Scales all subsequently drawn content around the screen center for one reveal frame;
     * pair with {@link #endScreenTransform()}. Explicit dimensions keep this testable and
     * usable from HUD callers too.
     */
    public void beginScreenTransform(
        final float scaleFactor,
        final float screenWidth,
        final float screenHeight
    ) {
        matrices.pushPose();
        translate(screenWidth / 2f, screenHeight / 2f);
        scale(scaleFactor, scaleFactor);
        translate(-screenWidth / 2f, -screenHeight / 2f);
    }

    public void endScreenTransform() {
        matrices.popPose();
    }

    /**
     * Installs this GuiDraw's transform as the context's own pose so draws that bypass this
     * class (vanilla's widget pass, {@link #fill} on 1.21.8+) still land transformed. No-op
     * below 1.21.8, where the context and this GuiDraw already share one PoseStack; on
     * 1.21.8+ this GuiDraw owns its stack, so the 2x3 pose is folded in exactly like
     * {@code withTextPose} does for text. Pair with {@link #restoreContextPose()}.
     */
    public void applyToContextPose() {
        context.pose().pushMatrix();
        foldContextPose();
    }

    public void restoreContextPose() {
        context.pose().popMatrix();
    }

    /** Overwrites the context pose with this stack's current 2D transform (absolute, not relative). */
    private void foldContextPose() {
        final Matrix3x2fStack pose = context.pose();
        final Matrix4f model = matrices.last().pose();
        pose.set(model.m00(), model.m01(), model.m10(), model.m11(), model.m30(), model.m31());
    }

    /**
     * Multiplies the alpha of every color this class and {@link GuiShapes} emit until
     * {@link #endReveal()}, so a screen reveal fades its own fills, text, and shape draws
     * alike. Item icons are exempt (their model render has no per-call color to scale).
     */
    public static void beginReveal(final float alpha) {
        GuiShapes.setRevealAlpha(Math.max(0.0f, Math.min(FULL_ALPHA, alpha)));
    }

    public static void endReveal() {
        GuiShapes.setRevealAlpha(FULL_ALPHA);
    }

    /**
     * Scales one caller-supplied color by the active reveal; context-routed outlets use this.
     * The scaled alpha never lands in vanilla Font's forced-opaque zone: 1.17.1's
     * {@code tweakTransparency} rewrites any color whose alpha top bits are zero back to fully
     * opaque, which would flash scoped text at full brightness exactly when its fade sits at
     * zero. The 4/255 floor stays invisible but keeps the rewrite from firing.
     */
    private static final int MIN_REVEALED_ALPHA = 4 << 24;

    private static int revealed(final int color) {
        if (GuiShapes.revealAlpha() == FULL_ALPHA) {
            return color;
        }
        final int scaled = Argb.scaleAlpha(color, GuiShapes.revealAlpha());
        return (scaled & 0xFC000000) == 0 ? scaled | MIN_REVEALED_ALPHA : scaled;
    }

    /**
     * Texture-backed counterpart to the color outlets: one axis-aligned textured quad carrying
     * an ARGB tint over the sampled texture. Texture draws that bypass this class's instance
     * methods (the minimap's map tiles) route through here so a fade scales them like every
     * fill and text, instead of special-casing the call sites. An opaque tint is exactly
     * {@link GuiShapes#drawQuad} - the pre-transition fast path.
     */
    public static void drawTexturedQuad(
        final PoseStack matrices,
        final float x,
        final float y,
        final float width,
        final float height,
        final float u0,
        final float v0,
        final float u1,
        final float v1,
        final int argbTint
    ) {
        if (argbTint == OPAQUE_TINT) {
            GuiShapes.drawQuad(matrices, x, y, width, height, u0, v0, u1, v1);
            return;
        }
        GuiShapes.drawTintedQuad(matrices, x, y, width, height, u0, v0, u1, v1, argbTint);
    }

    /**
     * Fills one axis-aligned rect in float coordinates through this GuiDraw's own transform,
     * without rounding. Unlike {@link #fill(int, int, int, int, int)} this carries transforms
     * pushed with {@link #pushTransform()} (the context's own fill would drop them on 1.21.8+),
     * at the cost of bypassing the shared vertex-consumer batching - pair it with
     * {@link #flushGui()} when earlier buffered fills must stay underneath.
     */
    public void fillRect(
        final float x1,
        final float y1,
        final float x2,
        final float y2,
        final int color
    ) {
        // GuiShapes applies the reveal alpha itself; pre-scaling here would double it.
        GuiShapes.fillRect(matrices, x1, y1, x2 - x1, y2 - y1, color);
    }

    /** Draws the item's normal 16px GUI model, scaled and centered on one marker position. */
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
            // Items are recorded with the context pose, which never sees this GuiDraw's own
            // stack; install it first (same fold as withTextPose).
            final Matrix4f model = matrices.last().pose();
            pose.set(model.m00(), model.m01(), model.m10(), model.m11(), model.m30(), model.m31());
            pose.translate(left, top);
            pose.scale(scale, scale);
            context.item(stack, 0, 0);
        } finally {
            pose.popMatrix();
        }
    }

    public void extractBackground(
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
        final List<FormattedCharSequence> lines = renderer.split(text, Math.max(200, screen.width / 2));
        context.setTooltipForNextFrame(lines, mouseX, mouseY);
    }

    public void fill(final int x1, final int y1, final int x2, final int y2, final int color) {
        context.fill(x1, y1, x2, y2, revealed(color));
    }

    /**
     * Fills one rect of a popup (dropdown backdrop, option row, selection stripe, its
     * scrollbar) that must fully cover earlier screen content.
     *
     * <p>The main glyph pass of any text drawn earlier in the frame writes depth at
     * {@code Font.FORWARD_SHIFT} (z + 0.03, nearer than z=0 fills) while sharing the
     * GUI layer's LEQUAL depth test, so a plain {@link #fill} is depth-rejected at those
     * pixels and the earlier text bleeds through the popup.
     */
    public void fillPopup(final int x1, final int y1, final int x2, final int y2, final int color) {
        // Overlap with an earlier element already starts a nearer recorded layer.
        context.fill(x1, y1, x2, y2, revealed(color));
    }

    /** No-op where fills already paint in call order; see the buffered-version variant. */
    public void flushGui() {
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
     * of a PoseStack, so a rotated or scaled caller (minimap markers, waypoint labels) would
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
        withTextPose(x, y, () -> context.text(renderer, text, 0, 0, revealed(color)));
        return (int) x + renderer.width(text);
    }

    public int drawTextWithShadow(
        final Font renderer,
        final Component text,
        final float x,
        final float y,
        final int color
    ) {
        withTextPose(x, y, () -> context.text(renderer, text, 0, 0, revealed(color)));
        return (int) x + renderer.width(text);
    }

    public int drawTextWithShadow(
        final Font renderer,
        final FormattedCharSequence text,
        final float x,
        final float y,
        final int color
    ) {
        withTextPose(x, y, () -> context.text(renderer, text, 0, 0, revealed(color)));
        return (int) x + renderer.width(text);
    }
}
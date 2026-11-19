package cn.net.rms.confluxmap.mc.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.Window;

/**
 * Tracks where this mod's GUI-space batches render: the overridden draw target, the active
 * scissor rectangle (framebuffer pixels plus, on 1.21.8+, the scaled GUI units the recorded
 * GUI pass clips in), and the GUI element list the game is currently collecting.
 * {@link OffscreenCanvas} retargets draws at its own framebuffer through
 * {@link #setDrawTarget}; {@link Mesh} reads the state back when it finishes a batch,
 * and on 26.3 resolves pipelines through {@link #compiled} because a render pass only
 * accepts pipelines the game's fallback cache has already compiled.
 */
public final class RenderTargetState {
    private static RenderTarget drawTarget;
    private static boolean scissorEnabled;
    private static int scissorX;
    private static int scissorY;
    private static int scissorWidth;
    private static int scissorHeight;
    private static GuiRenderState guiState;
    // The GUI renderer clips in scaled GUI units, the render pass in framebuffer pixels.
    private static int guiScissorX;
    private static int guiScissorY;
    private static int guiScissorWidth;
    private static int guiScissorHeight;

    private RenderTargetState() {
    }

    static RenderTarget drawTarget() {
        return drawTarget == null
            ? Minecraft.getInstance().gameRenderer.mainRenderTarget()
            : drawTarget;
    }
    
    static void setDrawTarget(final RenderTarget target) {
        drawTarget = target;
    }
    
    static void applyScissor(final RenderPass pass) {
        if (scissorEnabled) {
            pass.enableScissor(scissorX, scissorY, scissorWidth, scissorHeight);
        }
    }

    /**
     * Points GUI-space batches at the element list the game is currently collecting. Set once
     * per {@code GuiDraw}, which is the only way into this mod's screen and HUD drawing.
     */
    public static void setGuiState(final GuiRenderState state) {
        guiState = state;
    }
    
    /** Null while an {@link OffscreenCanvas} owns the draws - those target its own framebuffer. */
    static GuiRenderState guiState() {
        return drawTarget == null ? guiState : null;
    }
    
    static ScreenRectangle guiScissor() {
        return scissorEnabled
            ? new ScreenRectangle(guiScissorX, guiScissorY, guiScissorWidth, guiScissorHeight)
            : null;
    }

    /**
     * 26.3 pipelines must be compiled before a render pass accepts them; the game's
     * fallback cache owns the live compiler and is refreshed on every shader reload.
     */
    static RenderPipeline compiled(final RenderPipeline pipeline) {
        // 26.2's render pass takes the pipeline itself; the game compiles it on first use.
        return pipeline;
    }

    /**
     * Enables the GL scissor test for a rectangle given in GUI (scaled) coordinates,
     * converting to framebuffer pixels via the window's current scale factor.
     */
    public static void enableScissor(
        final Minecraft client,
        final int guiX,
        final int guiY,
        final int guiWidth,
        final int guiHeight
    ) {
        final Window window = client.getWindow();
        final double scale = window.getGuiScale();
        final int fbHeight = window.getHeight();
        final int x = (int) Math.round(guiX * scale);
        final int w = (int) Math.round(guiWidth * scale);
        final int h = (int) Math.round(guiHeight * scale);
        final int y = fbHeight - (int) Math.round((guiY + guiHeight) * scale);
        scissorEnabled = true;
        scissorX = x;
        scissorY = y;
        scissorWidth = w;
        scissorHeight = h;
        guiScissorX = guiX;
        guiScissorY = guiY;
        guiScissorWidth = guiWidth;
        guiScissorHeight = guiHeight;
    }

    /** Enables scissoring in the pixel coordinates of the currently bound off-screen target. */
    public static void enableTargetScissor(
        final int targetX,
        final int targetY,
        final int width,
        final int height,
        final int targetHeight
    ) {
        final int flippedY = targetScissorY(targetY, height, targetHeight);
        scissorEnabled = true;
        scissorX = targetX;
        scissorY = flippedY;
        scissorWidth = width;
        scissorHeight = height;
        guiScissorX = targetX;
        guiScissorY = targetY;
        guiScissorWidth = width;
        guiScissorHeight = height;
    }

    static int targetScissorY(final int targetY, final int height, final int targetHeight) {
        return targetHeight - targetY - height;
    }

    public static void disableScissor() {
        scissorEnabled = false;
    }
}
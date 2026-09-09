package cn.net.rms.confluxmap.mc.render;

import cn.net.rms.confluxmap.core.util.Argb;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.List;
import java.util.Optional;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.gui.render.TextureSetup;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.systems.RenderPass;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Core-shader helpers for drawing dynamically-generated textures (map tiles) as flat GUI quads.
 * Render thread only; every call here assumes a current GL context.
 *
 * <p>The version differences live in {@link Mesh} (batch setup/teardown) and in the extra
 * mappings for the {@code GameRenderer} shader accessors, so the geometry below is one shared
 * copy across every supported Minecraft version.
 */
public final class RenderUtil {
    private static final RenderPipeline GUI_PRESERVE_DESTINATION_ALPHA =
        createGuiPreserveDestinationAlphaPipeline();
    private static final RenderPipeline GUI_REPLACE = createGuiReplacePipeline();
    private static RenderTarget drawTarget;
    private static boolean scissorEnabled;
    private static int scissorX;
    private static int scissorY;
    private static int scissorWidth;
    private static int scissorHeight;
    private static GuiRenderState guiState;
    private static GpuTextureView boundTexture;
    // The GUI renderer clips in scaled GUI units, the render pass in framebuffer pixels.
    private static int guiScissorX;
    private static int guiScissorY;
    private static int guiScissorWidth;
    private static int guiScissorHeight;
    private static GpuSampler boundSampler;

    private RenderUtil() {
    }

    /** Selects the flat position+texture shader and standard alpha blending, for textured GUI quads. */
    public static void beginTexturedQuads() {
    }

    public static void bindTexture(final int glId) {
    }
    public static void bindTexture(final GpuTextureView texture) {
        bindTexture(
            texture,
            RenderSystem.getSamplerCache().getSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST,
                FilterMode.NEAREST,
                false
            )
        );
    }

    public static void bindTexture(final GpuTextureView texture, final GpuSampler sampler) {
        boundTexture = texture;
        boundSampler = sampler;
    }

    static GpuTextureView boundTexture() {
        return boundTexture;
    }

    static GpuSampler boundSampler() {
        return boundSampler;
    }
    static RenderTarget drawTarget() {
        return drawTarget == null ? Minecraft.getInstance().getMainRenderTarget() : drawTarget;
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

    static TextureSetup guiTextureSetup(final boolean textured) {
        if (!textured || boundTexture == null) {
            return TextureSetup.noTexture();
        }
        return TextureSetup.singleTexture(boundTexture, boundSampler);
    }

    public static void rotateZ(final PoseStack matrices, final float degrees) {
        matrices.mulPose(new Quaternionf().rotationZ((float) Math.toRadians(degrees)));
    }

    /** Saves the world ModelView and normalizes only the legacy LAST-event state. */
    public static void pushWorldHudModelView() {
        RenderSystem.getModelViewStack().pushMatrix();
    }

    /** Restores the global model-view saved by {@link #pushWorldHudModelView()}. */
    public static void popModelView() {
        RenderSystem.getModelViewStack().popMatrix();
    }
    /** Draws fully-lit marker text through the versioned text-layer argument. */
    public static void drawSeeThroughText(
        final Font textRenderer,
        final String text,
        final float x,
        final float y,
        final int color,
        final PoseStack matrices,
        final MultiBufferSource.BufferSource immediate,
        final int light
    ) {
        textRenderer.drawInBatch(
            text, x, y, color, false, matrices.last().pose(), immediate,
            Font.DisplayMode.SEE_THROUGH, 0, light
        );
    }

    /**
     * Binds an already-vanilla-managed texture (player skin, mob texture, etc.) by identifier.
     *
     * <p>Core shaders sample what {@code RenderSystem.setShaderTexture} points at, not the
     * legacy {@code TextureManager} bind - using the latter leaves unit 0 on whatever was
     * drawn last (map tiles), which is exactly the "icons show dark terrain" bug.
     */
    public static void bindTexture(final Minecraft client, final Identifier id) {
        final var texture = client.getTextureManager().getTexture(id);
        bindTexture(texture.getTextureView(), texture.getSampler());
    }

    /**
     * Draws one axis-aligned textured quad in GUI space. Must be called between
     * {@link #beginTexturedQuads()} and a bound texture ({@link #bindTexture(int)}).
     */
    public static void drawQuad(
        final PoseStack matrices,
        final float x,
        final float y,
        final float width,
        final float height,
        final float u0,
        final float v0,
        final float u1,
        final float v1
    ) {
        final var model = matrices.last().pose();
        final Mesh mesh = Mesh.beginGui(Mesh.Mode.QUADS, Mesh.tintedTextureFormat());
        mesh.tintedVertex(model, x, y + height, 0, u0, v1, 1f, 1f, 1f, 1f);
        mesh.tintedVertex(model, x + width, y + height, 0, u1, v1, 1f, 1f, 1f, 1f);
        mesh.tintedVertex(model, x + width, y, 0, u1, v0, 1f, 1f, 1f, 1f);
        mesh.tintedVertex(model, x, y, 0, u0, v0, 1f, 1f, 1f, 1f);
        mesh.drawGui(RenderPipelines.GUI_TEXTURED);
    }

    /**
     * Self-contained (sets its own shader/blend, unlike {@link #drawQuad} which expects
     * {@link #beginTexturedQuads()} to have been called by a tile-drawing loop) single-texture
     * quad with a per-vertex ARGB tint, multiplied over the sampled texture color. Used for radar
     * entity icons: alpha carries the above/below elevation fade, RGB carries the brightness dim,
     * matching the plain {@link #fillRect}/{@link #fillTriangle}/{@link #drawRing} markers'
     * per-call convention.
     */
    public static void drawTintedQuad(
        final PoseStack matrices,
        final float x,
        final float y,
        final float width,
        final float height,
        final float u0,
        final float v0,
        final float u1,
        final float v1,
        final int argbColor
    ) {
        final float a = Argb.alpha(argbColor) / 255f;
        final float r = Argb.red(argbColor) / 255f;
        final float g = Argb.green(argbColor) / 255f;
        final float b = Argb.blue(argbColor) / 255f;
        final var model = matrices.last().pose();
        final Mesh mesh = Mesh.beginGui(Mesh.Mode.QUADS, Mesh.tintedTextureFormat());
        mesh.tintedVertex(model, x, y + height, 0, u0, v1, r, g, b, a);
        mesh.tintedVertex(model, x + width, y + height, 0, u1, v1, r, g, b, a);
        mesh.tintedVertex(model, x + width, y, 0, u1, v0, r, g, b, a);
        mesh.tintedVertex(model, x, y, 0, u0, v0, r, g, b, a);
        mesh.drawGui(RenderPipelines.GUI_TEXTURED);
    }

    /**
     * Draws a dark texture-alpha silhouette expanded by {@code radius} GUI pixels. The textured
     * shader multiplies sampled RGB rather than replacing it, so this helper deliberately emits
     * black vertices and accepts opacity only. All shifted copies share one mesh submission;
     * drawing the original texture afterward covers the dark interior, leaving only the contour
     * around non-transparent pixels.
     */
    public static void drawDarkTextureOutline(
        final PoseStack matrices,
        final float x,
        final float y,
        final float width,
        final float height,
        final float u0,
        final float v0,
        final float u1,
        final float v1,
        final int radius,
        final float opacity
    ) {
        if (radius <= 0) {
            return;
        }
        final int diameter = radius * 2 + 1;
        final int copies = diameter * diameter - 1;
        // All shifted silhouettes can cover the same target pixel. Give each draw only the
        // per-layer alpha that converges on the requested fade after source-over accumulation.
        final float a = Argb.alphaForRepeatedOverdraw(opacity, copies);
        final var model = matrices.last().pose();
        final Mesh mesh = Mesh.beginGui(Mesh.Mode.QUADS, Mesh.tintedTextureFormat());
        for (int offsetY = -radius; offsetY <= radius; offsetY++) {
            for (int offsetX = -radius; offsetX <= radius; offsetX++) {
                if (offsetX == 0 && offsetY == 0) {
                    continue;
                }
                final float left = x + offsetX;
                final float top = y + offsetY;
                mesh.tintedVertex(model, left, top + height, 0, u0, v1, 0f, 0f, 0f, a);
                mesh.tintedVertex(model, left + width, top + height, 0, u1, v1, 0f, 0f, 0f, a);
                mesh.tintedVertex(model, left + width, top, 0, u1, v0, 0f, 0f, 0f, a);
                mesh.tintedVertex(model, left, top, 0, u0, v0, 0f, 0f, 0f, a);
            }
        }
        mesh.drawGui(RenderPipelines.GUI_TEXTURED);
    }

    /** Maps one horizontal texture strip clockwise around a circular frame. */
    public static void drawTexturedRing(
        final PoseStack matrices,
        final float centerX,
        final float centerY,
        final float outerRadius,
        final float thickness,
        final float u0,
        final float v0,
        final float u1,
        final float v1,
        final int argbColor
    ) {
        final int segments = Math.max(32, (int) Math.ceil(outerRadius * Math.PI / 2));
        final float innerRadius = Math.max(0f, outerRadius - thickness);
        final float a = Argb.alpha(argbColor) / 255f;
        final float r = Argb.red(argbColor) / 255f;
        final float g = Argb.green(argbColor) / 255f;
        final float b = Argb.blue(argbColor) / 255f;
        final var model = matrices.last().pose();
        final Mesh mesh = Mesh.beginGui(Mesh.Mode.QUADS, Mesh.tintedTextureFormat());
        for (int i = 0; i < segments; i++) {
            final float p0 = i / (float) segments;
            final float p1 = (i + 1) / (float) segments;
            final double angle0 = -Math.PI / 2.0 + p0 * Math.PI * 2.0;
            final double angle1 = -Math.PI / 2.0 + p1 * Math.PI * 2.0;
            final float sin0 = (float) Math.sin(angle0);
            final float cos0 = (float) Math.cos(angle0);
            final float sin1 = (float) Math.sin(angle1);
            final float cos1 = (float) Math.cos(angle1);
            final float texU0 = u0 + (u1 - u0) * p0;
            final float texU1 = u0 + (u1 - u0) * p1;

            texturedVertex(
                mesh, model,
                centerX + cos0 * innerRadius, centerY + sin0 * innerRadius,
                texU0, v1, r, g, b, a
            );
            texturedVertex(
                mesh, model,
                centerX + cos1 * innerRadius, centerY + sin1 * innerRadius,
                texU1, v1, r, g, b, a
            );
            texturedVertex(
                mesh, model,
                centerX + cos1 * outerRadius, centerY + sin1 * outerRadius,
                texU1, v0, r, g, b, a
            );
            texturedVertex(
                mesh, model,
                centerX + cos0 * outerRadius, centerY + sin0 * outerRadius,
                texU0, v0, r, g, b, a
            );
        }
        drawGuiTexturedMesh(mesh);
    }

    private static void texturedVertex(
        final Mesh mesh,
        final org.joml.Matrix4f model,
        final float x,
        final float y,
        final float u,
        final float v,
        final float r,
        final float g,
        final float b,
        final float a
    ) {
        mesh.tintedVertex(model, x, y, 0, u, v, r, g, b, a);
    }

    private static void drawGuiTexturedMesh(final Mesh mesh) {
        mesh.drawGui(RenderPipelines.GUI_TEXTURED);
    }

    /**
     * Draws already-projected textured model quads into the current target. The array uses
     * {@code x,y,z,u,v} per vertex and must contain complete groups of four vertices.
     */
    public static void drawProjectedTexturedQuads(
        final PoseStack matrices,
        final float[] vertices,
        final int argbColor
    ) {
        if (vertices.length == 0) {
            return;
        }
        if (vertices.length % 20 != 0) {
            throw new IllegalArgumentException("projected textured quads require 20 floats per quad");
        }
        final float a = Argb.alpha(argbColor) / 255f;
        final float r = Argb.red(argbColor) / 255f;
        final float g = Argb.green(argbColor) / 255f;
        final float b = Argb.blue(argbColor) / 255f;
        final var model = matrices.last().pose();
        final Mesh mesh = Mesh.beginGui(Mesh.Mode.QUADS, Mesh.tintedTextureFormat());
        for (int i = 0; i < vertices.length; i += 5) {
            mesh.tintedVertex(
                model, vertices[i], vertices[i + 1], vertices[i + 2],
                vertices[i + 3], vertices[i + 4], r, g, b, a
            );
        }
        mesh.drawGui(RenderPipelines.GUI_TEXTURED);
    }

    /** Replaces one rectangle in the current render target with fully transparent pixels. */
    public static void clearTargetRect(
        final PoseStack matrices,
        final float x,
        final float y,
        final float width,
        final float height
    ) {
        final var model = matrices.last().pose();
        final Mesh mesh = Mesh.beginGui(Mesh.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        appendRect(mesh, model, x, y, width, height, 0);
        mesh.drawGui(GUI_REPLACE);
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
        final int scissorY = targetScissorY(targetY, height, targetHeight);
        scissorEnabled = true;
        scissorX = targetX;
        RenderUtil.scissorY = scissorY;
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

    /** Flat-colored filled triangle in GUI space (player arrow etc.). */
    public static void fillTriangle(
        final PoseStack matrices,
        final float x0, final float y0,
        final float x1, final float y1,
        final float x2, final float y2,
        final int argbColor
    ) {
        final float a = Argb.alpha(argbColor) / 255f;
        final float r = Argb.red(argbColor) / 255f;
        final float g = Argb.green(argbColor) / 255f;
        final float b = Argb.blue(argbColor) / 255f;
        final var model = matrices.last().pose();
        final Mesh mesh = Mesh.beginGui(Mesh.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        mesh.vertex(model, x0, y0, 0).color(r, g, b, a).next();
        mesh.vertex(model, x1, y1, 0).color(r, g, b, a).next();
        mesh.vertex(model, x2, y2, 0).color(r, g, b, a).next();
        mesh.vertex(model, x2, y2, 0).color(r, g, b, a).next();
        // Callers pass either winding (compare the two halves of a StructureMarkerRenderer
        // diamond), and 1.21.5 pipelines cull back faces. Emitting both windings of a flat
        // triangle costs nothing at raster time: exactly one of them survives the cull.
        mesh.vertex(model, x2, y2, 0).color(r, g, b, a).next();
        mesh.vertex(model, x1, y1, 0).color(r, g, b, a).next();
        mesh.vertex(model, x0, y0, 0).color(r, g, b, a).next();
        mesh.vertex(model, x0, y0, 0).color(r, g, b, a).next();
        mesh.drawGui(RenderPipelines.GUI);
    }

    /**
     * Small pixel-style diamond with a top-left highlight, lower-right shading, and a restrained
     * drop shadow. Every layer is appended to one mesh so dense radar views still issue one draw.
     */
    public static void fillBeveledDiamond(
        final PoseStack matrices,
        final float centerX,
        final float centerY,
        final float radius,
        final int argbColor
    ) {
        final var model = matrices.last().pose();
        final Mesh mesh = Mesh.beginGui(Mesh.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        final int dropShadow = Argb.scaleAlpha(
            0xA0000000, Argb.alpha(argbColor) / 255f
        );
        appendDiamond(
            mesh, model, centerX + 0.5f, centerY + 0.5f,
            Math.max(0f, radius - 0.25f), dropShadow
        );
        final int topLeft = Argb.blendOver(argbColor, 0x70FFFFFF);
        final int topRight = Argb.blendOver(argbColor, 0x28FFFFFF);
        final int bottomRight = Argb.scale(argbColor, 0.5f);
        final int bottomLeft = Argb.scale(argbColor, 0.72f);
        appendTriangleQuad(
            mesh, model,
            centerX, centerY - radius,
            centerX - radius, centerY,
            centerX, centerY,
            topLeft
        );
        appendTriangleQuad(
            mesh, model,
            centerX, centerY - radius,
            centerX, centerY,
            centerX + radius, centerY,
            topRight
        );
        appendTriangleQuad(
            mesh, model,
            centerX, centerY + radius,
            centerX + radius, centerY,
            centerX, centerY,
            bottomRight
        );
        appendTriangleQuad(
            mesh, model,
            centerX, centerY + radius,
            centerX, centerY,
            centerX - radius, centerY,
            bottomLeft
        );
        mesh.drawGui(RenderPipelines.GUI);
    }

    private static void appendDiamond(
        final Mesh mesh,
        final Matrix4f model,
        final float centerX,
        final float centerY,
        final float radius,
        final int argbColor
    ) {
        appendColoredVertex(mesh, model, centerX, centerY + radius, argbColor);
        appendColoredVertex(mesh, model, centerX + radius, centerY, argbColor);
        appendColoredVertex(mesh, model, centerX, centerY - radius, argbColor);
        appendColoredVertex(mesh, model, centerX - radius, centerY, argbColor);
    }

    private static void appendTriangleQuad(
        final Mesh mesh,
        final Matrix4f model,
        final float x0,
        final float y0,
        final float x1,
        final float y1,
        final float x2,
        final float y2,
        final int argbColor
    ) {
        appendColoredVertex(mesh, model, x0, y0, argbColor);
        appendColoredVertex(mesh, model, x1, y1, argbColor);
        appendColoredVertex(mesh, model, x2, y2, argbColor);
        appendColoredVertex(mesh, model, x2, y2, argbColor);
    }

    private static void appendColoredVertex(
        final Mesh mesh,
        final Matrix4f model,
        final float x,
        final float y,
        final int argbColor
    ) {
        mesh.vertex(model, x, y, 0).color(
            Argb.red(argbColor) / 255f,
            Argb.green(argbColor) / 255f,
            Argb.blue(argbColor) / 255f,
            Argb.alpha(argbColor) / 255f
        ).next();
    }

    /**
     * Textured disk sampling an {@link OffscreenCanvas}: rim UVs walk the unit circle
     * around (0.5, 0.5), V flipped because FBO row 0 is the bottom. The currently bound
     * texture must be the canvas contents; call between {@link #beginTexturedQuads()} and
     * a bound texture.
     */
    public static void drawTexturedDisk(
        final PoseStack matrices,
        final float centerX,
        final float centerY,
        final float radius
    ) {
        final var model = matrices.last().pose();
        final Mesh mesh = Mesh.beginGui(Mesh.Mode.QUADS, Mesh.tintedTextureFormat());
        final int segments = 48;
        for (int i = 0; i < segments; i++) {
            final double angle0 = 2.0 * Math.PI * i / segments;
            final double angle1 = 2.0 * Math.PI * (i + 1) / segments;
            final float cos0 = (float) Math.cos(angle0);
            final float sin0 = (float) Math.sin(angle0);
            final float cos1 = (float) Math.cos(angle1);
            final float sin1 = (float) Math.sin(angle1);
            // Wound backwards through the segment so the fan matches the front-facing
            // order of fillRect; the legacy path called disableCull here instead.
            mesh.tintedVertex(model, centerX, centerY, 0, 0.5f, 0.5f, 1f, 1f, 1f, 1f);
            mesh.tintedVertex(
                model, centerX + cos1 * radius, centerY + sin1 * radius, 0,
                0.5f + 0.5f * cos1, 0.5f - 0.5f * sin1, 1f, 1f, 1f, 1f
            );
            mesh.tintedVertex(
                model, centerX + cos0 * radius, centerY + sin0 * radius, 0,
                0.5f + 0.5f * cos0, 0.5f - 0.5f * sin0, 1f, 1f, 1f, 1f
            );
            mesh.tintedVertex(model, centerX, centerY, 0, 0.5f, 0.5f, 1f, 1f, 1f, 1f);
        }
        mesh.drawGui(RenderPipelines.GUI_TEXTURED);
    }

    /** Ring outline (circle border), as a triangle strip pre-1.21.5 and segment quads after. */
    public static void drawRing(
        final PoseStack matrices,
        final float centerX,
        final float centerY,
        final float outerRadius,
        final float thickness,
        final int argbColor
    ) {
        final float a = Argb.alpha(argbColor) / 255f;
        final float r = Argb.red(argbColor) / 255f;
        final float g = Argb.green(argbColor) / 255f;
        final float b = Argb.blue(argbColor) / 255f;
        final var model = matrices.last().pose();
        final Mesh mesh = Mesh.beginGui(Mesh.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        final int segments = 48;
        final float inner = outerRadius - thickness;
        for (int i = 0; i < segments; i++) {
            final double angle0 = 2.0 * Math.PI * i / segments;
            final double angle1 = 2.0 * Math.PI * (i + 1) / segments;
            final float cos0 = (float) Math.cos(angle0);
            final float sin0 = (float) Math.sin(angle0);
            final float cos1 = (float) Math.cos(angle1);
            final float sin1 = (float) Math.sin(angle1);
            // Inner edge first, so each segment quad winds like fillRect and survives the
            // back-face cull that 1.21.5 pipelines apply to GUI geometry.
            mesh.vertex(model, centerX + cos0 * inner, centerY + sin0 * inner, 0).color(r, g, b, a).next();
            mesh.vertex(model, centerX + cos1 * inner, centerY + sin1 * inner, 0).color(r, g, b, a).next();
            mesh.vertex(model, centerX + cos1 * outerRadius, centerY + sin1 * outerRadius, 0).color(r, g, b, a).next();
            mesh.vertex(model, centerX + cos0 * outerRadius, centerY + sin0 * outerRadius, 0).color(r, g, b, a).next();
        }
        mesh.drawGui(RenderPipelines.GUI);
    }

    /**
     * Selects the flat position+color shader and additive-ish blending
     * ({@code src*alpha + dst*1}), for glow-style translucent 3D geometry like waypoint
     * beams (see {@code mc.ui.world.WaypointWorldRenderer}). Unlike {@link #fillTriangle}
     * and {@link #fillRect}, this does not reset itself on every draw call - callers issue
     * this once, draw as many {@link #fillTriangle3D} calls as needed, then restore normal
     * blending with {@link #restoreDefaultBlend()} when done.
     */
    public static void beginAdditiveTriangles() {
    }

    /** Restores standard alpha blending after {@link #beginAdditiveTriangles()}. */
    public static void restoreDefaultBlend() {
    }

    /**
     * Flat-colored filled triangle with three independent coordinates, for true 3D
     * world-space geometry drawn from a {@code WorldRenderEvents} callback (unlike
     * {@link #fillTriangle}, which always draws on the local matrix's Z=0 plane for flat
     * GUI shapes). Assumes the shader and blend state are already set up by the caller -
     * see {@link #beginAdditiveTriangles()}.
     */
    public static void fillTriangle3D(
        final PoseStack matrices,
        final float x0, final float y0, final float z0,
        final float x1, final float y1, final float z1,
        final float x2, final float y2, final float z2,
        final int argbColor
    ) {
        final float a = Argb.alpha(argbColor) / 255f;
        final float r = Argb.red(argbColor) / 255f;
        final float g = Argb.green(argbColor) / 255f;
        final float b = Argb.blue(argbColor) / 255f;
        final var model = matrices.last().pose();
        final Mesh mesh = Mesh.begin(Mesh.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        mesh.vertex(model, x0, y0, z0).color(r, g, b, a).next();
        mesh.vertex(model, x1, y1, z1).color(r, g, b, a).next();
        mesh.vertex(model, x2, y2, z2).color(r, g, b, a).next();
        mesh.vertex(model, x2, y2, z2).color(r, g, b, a).next();
        mesh.vertex(model, x1, y1, z1).color(r, g, b, a).next();
        mesh.vertex(model, x0, y0, z0).color(r, g, b, a).next();
        mesh.draw(RenderPipelines.DRAGON_RAYS);
    }

    /** Flat-colored axis-aligned quad (background/border), independent of any bound texture. */
    public static void fillRect(final PoseStack matrices, final float x, final float y, final float width, final float height, final int argbColor) {
        fillRect(matrices, x, y, width, height, argbColor, true);
    }

    /** Draws many flat GUI rectangles in one batch. */
    public static void fillRects(final PoseStack matrices, final List<ColoredRect> rects) {
        fillRects(matrices, rects, false);
    }

    /** Draws translucent GUI rectangles without replacing the target's existing alpha. */
    public static void fillRectsPreservingDestinationAlpha(
        final PoseStack matrices,
        final List<ColoredRect> rects
    ) {
        fillRects(matrices, rects, true);
    }

    private static void fillRects(
        final PoseStack matrices,
        final List<ColoredRect> rects,
        final boolean preserveDestinationAlpha
    ) {
        if (rects.isEmpty()) {
            return;
        }
        final var model = matrices.last().pose();
        final Mesh mesh = Mesh.beginGui(Mesh.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (final ColoredRect rect : rects) {
            appendRect(mesh, model, rect.x(), rect.y(), rect.width(), rect.height(), rect.argbColor());
        }
        mesh.drawGui(
            preserveDestinationAlpha ? GUI_PRESERVE_DESTINATION_ALPHA : RenderPipelines.GUI
        );
    }
    private static RenderPipeline createGuiPreserveDestinationAlphaPipeline() {
        final RenderPipeline gui = RenderPipelines.GUI;
        final RenderPipeline.Builder builder = RenderPipeline.builder()
            .withLocation("pipeline/confluxmap_gui_preserve_destination_alpha")
            .withVertexShader(gui.getVertexShader())
            .withFragmentShader(gui.getFragmentShader())
            .withVertexFormat(gui.getVertexFormat(), gui.getVertexFormatMode());
        builder
            .withCull(gui.isCull())
            .withColorTargetState(new ColorTargetState(
                gui.getColorTargetState().blendFunction(),
                ColorTargetState.WRITE_COLOR
            ));
        builder
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER);
        return builder.build();
    }

    private static RenderPipeline createGuiReplacePipeline() {
        final RenderPipeline gui = RenderPipelines.GUI;
        final RenderPipeline.Builder builder = RenderPipeline.builder()
            .withLocation("pipeline/confluxmap_gui_replace")
            .withVertexShader(gui.getVertexShader())
            .withFragmentShader(gui.getFragmentShader())
            .withVertexFormat(gui.getVertexFormat(), gui.getVertexFormatMode());
        builder
            .withCull(gui.isCull())
            .withColorTargetState(new ColorTargetState(Optional.empty(), ColorTargetState.WRITE_ALL));
        builder
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER);
        return builder.build();
    }

    /**
     * The same quad as {@link #fillRect}, drawn as world geometry from a {@code WorldRenderEvents}
     * callback (waypoint label plates) rather than as part of the GUI. The distinction only
     * matters from 1.21.6, where GUI drawing is recorded for a later pass and world drawing is not
     * - see {@link Mesh#beginGui}.
     */
    public static void fillRect3D(final PoseStack matrices, final float x, final float y, final float width, final float height, final int argbColor) {
        fillRect(matrices, x, y, width, height, argbColor, false);
    }

    private static void fillRect(
        final PoseStack matrices,
        final float x,
        final float y,
        final float width,
        final float height,
        final int argbColor,
        final boolean gui
    ) {
        final var model = matrices.last().pose();
        final Mesh mesh = gui
            ? Mesh.beginGui(Mesh.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
            : Mesh.begin(Mesh.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        appendRect(mesh, model, x, y, width, height, argbColor);
        if (gui) {
            mesh.drawGui(RenderPipelines.GUI);
        } else {
            mesh.draw(RenderPipelines.GUI);
        }
    }

    private static void appendRect(
        final Mesh mesh,
        final Matrix4f model,
        final float x,
        final float y,
        final float width,
        final float height,
        final int argbColor
    ) {
        final float a = Argb.alpha(argbColor) / 255f;
        final float r = Argb.red(argbColor) / 255f;
        final float g = Argb.green(argbColor) / 255f;
        final float b = Argb.blue(argbColor) / 255f;
        mesh.vertex(model, x, y + height, 0).color(r, g, b, a).next();
        mesh.vertex(model, x + width, y + height, 0).color(r, g, b, a).next();
        mesh.vertex(model, x + width, y, 0).color(r, g, b, a).next();
        mesh.vertex(model, x, y, 0).color(r, g, b, a).next();
    }

    public record ColoredRect(float x, float y, float width, float height, int argbColor) {
    }

    /*
     * 1.20 renamed the core shader accessors and changed their return type (Shader ->
     * ShaderProgram), so these three cannot be expressed as extra mappings and fork here instead.
     */

    private static void useTextureShader() {
        // Pipeline selection happens at draw time.
    }

    private static void useColorShader() {
        // Pipeline selection happens at draw time.
    }

    private static void useTintedTextureShader() {
        // Pipeline selection happens at draw time.
    }
}

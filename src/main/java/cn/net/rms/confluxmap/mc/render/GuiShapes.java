package cn.net.rms.confluxmap.mc.render;

import cn.net.rms.confluxmap.core.util.Argb;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.List;
import java.util.Optional;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Flat GUI and world-space shape emission shared by the screens, HUD, radar, and world
 * overlay passes. Render thread only; every call here assumes a current GL context. The
 * version differences live in {@link Mesh} (batch setup/teardown) and in the extra
 * mappings for the {@code GameRenderer} shader accessors, so the geometry is one shared
 * copy across every supported Minecraft version.
 */
public final class GuiShapes {
    private static final RenderPipeline GUI_PRESERVE_DESTINATION_ALPHA =
        createGuiPreserveDestinationAlphaPipeline();
    private static final RenderPipeline GUI_REPLACE = createGuiReplacePipeline();

    /**
     * Alpha multiplier of the interface transitions' reveal frames, applied to every color
     * emitted here so a fading screen scales its fills, tints, and textures alike. Set only
     * through {@code GuiDraw.beginReveal}/{@code endReveal}, which pair it in a try/finally;
     * 1 keeps every color on the unchanged fast path. Render thread only.
     */
    private static float revealAlpha = 1.0f;

    public static void setRevealAlpha(final float alpha) {
        revealAlpha = Math.max(0.0f, Math.min(1.0f, alpha));
    }

    public static float revealAlpha() {
        return revealAlpha;
    }

    private static int reveal(final int argbColor) {
        return revealAlpha == 1.0f ? argbColor : Argb.scaleAlpha(argbColor, revealAlpha);
    }


    public static void rotateZ(final PoseStack matrices, final float degrees) {
        matrices.mulPose(new Quaternionf().rotationZ((float) Math.toRadians(degrees)));
    }

    /**
     * Draws one axis-aligned textured quad in GUI space. Must be called between
     * {@link TextureBinds#beginTexturedQuads()} and a bound texture ({@link TextureBinds#bindTexture(int)}).
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
        if (revealAlpha != 1.0f) {
            // POSITION_TEXTURE carries no per-vertex color, so a reveal frame reroutes
            // through the tinted twin instead of touching global shader color state.
            drawTintedQuad(
                matrices, x, y, width, height, u0, v0, u1, v1,
                Argb.scaleAlpha(0xFFFFFFFF, revealAlpha)
            );
            return;
        }
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
     * {@link TextureBinds#beginTexturedQuads()} to have been called by a tile-drawing loop) single-texture
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
        final int revealedColor = reveal(argbColor);
        final float a = Argb.alpha(revealedColor) / 255f;
        final float r = Argb.red(revealedColor) / 255f;
        final float g = Argb.green(revealedColor) / 255f;
        final float b = Argb.blue(revealedColor) / 255f;
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
        final float a = Argb.alphaForRepeatedOverdraw(opacity * revealAlpha, copies);
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
        final int revealedColor = reveal(argbColor);
        final float a = Argb.alpha(revealedColor) / 255f;
        final float r = Argb.red(revealedColor) / 255f;
        final float g = Argb.green(revealedColor) / 255f;
        final float b = Argb.blue(revealedColor) / 255f;
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
        final int revealedColor = reveal(argbColor);
        final float a = Argb.alpha(revealedColor) / 255f;
        final float r = Argb.red(revealedColor) / 255f;
        final float g = Argb.green(revealedColor) / 255f;
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

    /** Flat-colored filled triangle in GUI space (player arrow etc.). */
    public static void fillTriangle(
        final PoseStack matrices,
        final float x0, final float y0,
        final float x1, final float y1,
        final float x2, final float y2,
        final int argbColor
    ) {
        final int revealedColor = reveal(argbColor);
        final float a = Argb.alpha(revealedColor) / 255f;
        final float r = Argb.red(revealedColor) / 255f;
        final float g = Argb.green(revealedColor) / 255f;
        final float b = Argb.blue(revealedColor) / 255f;
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
        final int revealedColor = reveal(argbColor);
        final int dropShadow = Argb.scaleAlpha(
            0xA0000000, Argb.alpha(revealedColor) / 255f
        );
        appendDiamond(
            mesh, model, centerX + 0.5f, centerY + 0.5f,
            Math.max(0f, radius - 0.25f), dropShadow
        );
        final int topLeft = Argb.blendOver(revealedColor, 0x70FFFFFF);
        final int topRight = Argb.blendOver(revealedColor, 0x28FFFFFF);
        final int bottomRight = Argb.scale(revealedColor, 0.5f);
        final int bottomLeft = Argb.scale(revealedColor, 0.72f);
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
     * texture must be the canvas contents; call between {@link TextureBinds#beginTexturedQuads()} and
     * a bound texture.
     */
    public static void drawTexturedDisk(
        final PoseStack matrices,
        final float centerX,
        final float centerY,
        final float radius
    ) {
        if (revealAlpha != 1.0f) {
            // The untinted fan has no per-vertex color to scale; reroute through the
            // tinted twin so a reveal frame fades the composite.
            drawTexturedDisk(
                matrices, centerX, centerY, radius,
                Argb.scaleAlpha(0xFFFFFFFF, revealAlpha)
            );
            return;
        }
        drawTexturedDisk(matrices, centerX, centerY, radius, 0xFFFFFFFF);
    }

    /**
     * Tinted twin of the disk fan: an ARGB tint multiplied over the sampled canvas. The
     * minimap HUD passes its fade alpha; a reveal frame scales the tint here.
     */
    public static void drawTexturedDisk(
        final PoseStack matrices,
        final float centerX,
        final float centerY,
        final float radius,
        final int argbTint
    ) {
        final int revealedTint = reveal(argbTint);
        final boolean opaque = revealedTint == 0xFFFFFFFF;
        final float a = Argb.alpha(revealedTint) / 255f;
        final float r = Argb.red(revealedTint) / 255f;
        final float g = Argb.green(revealedTint) / 255f;
        final float b = Argb.blue(revealedTint) / 255f;
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
            if (opaque) {
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
            } else {
                mesh.tintedVertex(model, centerX, centerY, 0, 0.5f, 0.5f, r, g, b, a);
                mesh.tintedVertex(
                    model, centerX + cos1 * radius, centerY + sin1 * radius, 0,
                    0.5f + 0.5f * cos1, 0.5f - 0.5f * sin1, r, g, b, a
                );
                mesh.tintedVertex(
                    model, centerX + cos0 * radius, centerY + sin0 * radius, 0,
                    0.5f + 0.5f * cos0, 0.5f - 0.5f * sin0, r, g, b, a
                );
                mesh.tintedVertex(model, centerX, centerY, 0, 0.5f, 0.5f, r, g, b, a);
            }
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
        final int revealedColor = reveal(argbColor);
        final float a = Argb.alpha(revealedColor) / 255f;
        final float r = Argb.red(revealedColor) / 255f;
        final float g = Argb.green(revealedColor) / 255f;
        final float b = Argb.blue(revealedColor) / 255f;
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
            appendRect(
                mesh, model, rect.x(), rect.y(), rect.width(), rect.height(),
                reveal(rect.argbColor())
            );
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
        appendRect(mesh, model, x, y, width, height, reveal(argbColor));
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

    public static void useTextureShader() {
        // Pipeline selection happens at draw time.
    }

    public static void useColorShader() {
        // Pipeline selection happens at draw time.
    }

    private static void useTintedTextureShader() {
        // Pipeline selection happens at draw time.
    }
}
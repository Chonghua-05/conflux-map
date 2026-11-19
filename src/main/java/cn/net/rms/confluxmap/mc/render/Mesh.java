package cn.net.rms.confluxmap.mc.render;

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.StagedVertexBuffer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.render.TextureSetup;
import java.util.Arrays;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * One immediate-mode draw call, hiding how this Minecraft version starts and finishes a
 * tessellator batch.
 *
 * <p>1.21 rewrote the entry and exit of the batch - {@code Tesselator.getBuffer()} plus
 * {@code buffer.begin(..)} became {@code Tesselator.begin(..)}, per-vertex {@code next()}
 * disappeared, and {@code Tesselator.draw()} became
 * {@code BufferRenderer.drawWithGlobalProgram(buffer.end())} - while the vertex emission in
 * between stayed the same. Wrapping only the three changed points keeps every caller's geometry
 * as a single shared copy instead of one per version.
 *
 * <p>Render thread only, and not reentrant: the tessellator is a singleton, so a batch must be
 * {@link #draw() drawn} before the next one begins. A captured batch (see {@link #beginGui}) owns
 * its vertices instead of the tessellator's and is exempt from that.
 */
public final class Mesh {
    public enum Mode {
        TRIANGLES,
        TRIANGLE_STRIP,
        TRIANGLE_FAN,
        QUADS
    }

    private static final StagedVertexBuffer IMMEDIATE =
        new StagedVertexBuffer(() -> "Conflux Map immediate geometry", 1 << 20);

    /** Captured vertex layout: x, y, z, u, v, r, g, b, a. */
    private static final int STRIDE = 9;

    private final VertexConsumer buffer;
    private final StagedVertexBuffer.Draw stagedDraw;
    private final GuiRenderState guiState;
    private final boolean textured;
    private final float[] pending;
    private float[] vertices;
    private int vertexCount;

    private Mesh(final VertexConsumer buffer, final StagedVertexBuffer.Draw stagedDraw) {
        this.buffer = buffer;
        this.stagedDraw = stagedDraw;
        this.guiState = null;
        this.textured = false;
        this.pending = null;
    }

    private Mesh(final GuiRenderState guiState, final boolean textured) {
        this.buffer = null;
        this.stagedDraw = null;
        this.guiState = guiState;
        this.textured = textured;
        this.pending = new float[STRIDE];
        this.vertices = new float[STRIDE * 16];
    }

    /** Starts a batch in {@code format}; finish it with {@link #draw()}. */
    public static Mesh begin(final Mode mode, final VertexFormat format) {
        final StagedVertexBuffer.Draw draw = IMMEDIATE.appendDraw(format, topology(mode));
        return new Mesh(IMMEDIATE.getVertexBuilder(draw), draw);
    }

    private static PrimitiveTopology topology(final Mode mode) {
        return switch (mode) {
            case TRIANGLES -> PrimitiveTopology.TRIANGLES;
            case TRIANGLE_STRIP -> PrimitiveTopology.TRIANGLE_STRIP;
            case TRIANGLE_FAN -> PrimitiveTopology.TRIANGLE_FAN;
            case QUADS -> PrimitiveTopology.QUADS;
        };
    }

    /**
     * Starts a batch of flat GUI-space geometry; finish it with {@code drawGui}.
     *
     * <p>1.21.6 turned the GUI retained-mode: a screen or HUD callback only records elements into
     * a {@code GuiRenderState}, and the renderer replays the whole list afterwards under its own
     * projection. A batch that draws itself on the spot there paints with the world pass' matrices
     * and is then covered by everything vanilla recorded, so from that version the vertices are
     * captured and handed to the state instead. Older versions - and canvas batches, which target
     * {@link OffscreenCanvas}' own framebuffer rather than the screen - still draw immediately.
     */
    public static Mesh beginGui(final Mode mode, final VertexFormat format) {
        final GuiRenderState guiState = RenderTargetState.guiState();
        if (guiState != null) {
            return new Mesh(guiState, format == tintedTextureFormat());
        }
        return begin(mode, format);
    }

    /**
     * The vertex format for a textured quad carrying a per-vertex tint. 1.20.5 reordered the
     * elements (and renamed the constant accordingly), so callers must emit texture and colour
     * through {@link #tintedVertex} rather than ordering the calls themselves.
     */
    public static VertexFormat tintedTextureFormat() {
        return DefaultVertexFormat.POSITION_TEX_COLOR;
    }

    /** Position-only vertex, for formats whose remaining elements the caller adds next. */
    public Mesh vertex(final Matrix4f model, final float x, final float y, final float z) {
        if (buffer == null) {
            // A captured batch is replayed without a matrix, so the transform is applied here.
            final Vector3f position = model.transformPosition(x, y, z, new Vector3f());
            pending[0] = position.x;
            pending[1] = position.y;
            pending[2] = position.z;
            return this;
        }
        buffer.addVertex(model, x, y, z);
        return this;
    }

    public Mesh texture(final float u, final float v) {
        if (buffer == null) {
            pending[3] = u;
            pending[4] = v;
            return this;
        }
        buffer.setUv(u, v);
        return this;
    }

    public Mesh color(final float r, final float g, final float b, final float a) {
        if (buffer == null) {
            pending[5] = r;
            pending[6] = g;
            pending[7] = b;
            pending[8] = a;
            return this;
        }
        buffer.setColor(r, g, b, a);
        return this;
    }

    /** A complete vertex in {@link #tintedTextureFormat()}, emitted in this version's element order. */
    public Mesh tintedVertex(
        final Matrix4f model,
        final float x, final float y, final float z,
        final float u, final float v,
        final float r, final float g, final float b, final float a
    ) {
        vertex(model, x, y, z);
        texture(u, v);
        color(r, g, b, a);
        return next();
    }

    /** Ends the vertex under construction. A no-op where the buffer commits vertices itself. */
    public Mesh next() {
        if (buffer == null) {
            if (vertexCount * STRIDE == vertices.length) {
                vertices = Arrays.copyOf(vertices, vertices.length * 2);
            }
            System.arraycopy(pending, 0, vertices, vertexCount * STRIDE, STRIDE);
            vertexCount++;
            return this;
        }
        return this;
    }

    /** Uploads and draws the batch. */
    public void draw() {
        throw new IllegalStateException("1.21.5+ draws require an explicit render pipeline");
    }

    /** Finishes a {@link #beginGui} batch: recorded for the GUI renderer, or drawn on the spot. */
    public void drawGui(final RenderPipeline pipeline) {
        if (buffer == null) {
            guiState.addGuiElement(new CapturedGuiElement(
                pipeline,
                TextureBinds.guiTextureSetup(textured),
                RenderTargetState.guiScissor(),
                vertices,
                vertexCount,
                textured
            ));
            return;
        }
        draw(pipeline);
    }
    
    /** Uploads and draws the batch through the pipeline-based renderer. */
    public void draw(final RenderPipeline pipeline) {
        try {
            IMMEDIATE.upload();
            final StagedVertexBuffer.ExecuteInfo info = IMMEDIATE.getExecuteInfo(stagedDraw);
            final var target = RenderTargetState.drawTarget();
            final var dynamicTransforms = RenderSystem.getDynamicUniforms().writeTransform(
                RenderSystem.getModelViewMatrixCopy()
            );
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Conflux Map immediate " + pipeline.getLocation(),
                target.getColorTextureView(),
                Optional.empty(),
                // 26.3 replaced RenderTarget.useDepth with hasDepth(); the pipeline must be
                // compiled through the game's fallback cache before setPipeline accepts it,
                // flattenSamplers became flattenUniforms, and bindTexture became setUniform.
                target.hasDepth() ? target.getDepthTextureView() : null,
                OptionalDouble.empty()
            )) {
                pass.setPipeline(RenderTargetState.compiled(pipeline));
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", dynamicTransforms);
                pass.setVertexBuffer(0, info.vertexBuffer().slice());
                RenderTargetState.applyScissor(pass);
                if (BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts()).stream()
                        .anyMatch(layout -> layout.name().equals("Sampler0"))
                    && TextureBinds.boundTexture() != null
                    && TextureBinds.boundSampler() != null) {
                    pass.setUniform("Sampler0", TextureBinds.boundTexture(), TextureBinds.boundSampler());
                }
                pass.setIndexBuffer(info.indexBuffer(), info.indexType());
                pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
            }
        } finally {
            IMMEDIATE.endFrame();
        }
    }
    
    /** Releases the reusable immediate vertex buffers during client shutdown. */
    public static void close() {
        IMMEDIATE.close();
    }

    /**
     * One captured GUI batch, waiting for the GUI renderer's pass.
     *
     * <p>The renderer decides the batch's depth and hands over a shared vertex consumer, so the
     * vertices are replayed flat and already in screen space. {@link #bounds()} is what lets
     * vanilla keep draw order: it starts a fresh layer whenever a new element overlaps one
     * already recorded, which is the only thing standing between the map and the markers on top
     * of it once elements get sorted into texture batches.
     */
    private static final class CapturedGuiElement implements GuiElementRenderState {
        private final RenderPipeline pipeline;
        private final TextureSetup textureSetup;
        private final ScreenRectangle scissorArea;
        private final ScreenRectangle bounds;
        private final float[] vertices;
        private final int vertexCount;
        private final boolean textured;
    
        CapturedGuiElement(
            final RenderPipeline pipeline,
            final TextureSetup textureSetup,
            final ScreenRectangle scissorArea,
            final float[] vertices,
            final int vertexCount,
            final boolean textured
        ) {
            this.pipeline = pipeline;
            this.textureSetup = textureSetup;
            this.scissorArea = scissorArea;
            this.vertices = vertices;
            this.vertexCount = vertexCount;
            this.textured = textured;
            this.bounds = boundsOf(vertices, vertexCount, scissorArea);
        }
    
        @Override
        public RenderPipeline pipeline() {
            return pipeline;
        }
    
        @Override
        public TextureSetup textureSetup() {
            return textureSetup;
        }
    
        @Override
        public ScreenRectangle scissorArea() {
            return scissorArea;
        }
    
        @Override
        public ScreenRectangle bounds() {
            return bounds;
        }
    
        @Override
        public void buildVertices(final VertexConsumer consumer) {
            // 1.21.11 drops the depth argument and offsets the whole batch itself.
            emit(consumer, 0f);
        }
    
        private void emit(final VertexConsumer consumer, final float depth) {
            for (int i = 0; i < vertexCount; i++) {
                final int offset = i * STRIDE;
                consumer.addVertex(vertices[offset], vertices[offset + 1], depth);
                if (textured) {
                    consumer.setUv(vertices[offset + 3], vertices[offset + 4]);
                }
                consumer.setColor(
                    vertices[offset + 5], vertices[offset + 6],
                    vertices[offset + 7], vertices[offset + 8]
                );
            }
        }
    
        private static ScreenRectangle boundsOf(
            final float[] vertices,
            final int vertexCount,
            final ScreenRectangle scissorArea
        ) {
            if (vertexCount == 0) {
                return ScreenRectangle.empty();
            }
            float minX = Float.POSITIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < vertexCount; i++) {
                final float x = vertices[i * STRIDE];
                final float y = vertices[i * STRIDE + 1];
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
            final int left = (int) Math.floor(minX);
            final int top = (int) Math.floor(minY);
            final ScreenRectangle rect = new ScreenRectangle(
                left, top, (int) Math.ceil(maxX) - left, (int) Math.ceil(maxY) - top
            );
            return scissorArea == null ? rect : rect.intersection(scissorArea);
        }
    }
}
package cn.net.rms.confluxmap.mc.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.ProjectionType;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Matrix4f;

/**
 * Off-screen RGBA render target for HUD elements that need real geometric
 * clipping (the circular minimap). Content is drawn into the canvas in canvas
 * pixel units, then sampled back as a texture by arbitrarily-shaped geometry -
 * unlike destination-alpha masking this works regardless of the main
 * framebuffer's alpha/depth state. Render thread only.
 *
 * <p>The canvas projection matches whatever the active version's GUI renderer
 * expects to see around the drawn plane, and modern versions restore the exact
 * projection and vertex sorter that were active on entry.
 */
public final class OffscreenCanvas {
    private RenderTarget framebuffer;
    private int sizePx;
    private ProjectionMatrixBuffer projectionMatrix;

    /** Bind + clear to transparent; sets an ortho projection in canvas pixel units. */
    public void begin(final int sizePx) {
        beginInternal(sizePx, true);
    }

    /** Binds the canvas without clearing it, for persistent texture-atlas updates. */
    public void beginPreserving(final int sizePx) {
        beginInternal(sizePx, false);
    }

    private void beginInternal(final int sizePx, final boolean clear) {
        final boolean created = framebuffer == null || framebuffer.width != sizePx;
        if (framebuffer == null || framebuffer.width != sizePx) {
            close();
            framebuffer = new TextureTarget("Conflux Map minimap", sizePx, sizePx, false);
            this.sizePx = sizePx;
        }
        if (projectionMatrix == null) {
            projectionMatrix = new ProjectionMatrixBuffer("Conflux Map minimap projection");
        }
        if (created || clear) {
            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(
                framebuffer.getColorTexture(), 0
            );
        }
        RenderUtil.setDrawTarget(framebuffer);
        RenderSystem.backupProjectionMatrix();
        // Persistent atlases are filled from the client tick, outside any GUI pass, so the
        // global model-view still holds whatever the world pass left in it. Canvas geometry is
        // already in canvas pixels and needs none of it.
        RenderSystem.getModelViewStack().pushMatrix().identity();
        setProjection(canvasProjection(sizePx));
    }

    /** Modern GUI rendering culls map quads unless the canvas uses the same downward Y axis. */
    private static Matrix4f canvasProjection(final int sizePx) {
        return ortho(0f, sizePx, sizePx, 0f);
    }

    /** Local depth used while the persistent radar atlas is filled from a client tick. */
    public static float atlasDrawPlaneZ() {
        return 0f;
    }

    /**
     * Orthographic projection over the canvas' depth range. 1.19.4 swapped Minecraft's matrix
     * type for JOML's; the argument order (left, right, bottom, top, near, far) is the same in
     * both, so only the constructing call differs. Modern GUI rendering puts its draw plane at
     * z=-11000, so the far plane has to reach past it or every canvas quad is depth-clipped.
     */
    private static Matrix4f ortho(final float left, final float right, final float bottom, final float top) {
        // The canvas installs its own identity model-view, so the depth range only has to cover
        // the z=0 plane every canvas quad sits on.
        return new Matrix4f().setOrtho(left, right, bottom, top, -1000f, 1000f);
    }

    /** 1.20 made the projection carry an explicit vertex sort order; flat GUI geometry sorts by Z. */
    private void setProjection(final Matrix4f projection) {
        RenderSystem.setProjectionMatrix(projectionMatrix.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);
    }

    /** Unbind; restores the main framebuffer and vanilla's GUI projection. */
    public void end(final Minecraft client) {
        RenderUtil.setDrawTarget(null);
        RenderSystem.getModelViewStack().popMatrix();
        RenderSystem.restoreProjectionMatrix();
    }

    /** Binds the canvas contents for sampling; row 0 is the BOTTOM (flip V when sampling). */
    public void bindTexture() {
        RenderUtil.bindTexture(framebuffer.getColorTextureView());
    }

    public void close() {
        if (framebuffer != null) {
            framebuffer.destroyBuffers();
            framebuffer = null;
            sizePx = 0;
        }
        if (projectionMatrix != null) {
            projectionMatrix.close();
            projectionMatrix = null;
        }
    }

    public int size() {
        return sizePx;
    }
}

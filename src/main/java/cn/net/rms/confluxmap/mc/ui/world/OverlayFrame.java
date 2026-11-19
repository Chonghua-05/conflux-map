package cn.net.rms.confluxmap.mc.ui.world;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;

/**
 * One world-overlay render pass's state, adapted by {@link WorldOverlayEvents} from the
 * native render hook's context into version-free terms: camera placement, the matrix stack
 * to bake through, the tick delta, and the immediate buffer the passes draw through.
 *
 * <p>26.1.2 draws the overlay with immediate vertex consumers inside the level render pass
 * rather than through a submit collector, so the frame carries the buffer source as well as
 * the camera the label billboards rotate against.
 */
final class OverlayFrame {
    /** Stack the pass bakes its world-anchored geometry through. */
    final PoseStack matrices;
    final Vec3 cameraPos;
    final Camera camera;
    final MultiBufferSource.BufferSource buffers;
    final float cameraYaw;
    final float cameraPitch;
    final float tickDelta;

    OverlayFrame(
        final PoseStack matrices,
        final Vec3 cameraPos,
        final Camera camera,
        final MultiBufferSource.BufferSource buffers,
        final float cameraYaw,
        final float cameraPitch,
        final float tickDelta
    ) {
        this.matrices = matrices;
        this.cameraPos = cameraPos;
        this.camera = camera;
        this.buffers = buffers;
        this.cameraYaw = cameraYaw;
        this.cameraPitch = cameraPitch;
        this.tickDelta = tickDelta;
    }
}

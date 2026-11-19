package cn.net.rms.confluxmap.mc.ui.world;

import net.minecraft.client.renderer.SubmitNodeCollector;
import org.joml.Quaternionf;
import cn.net.rms.confluxmap.neoforge.compat.FabricOrderedSubmitNodeCollector;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.phys.Vec3;

/**
 * One world-overlay render pass's state, adapted by {@link WorldOverlayEvents} from the
 * native render hook's context into version-free terms: camera placement, the matrix stack
 * to bake through, the tick delta, and the submit handles the passes draw with. Everything
 * {@link WaypointWorldRenderer}, {@link BeamPass}, and {@link LabelPass} touch per frame
 * arrives through this class, so no gate is needed outside the event seam.
 *
 * <p>The version-gated fields are filled for every pass on the versions where they exist;
 * a pass only reads the fields its own gates reference (the beam never reads the label
 * submit handle, and only the label pass reads the billboard rotation).
 */
final class OverlayFrame {
    /** Stack the pass bakes its world-anchored geometry through. */
    final PoseStack matrices;
    final Vec3 cameraPos;
    final SubmitNodeCollector labelSubmits;
    final Quaternionf cameraRotation;
    final FabricOrderedSubmitNodeCollector beamSubmits;
    final float cameraYaw;
    final float cameraPitch;
    final float tickDelta;

    OverlayFrame(
        final PoseStack matrices,
        final Vec3 cameraPos,
        final SubmitNodeCollector labelSubmits,
        final Quaternionf cameraRotation,
        final FabricOrderedSubmitNodeCollector beamSubmits,
        final float cameraYaw,
        final float cameraPitch,
        final float tickDelta
    ) {
        this.matrices = matrices;
        this.cameraPos = cameraPos;
        this.labelSubmits = labelSubmits;
        this.cameraRotation = cameraRotation;
        this.beamSubmits = beamSubmits;
        this.cameraYaw = cameraYaw;
        this.cameraPitch = cameraPitch;
        this.tickDelta = tickDelta;
    }
}
package cn.net.rms.confluxmap.neoforge.compat;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;

/**
 * Mirrors the fabric ordered-collector seam over vanilla's submit-node API. The
 * fabric submitCustom phases all land in the vanilla custom-geometry storage,
 * which routes by the render type's blending, so one delegate covers them.
 */
public final class FabricOrderedSubmitNodeCollector {
    private final SubmitNodeCollector collector;

    private FabricOrderedSubmitNodeCollector(final SubmitNodeCollector collector) {
        this.collector = collector;
    }

    public static FabricOrderedSubmitNodeCollector of(final SubmitNodeCollector collector) {
        return new FabricOrderedSubmitNodeCollector(collector);
    }

    /**
     * Submits one batchable custom-geometry node; vanilla splits it into the solid or
     * translucent custom-geometry storage by the render type's blending, the same split
     * the fabric phase ordering produced.
     */
    public void submitCustom(
        final SubmitRenderPhases phase,
        final CustomFeatureRenderer.Submit submit
    ) {
        final com.mojang.blaze3d.vertex.PoseStack stack = new com.mojang.blaze3d.vertex.PoseStack();
        stack.last().pose().set(submit.pose().pose());
        stack.last().normal().set(submit.pose().normal());
        collector.submitCustomGeometry(
            stack, submit.renderType(), submit.customGeometryRenderer()
        );
    }
}

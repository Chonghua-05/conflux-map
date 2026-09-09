package cn.net.rms.confluxmap.mixin;

import net.minecraft.client.renderer.entity.PufferfishRenderer;
import net.minecraft.client.model.EntityModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the three models vanilla switches by pufferfish inflation state. */
@Mixin(PufferfishRenderer.class)
public interface PufferfishEntityRendererAccessor {
    @Accessor("small")
    EntityModel<?> confluxmap$getSmallModel();
    @Accessor("mid")
    EntityModel<?> confluxmap$getMediumModel();
    @Accessor("big")
    EntityModel<?> confluxmap$getLargeModel();
}

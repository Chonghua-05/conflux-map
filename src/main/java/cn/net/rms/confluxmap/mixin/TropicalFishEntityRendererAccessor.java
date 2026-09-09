package cn.net.rms.confluxmap.mixin;

import net.minecraft.client.renderer.entity.TropicalFishRenderer;
import net.minecraft.client.model.EntityModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the model pair vanilla switches by tropical-fish body shape. */
@Mixin(TropicalFishRenderer.class)
public interface TropicalFishEntityRendererAccessor {
    @Accessor("smallModel")
    EntityModel<?> confluxmap$getSmallModel();

    @Accessor("largeModel")
    EntityModel<?> confluxmap$getLargeModel();
}

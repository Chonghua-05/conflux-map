package cn.net.rms.confluxmap.mixin;
import net.minecraft.client.renderer.entity.AgeableMobRenderer;
import net.minecraft.client.model.EntityModel;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Exposes the model pair that vanilla switches by {@code LivingEntityRenderState#baby}. */
@Mixin(AgeableMobRenderer.class)
public interface AgeableMobEntityRendererAccessor {
    @Accessor("adultModel")
    EntityModel<?> confluxmap$getAdultModel();

    @Accessor("babyModel")
    EntityModel<?> confluxmap$getBabyModel();
}

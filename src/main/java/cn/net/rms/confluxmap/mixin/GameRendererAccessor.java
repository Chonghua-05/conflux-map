package cn.net.rms.confluxmap.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
}

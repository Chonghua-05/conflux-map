package cn.net.rms.confluxmap.mixin;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;

/** Marker mixin reserved for version-specific NeoForge rendering hooks. */
@Mixin(LevelRenderer.class)
public abstract class WorldRendererMixin {
}

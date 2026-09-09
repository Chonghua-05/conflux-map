package cn.net.rms.confluxmap.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Exposes the private large-biomes toggle for world-preset detection (no vanilla getter exists). */
@Pseudo
@Mixin(targets = "net.minecraft.world.biome.source.VanillaLayeredBiomeSource")
public interface VanillaLayeredBiomeSourceAccessor {
}

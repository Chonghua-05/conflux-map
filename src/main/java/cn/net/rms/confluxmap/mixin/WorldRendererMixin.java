package cn.net.rms.confluxmap.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Supplies the world render callbacks the waypoint overlay needs where Fabric's events cannot
 * provide them: on the 1.21.9 line, which ships no world render events at all, it drives the
 * BEFORE_TRANSLUCENT-equivalent section hook and the main-pass tail; on 26.1 it draws the beams
 * at {@code renderLevel}'s RETURN because vanilla consumes no submit nodes after the terrain
 * events there, so the captured path the 26.2 beam uses cannot render. They drive
 * {@link WorldOverlayEvents}, the same seam every other version reaches through its Fabric
 * event registration; on those versions this mixin is empty.
 */
@Mixin(LevelRenderer.class)
public abstract class WorldRendererMixin {

}
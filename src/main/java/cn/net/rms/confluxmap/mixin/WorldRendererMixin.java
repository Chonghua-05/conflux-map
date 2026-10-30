package cn.net.rms.confluxmap.mixin;

//#if MC>=12109 && MC<12111
//$$ import cn.net.rms.confluxmap.ConfluxMapMod;
//$$ import cn.net.rms.confluxmap.mc.ui.world.WorldOverlayEvents;
//$$ import net.minecraft.client.render.command.OrderedRenderCommandQueueImpl;
//$$ import net.minecraft.client.render.command.RenderDispatcher;
//$$ import net.minecraft.client.util.math.MatrixStack;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$ import org.spongepowered.asm.mixin.injection.Inject;
//$$ import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//$$ import org.spongepowered.asm.mixin.Final;
//$$ import org.spongepowered.asm.mixin.Shadow;
//$$ import org.spongepowered.asm.mixin.Unique;
//#endif
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Supplies the two world render callbacks the waypoint overlay needs on the 1.21.9 line, where
 * Fabric API ships no world render events at all: 1.21.9 rewrote world rendering and the events
 * only returned in 1.21.11. They drive {@link WorldOverlayEvents}, the same seam every other
 * version reaches through its Fabric event registration; on those versions this mixin is empty.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    //#if MC>=12109 && MC<12111
    //$$ @Shadow @Final
    //$$ private OrderedRenderCommandQueueImpl entityRenderCommandQueue;
    //$$ @Shadow @Final
    //$$ private RenderDispatcher entityRenderDispatcher;
    //$$
    //$$ /** Per-frame callbacks; the first fault disables waypoint rendering for the session. */
    //$$ @Unique
    //$$ private static boolean confluxmap$waypointWorldRenderFaulted;
    //$$
    //$$ /**
    //$$  * The section render state draws one layer group per call, in OPAQUE, TRANSLUCENT, TRIPWIRE
    //$$  * order, so ordinal 1 is the point matching Fabric's BEFORE_TRANSLUCENT on other versions.
    //$$  */
    //$$ @Inject(
    //$$     method = "method_62214",
    //$$     at = @At(
    //$$         value = "INVOKE",
    //$$         target = "Lnet/minecraft/client/render/SectionRenderState;renderSection("
    //$$             + "Lnet/minecraft/client/render/BlockRenderLayerGroup;)V",
    //$$         ordinal = 1
    //$$     )
    //$$ )
    //$$ private void confluxmap$beforeTranslucent(final CallbackInfo ci) {
    //$$     if (confluxmap$waypointWorldRenderFaulted) {
    //$$         return;
    //$$     }
//$$     try {
//$$         WorldOverlayEvents.onBeforeTranslucent(new MatrixStack());
//$$     } catch (final Throwable fault) {
    //$$         confluxmap$waypointWorldRenderFaulted = true;
    //$$         ConfluxMapMod.LOGGER.error(
    //$$             "waypoint world rendering disabled after a fault", fault
    //$$         );
    //$$     }
    //$$ }
    //$$
    //$$ @Inject(method = "method_62214", at = @At("TAIL"))
    //$$ private void confluxmap$endMain(final CallbackInfo ci) {
    //$$     if (!confluxmap$waypointWorldRenderFaulted) {
//$$         try {
//$$             WorldOverlayEvents.onEndMain(new MatrixStack(), entityRenderCommandQueue);
//$$         } catch (final Throwable fault) {
    //$$             confluxmap$waypointWorldRenderFaulted = true;
    //$$             ConfluxMapMod.LOGGER.error(
    //$$                 "waypoint world rendering disabled after a fault", fault
    //$$             );
    //$$         }
    //$$     }
    //$$     entityRenderDispatcher.render();
    //$$ }
    //#endif
}

package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.compat.GuiTransforms;
import cn.net.rms.confluxmap.core.config.HudAmbient;
import cn.net.rms.confluxmap.mc.ui.hud.ToastHudBounds;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Measures each toast where the manager has already positioned it.
 *
 * <p>Toast width and height are per-toast in vanilla - a multi-line system toast is taller than
 * the default and a long one is wider - so the stack has to be measured rather than assumed. The
 * matrix at this point carries the manager's placement plus anything another mod installed, so
 * the toast's own {@code 0,0,width,height} box maps straight to screen pixels.
 */
@Mixin(targets = "net.minecraft.client.gui.components.toasts.ToastManager$ToastInstance")
public abstract class ToastEntryMixin {
    @Redirect(
        method = "extractRenderState",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/toasts/Toast;extractRenderState"
                + "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;"
                + "Lnet/minecraft/client/gui/Font;J)V"
        )
    )
    private void confluxmap$measureToast(
        final Toast toast,
        final GuiGraphicsExtractor context,
        final Font font,
        final long time
    ) {
        ToastHudBounds.include(toast.width(), toast.height(), GuiTransforms.ambient(context));
        toast.extractRenderState(context, font, time);
    }
}

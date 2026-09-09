package cn.net.rms.confluxmap.mixin;
import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.GuiTransforms;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.config.HudAmbient;
import cn.net.rms.confluxmap.core.config.HudAvoidanceLayout;
import cn.net.rms.confluxmap.core.config.HudTransform;
import cn.net.rms.confluxmap.core.config.MinimapHudVisibility;
import cn.net.rms.confluxmap.core.config.MinimapInformationLayout;
import cn.net.rms.confluxmap.core.config.MinimapPlacement;
import cn.net.rms.confluxmap.mc.ui.hud.ToastHudBounds;
import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves vanilla toast notifications below an overlapping minimap.
 *
 * <p>The stack is measured by {@link ToastEntryMixin} on the previous frame rather than assumed to
 * be one default-sized toast, so a wide or multi-line notification is placed with its real size.
 */
@Mixin(ToastManager.class)
public abstract class ToastHudMixin {
    @Unique
    private boolean confluxmap$toastsShifted;

    @Inject(
        method = "extractRenderState",
        at = @At("HEAD")
    )
    private void confluxmap$beforeToasts(
        final GuiGraphicsExtractor context,
        final CallbackInfo ci
    ) {
        confluxmap$toastsShifted = false;
        final int screenWidth = context.guiWidth();
        final int screenHeight = context.guiHeight();
        final HudAmbient ambient = GuiTransforms.ambient(context);
        ToastHudBounds.beginFrame(screenWidth, screenHeight);
        final float shift = confluxmap$verticalPush(ambient, screenWidth, screenHeight);
        if (shift == 0f) {
            return;
        }
        context.pose().pushMatrix();
        context.pose().translate(0, shift);
        confluxmap$toastsShifted = true;
    }

    @Inject(
        method = "extractRenderState",
        at = @At("RETURN")
    )
    private void confluxmap$afterToasts(
        final GuiGraphicsExtractor context,
        final CallbackInfo ci
    ) {
        if (!confluxmap$toastsShifted) {
            return;
        }
        context.pose().popMatrix();
        confluxmap$toastsShifted = false;
    }

    /** Returns the translation to push, in the coordinates of the matrix {@code ambient} sits on. */
    @Unique
    private static float confluxmap$verticalPush(
        final HudAmbient ambient,
        final int screenWidth,
        final int screenHeight
    ) {
        final ConfluxMapClient app = ConfluxMapClient.get();
        final Minecraft client = Minecraft.getInstance();
        if (app == null || client.player == null) {
            return 0f;
        }
        final ConfluxConfig config = app.config();
        if (!config.minimapHudAvoidance) {
            return 0f;
        }
        final var screen = MinecraftAccess.screen(client);
        if (!MinimapHudVisibility.shouldRender(
            config.minimapEnabled,
            app.gameBridge().session().active(),
            screen instanceof FullscreenMapScreen,
            MinecraftAccess.isContainerScreen(screen),
            MinecraftAccess.isFullDebugOverlayVisible(client)
        )) {
            return 0f;
        }

        final MinimapPlacement.Layout minimap = MinimapPlacement.resolve(
            screenWidth,
            screenHeight,
            config.minimapSize,
            config.minimapPositionX,
            config.minimapPositionY
        );
        final int informationHeight = MinimapInformationLayout.height(
            config.showCoordinates, config.showBiome, config.showLayerIndicator
        );
        final int shift = HudAvoidanceLayout.toastShift(
            config.minimapHudAvoidance,
            screenHeight,
            minimap,
            informationHeight,
            ToastHudBounds.previousFrame(screenWidth, screenHeight)
        );
        final HudTransform transform = HudTransform.ofVerticalShift(shift);
        ToastHudBounds.recordAppliedTransform(transform);
        return transform.rebased(ambient).translateY();
    }
}

package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.GuiTransforms;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.config.HudAmbient;
import cn.net.rms.confluxmap.core.config.HudAvoidanceLayout;
import cn.net.rms.confluxmap.core.config.HudTransform;
import cn.net.rms.confluxmap.core.config.MinimapHudVisibility;
import cn.net.rms.confluxmap.core.config.MinimapPlacement;
import cn.net.rms.confluxmap.mc.ui.hud.VanillaStatusEffectLayout;
import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Moves vanilla status-effect icons while leaving the configured minimap position untouched. */
@Mixin(Gui.class)
public abstract class StatusEffectHudMixin {
    @WrapMethod(
        method = "extractEffects"
    )
    private void confluxmap$renderStatusEffects(
        final GuiGraphicsExtractor context,
        final DeltaTracker tickCounter,
        final Operation<Void> original
    ) {
        final float shift = confluxmap$horizontalPush(
            GuiTransforms.ambient(context), context.guiWidth(), context.guiHeight()
        );
        if (shift == 0f) {
            original.call(context, tickCounter);
            return;
        }
        context.pose().pushMatrix();
        context.pose().translate(shift, 0);
        try {
            original.call(context, tickCounter);
        } finally {
            context.pose().popMatrix();
        }
    }

    /**
     * Returns the translation to push, in the coordinates of the matrix {@code ambient} sits on.
     *
     * <p>The row rectangles are mapped through {@code ambient} first, so an overlay another mod
     * rescaled is compared against the minimap at its rendered size, and the resulting shift is
     * rebased so that scale does not also stretch the shift itself.
     */
    @Unique
    private static float confluxmap$horizontalPush(
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

        int beneficialCount = 0;
        int harmfulCount = 0;
        for (final MobEffectInstance effect : client.player.getActiveEffects()) {
            if (!effect.showIcon()) {
                continue;
            }
            if (effect.getEffect().value().isBeneficial()) {
                beneficialCount++;
            } else {
                harmfulCount++;
            }
        }

        final MinimapPlacement.Layout configuredMinimap = MinimapPlacement.resolve(
            screenWidth,
            screenHeight,
            config.minimapSize,
            config.minimapPositionX,
            config.minimapPositionY
        );
        final boolean demo = client.isDemo();
        final int shift = HudAvoidanceLayout.statusEffectShift(
            config.minimapHudAvoidance,
            configuredMinimap,
            ambient.apply(VanillaStatusEffectLayout.row(
                screenWidth, VanillaStatusEffectLayout.beneficialTop(demo), beneficialCount
            )),
            ambient.apply(VanillaStatusEffectLayout.row(
                screenWidth, VanillaStatusEffectLayout.harmfulTop(demo), harmfulCount
            ))
        );
        return HudTransform.ofHorizontalShift(shift).rebased(ambient).translateX();
    }
}

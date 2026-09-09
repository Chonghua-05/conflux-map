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
import cn.net.rms.confluxmap.mc.ui.hud.ScoreboardHudBounds;
import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Gui;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Measures vanilla's scoreboard without duplicating its layout rules, then moves the complete
 * sidebar away from the configured minimap on the following HUD frame.
 *
 * <p>The measurement goes through the GUI matrix rather than reading the painted coordinates
 * directly, so a mod that rescales the sidebar (TweakerMore's scoreboard scale, for example) is
 * measured at its rendered size instead of vanilla's.
 */
@Mixin(Gui.class)
public abstract class InGameHudMixin {
    @Unique
    private boolean confluxmap$scoreboardTransformed;
    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void confluxmap$beginHudFrame(final CallbackInfo ci) {
        final Minecraft client = Minecraft.getInstance();
        ScoreboardHudBounds.beginFrame(
            client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight()
        );
    }

    @Inject(
        method = "displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;"
            + "Lnet/minecraft/world/scores/Objective;)V",
        at = @At("HEAD")
    )
    private void confluxmap$beforeScoreboard(
        final GuiGraphicsExtractor context,
        final Objective objective,
        final CallbackInfo ci
    ) {
        confluxmap$scoreboardTransformed = false;
        final HudTransform transform =
            confluxmap$scoreboardTransform(context.guiWidth(), context.guiHeight());
        ScoreboardHudBounds.recordAppliedTransform(transform);
        if (transform.isIdentity()) {
            return;
        }
        // Another mod may already have scaled the sidebar around this injection, which would
        // scale this transform's translation with it; rebasing cancels that out.
        final HudTransform pushed =
            transform.rebased(GuiTransforms.ambient(context));
        context.pose().pushMatrix();
        context.pose().translate(pushed.translateX(), pushed.translateY());
        context.pose().scale(pushed.scale(), pushed.scale());
        confluxmap$scoreboardTransformed = true;
    }

    @Inject(
        method = "displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;"
            + "Lnet/minecraft/world/scores/Objective;)V",
        at = @At("RETURN")
    )
    private void confluxmap$afterScoreboard(
        final GuiGraphicsExtractor context,
        final Objective objective,
        final CallbackInfo ci
    ) {
        if (!confluxmap$scoreboardTransformed) {
            return;
        }
        context.pose().popMatrix();
        confluxmap$scoreboardTransformed = false;
    }

    @Unique
    private static HudTransform confluxmap$scoreboardTransform(
        final int screenWidth,
        final int screenHeight
    ) {
        final ConfluxMapClient app = ConfluxMapClient.get();
        final Minecraft client = Minecraft.getInstance();
        if (app == null || client.player == null) {
            return HudTransform.IDENTITY;
        }
        final ConfluxConfig config = app.config();
        final var screen = MinecraftAccess.screen(client);
        if (!MinimapHudVisibility.shouldRender(
            config.minimapEnabled,
            app.gameBridge().session().active(),
            screen instanceof FullscreenMapScreen,
            MinecraftAccess.isContainerScreen(screen),
            MinecraftAccess.isFullDebugOverlayVisible(client)
        )) {
            return HudTransform.IDENTITY;
        }

        final MinimapPlacement.Layout minimap = MinimapPlacement.resolve(
            screenWidth,
            screenHeight,
            config.minimapSize,
            config.minimapPositionX,
            config.minimapPositionY
        );
        return HudAvoidanceLayout.scoreboardTransform(
            config.minimapHudAvoidance,
            screenHeight,
            minimap,
            MinimapInformationLayout.height(
                config.showCoordinates,
                config.showBiome,
                config.showLayerIndicator
            ),
            ScoreboardHudBounds.previousFrame(screenWidth, screenHeight)
        );
    }
    @Redirect(
        method = "displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;"
            + "Lnet/minecraft/world/scores/Objective;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"
        )
    )
    private void confluxmap$captureScoreboardFill(
        final GuiGraphicsExtractor context,
        final int x1,
        final int y1,
        final int x2,
        final int y2,
        final int color
    ) {
        final HudAmbient pose = GuiTransforms.ambient(context);
        ScoreboardHudBounds.include(
            pose.applyX(x1), pose.applyY(y1), pose.applyX(x2), pose.applyY(y2)
        );
        context.fill(x1, y1, x2, y2, color);
    }
}

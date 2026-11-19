package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.mc.render.OffscreenCanvas;
import cn.net.rms.confluxmap.mc.render.GuiShapes;
import cn.net.rms.confluxmap.mc.render.TextureBinds;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;

/** Vanilla-style backdrop blur shared by every split-map search screen. */
final class SplitMapBlur {
    private final OffscreenCanvas canvas = new OffscreenCanvas();
    private boolean failed;

    boolean render(
        final GuiDraw draw,
        final Minecraft client,
        final SplitMapLayout layout,
        final Consumer<PoseStack> renderBackdrop
    ) {
        if (failed) {
            return false;
        }
        final int targetWidth = Math.max(1, client.getWindow().getWidth());
        final int targetHeight = Math.max(1, client.getWindow().getHeight());
        try {
            canvas.begin(targetWidth, targetHeight);
            try {
                final PoseStack backdrop = new PoseStack();
                backdrop.scale(
                    targetWidth / (float) layout.screenWidth(),
                    targetHeight / (float) layout.screenHeight(),
                    1f
                );
                renderBackdrop.accept(backdrop);
            } finally {
                canvas.end(client);
            }
            if (!canvas.applyMenuBlur(client)) {
                failed = true;
                close();
                return false;
            }

            TextureBinds.beginTexturedQuads();
            canvas.bindTextureLinear();
            final float panelU = layout.panelLeft() / (float) layout.screenWidth();
            GuiShapes.drawQuad(
                draw.matrices(),
                layout.panelLeft(), 0f, layout.panelWidth(), layout.screenHeight(),
                panelU, 1f, 1f, 0f
            );
            return true;
        } catch (final Exception error) {
            failed = true;
            close();
            ConfluxMapMod.LOGGER.warn(
                "Disabling the split-map panel blur after a render failure", error
            );
            return false;
        }
    }

    void close() {
        canvas.close();
    }
}
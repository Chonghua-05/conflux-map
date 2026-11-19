package cn.net.rms.confluxmap.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the retained-mode GUI render state so custom batches join vanilla's replay list. */
@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsExtractorAccessor {
    @Accessor("guiRenderState")
    GuiRenderState confluxmap$guiRenderState();
}

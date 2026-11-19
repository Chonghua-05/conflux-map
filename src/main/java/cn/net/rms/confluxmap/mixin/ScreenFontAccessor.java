package cn.net.rms.confluxmap.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Screen.font is protected in mojmap; expose it for the non-screen UI controllers. */
@Mixin(Screen.class)
public interface ScreenFontAccessor {
    @Accessor("font")
    Font confluxmap$font();
}

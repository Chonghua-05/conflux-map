package cn.net.rms.confluxmap.compat;

import cn.net.rms.confluxmap.mixin.ScreenFontAccessor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;

/** Reads Screen.font (protected in mojmap) from non-subclass UI code. */
public final class ScreenFont {
    private ScreenFont() {
    }

    public static Font of(final Screen screen) {
        return ((ScreenFontAccessor) screen).confluxmap$font();
    }
}

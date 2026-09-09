package cn.net.rms.confluxmap.mc.input;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Version seam for Minecraft's controls/keybind screen split in 1.18. */
final class VanillaHotkeyScreen {
    private VanillaHotkeyScreen() {
    }

    static Screen create(final Screen parent, final Minecraft client) {
        return new KeyBindsScreen(parent, client.options);
    }
}

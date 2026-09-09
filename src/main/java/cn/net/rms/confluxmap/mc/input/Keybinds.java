package cn.net.rms.confluxmap.mc.input;

import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.mc.world.LayerSelector;
import com.mojang.blaze3d.platform.InputConstants;
import cn.net.rms.confluxmap.neoforge.compat.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/** NeoForge key mappings and their client-tick dispatcher. */
public final class Keybinds {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
        Identifier.fromNamespaceAndPath("confluxmap", "controls")
    );
    private static final KeyMapping[] MAPPINGS = new KeyMapping[KeybindAction.values().length];
    private final KeybindActionHandler actionHandler;

    public static void registerMappings(final RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        for (final KeybindAction action : KeybindAction.values()) {
            final KeyMapping mapping = new KeyMapping(
                action.translationKey(), InputConstants.Type.KEYSYM, action.vanillaDefaultKey(), CATEGORY
            );
            MAPPINGS[action.ordinal()] = mapping;
            event.register(mapping);
        }
    }

    public Keybinds(final ConfluxConfig config, final ConfigIo configIo, final LayerSelector layerSelector) {
        actionHandler = new KeybindActionHandler(config, configIo, layerSelector, mapping(KeybindAction.OPEN_MAP));
        ClientTickEvents.END_CLIENT_TICK.register(client -> poll());
    }

    private static KeyMapping mapping(final KeybindAction action) {
        final KeyMapping mapping = MAPPINGS[action.ordinal()];
        if (mapping == null) {
            throw new IllegalStateException("Conflux Map key mappings were not registered");
        }
        return mapping;
    }

    private void poll() {
        for (final KeybindAction action : KeybindAction.values()) {
            final KeyMapping mapping = MAPPINGS[action.ordinal()];
            while (mapping != null && mapping.consumeClick()) {
                actionHandler.trigger(action);
            }
        }
    }

    public String openMapKeyDisplayName() {
        return mapping(KeybindAction.OPEN_MAP).getTranslatedKeyMessage().getString();
    }

    public void openHotkeySettings(final Screen parent) {
        final Minecraft client = Minecraft.getInstance();
        client.setScreen(VanillaHotkeyScreen.create(parent, client));
    }
}

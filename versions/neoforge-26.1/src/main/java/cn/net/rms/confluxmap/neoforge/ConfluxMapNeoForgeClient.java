package cn.net.rms.confluxmap.neoforge;

import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.mc.input.Keybinds;
import cn.net.rms.confluxmap.mc.ui.screen.ConfigScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import cn.net.rms.confluxmap.neoforge.network.ClientPlayNetworking;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.minecraft.client.Minecraft;

/** Client-only entrypoint, kept separate so dedicated servers never resolve client classes. */
@Mod(value = ConfluxMapNeoForge.MOD_ID, dist = Dist.CLIENT)
public final class ConfluxMapNeoForgeClient {
    private ConfluxConfig config;

    public ConfluxMapNeoForgeClient(final IEventBus modEventBus, final ModContainer modContainer) {
        modEventBus.addListener(ClientPlayNetworking::registerPayloadHandlers);
        modEventBus.addListener(Keybinds::registerMappings);
        HudElementRegistry.install(modEventBus);
        modEventBus.addListener(this::initialize);
        modContainer.registerExtensionPoint(
            IConfigScreenFactory.class,
            (IConfigScreenFactory) (container, parent) -> new ConfigScreen(parent)
        );
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) ->
            ClientTickEvents.END_CLIENT_TICK.invoke(Minecraft.getInstance()));
        NeoForge.EVENT_BUS.addListener((ClientStoppingEvent event) ->
            ClientLifecycleEvents.CLIENT_STOPPING.invoke(event.getClient()));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) ->
            ClientPlayConnectionEvents.JOIN.invoke(null, event.getConnection(), Minecraft.getInstance()));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) ->
            ClientPlayConnectionEvents.DISCONNECT.invoke(null, Minecraft.getInstance()));
    }

    private void initialize(final FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            final ConfigIo configIo = new ConfigIo(
                FMLPaths.CONFIGDIR.get().resolve(ConfluxMapNeoForge.MOD_ID).resolve("config.json"),
                ConfluxMapNeoForge.LOGGER
            );
            config = configIo.load();
            new ConfluxMapClient().onInitializeClient();
        });
    }
}

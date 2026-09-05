package net.fabricmc.fabric.api.client.rendering.v1.hud;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
public final class HudElementRegistry {
    private static final CopyOnWriteArrayList<BiConsumer<GuiGraphicsExtractor, DeltaTracker>> CALLBACKS =
        new CopyOnWriteArrayList<>();
    private static final Identifier LAYER_ID = Identifier.fromNamespaceAndPath("confluxmap", "compat_hud");

    public static void addLast(Identifier id, BiConsumer<GuiGraphicsExtractor, DeltaTracker> renderer) {
        CALLBACKS.add(renderer);
    }

    public static void attachElementBefore(Identifier before, Identifier id, BiConsumer<GuiGraphicsExtractor, DeltaTracker> renderer) {
        CALLBACKS.add(renderer);
    }

    /** Installs one native NeoForge layer whose callback list can be populated during client setup. */
    public static void install(final IEventBus modEventBus) {
        modEventBus.addListener(HudElementRegistry::registerNeoForgeLayer);
    }

    private static void registerNeoForgeLayer(final RegisterGuiLayersEvent event) {
        event.registerAboveAll(LAYER_ID, (graphics, delta) -> {
            for (final BiConsumer<GuiGraphicsExtractor, DeltaTracker> callback : CALLBACKS) {
                callback.accept(graphics, delta);
            }
        });
    }
    private HudElementRegistry() {}
}

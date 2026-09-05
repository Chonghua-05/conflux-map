package net.fabricmc.fabric.api.client.event.lifecycle.v1;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
public final class ClientTickEvents {
    public static final Event END_CLIENT_TICK = new Event();
    public static final class Event {
        private final CopyOnWriteArrayList<Consumer<Minecraft>> listeners = new CopyOnWriteArrayList<>();
        public void register(Consumer<Minecraft> listener) { listeners.add(listener); }
        public void invoke(Minecraft client) { listeners.forEach(listener -> listener.accept(client)); }
    }
    private ClientTickEvents() {}
}

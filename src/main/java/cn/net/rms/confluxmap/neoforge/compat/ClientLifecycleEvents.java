package cn.net.rms.confluxmap.neoforge.compat;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
public final class ClientLifecycleEvents {
    public static final Event CLIENT_STOPPING = new Event();
    public static final class Event {
        private final CopyOnWriteArrayList<Consumer<Minecraft>> listeners = new CopyOnWriteArrayList<>();
        public void register(Consumer<Minecraft> listener) { listeners.add(listener); }
        public void invoke(Minecraft client) { listeners.forEach(listener -> listener.accept(client)); }
    }
    private ClientLifecycleEvents() {}
}

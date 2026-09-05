package net.fabricmc.fabric.api.client.networking.v1;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
public final class ClientPlayConnectionEvents {
    @FunctionalInterface public interface Join { void join(ClientPacketListener handler, Connection sender, Minecraft client); }
    @FunctionalInterface public interface Disconnect { void disconnect(ClientPacketListener handler, Minecraft client); }
    public static final JoinEvent JOIN = new JoinEvent();
    public static final DisconnectEvent DISCONNECT = new DisconnectEvent();
    public static final class JoinEvent {
        private final CopyOnWriteArrayList<Join> listeners = new CopyOnWriteArrayList<>();
        public void register(Join listener) { listeners.add(listener); }
        public void invoke(ClientPacketListener h, Connection c, Minecraft m) { listeners.forEach(x -> x.join(h,c,m)); }
    }
    public static final class DisconnectEvent {
        private final CopyOnWriteArrayList<Disconnect> listeners = new CopyOnWriteArrayList<>();
        public void register(Disconnect listener) { listeners.add(listener); }
        public void invoke(ClientPacketListener h, Minecraft m) { listeners.forEach(x -> x.disconnect(h,m)); }
    }
    private ClientPlayConnectionEvents() {}
}

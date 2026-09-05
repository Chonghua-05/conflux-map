package cn.net.rms.confluxmap.compat;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

public final class PlayNetworking {
    private PlayNetworking() {}
    public static void registerServer(Identifier id, cn.net.rms.confluxmap.neoforge.network.PlayNetworking.ServerReceiver receiver) {
        cn.net.rms.confluxmap.neoforge.network.PlayNetworking.registerServer(id, receiver);
    }
    public static void sendServer(ServerPlayer player, Identifier id, byte[] payload) {
        cn.net.rms.confluxmap.neoforge.network.PlayNetworking.sendServer(player, id, payload);
    }
    public static boolean canSend(ServerPlayer player, Identifier id) {
        return cn.net.rms.confluxmap.neoforge.network.PlayNetworking.canSend(player, id);
    }
    public static void registerClient(Identifier id, cn.net.rms.confluxmap.neoforge.network.ClientPlayNetworking.ClientReceiver receiver) {
        cn.net.rms.confluxmap.neoforge.network.ClientPlayNetworking.registerClient(id, receiver);
    }
    public static void sendClient(Identifier id, byte[] payload) {
        cn.net.rms.confluxmap.neoforge.network.ClientPlayNetworking.sendClient(id, payload);
    }
}

package cn.net.rms.confluxmap.compat;

import java.io.IOException;
import java.io.InputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
//#if MC>=260200
//$$ import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
//#else
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
//#endif
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
//#if MC>=12111
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
//#endif

/** Small access seams for Minecraft methods whose signatures changed after 1.17.1. */
public final class MinecraftAccess {
    private MinecraftAccess() {
    }

    public static int viewDistance(final Minecraft client) {
        //#if MC>=12000
        return client.options.renderDistance().get();
        //#else
        //$$ return client.options.viewDistance;
        //#endif
    }

    /**
     * The player's biome-blend radius in blocks: the tint at a position is averaged over the
     * square that many blocks around it, and 0 turns blending off entirely.
     */
    public static int biomeBlendRadius(final Minecraft client) {
        //#if MC>=12000
        return client.options.biomeBlendRadius().get();
        //#else
        //$$ return client.options.biomeBlendRadius;
        //#endif
    }

    /**
     * Live video gamma. Tweakeroo Gamma Override deliberately writes through this vanilla
     * option, including values above the normal slider range, so no optional API is needed.
     */
    public static float gamma(final Minecraft client) {
        //#if MC>=12000
        return client.options.gamma().get().floatValue();
        //#else
        //$$ return (float) client.options.gamma;
        //#endif
    }

    /** Whether the configured vanilla player-list key is currently held. */
    public static boolean isPlayerListKeyPressed(final Minecraft client) {
        //#if MC>=260100
        return client.options.keyPlayerList.isDown();
        //#elseif MC>=11800
        //$$ return client.options.playerListKey.isPressed();
        //#else
        //$$ return client.options.keyPlayerList.isPressed();
        //#endif
    }

    /** The active screen, whose owner moved from Minecraft to Gui in 26.2. */
    public static Screen screen(final Minecraft client) {
        //#if MC>=260200
        //$$ return client.gui.screen();
        //#else
        return client.screen;
        //#endif
    }

    /** Changes the active screen through the version-appropriate owner. */
    public static void setScreen(final Minecraft client, final Screen screen) {
        //#if MC>=260200
        //$$ client.gui.setScreen(screen);
        //#else
        client.setScreen(screen);
        //#endif
    }

    /** Inventory-style screens also host JEI/REI overlays, so the minimap HUD must yield to them. */
    public static boolean isContainerScreen(final Screen screen) {
        //#if MC>=260200
        //$$ return screen instanceof AbstractContainerScreen<?>;
        //#else
        return screen instanceof AbstractContainerScreen<?>;
        //#endif
    }

    /** Whether the full vanilla debug overlay is visible. */
    public static boolean isFullDebugOverlayVisible(final Minecraft client) {
        //#if MC>=260100
        return client.debugEntries.isOverlayVisible();
        //#elseif MC>=12109
        //$$ return client.debugHudEntryList.isF3Enabled();
        //#elseif MC>=12100
        //$$ return client.getDebugHud().shouldShowDebugHud();
        //#else
        //$$ return client.options.debugEnabled;
        //#endif
    }

    public static void sendChatMessage(final Minecraft client, final String message) {
        //#if MC>=12000
        if (client.getConnection() != null) {
            client.getConnection().sendChat(message);
        }
        //#else
        //$$ if (client.player != null) {
        //$$     client.player.sendChatMessage(message);
        //$$ }
        //#endif
    }

    /** Whether the server exposed at least one named command to this player's command tree. */
    public static boolean canSendCommand(final Minecraft client, final String... commandNames) {
        if (client.player == null || client.getConnection() == null) {
            return false;
        }
        for (final String commandName : commandNames) {
            if (client.getConnection().getCommands().getRoot().getChild(commandName) != null) {
                return true;
            }
        }
        return false;
    }

    /** Sends one command without the leading slash through the version-appropriate chat path. */
    public static void sendCommand(final Minecraft client, final String command) {
        //#if MC>=12000
        if (client.getConnection() != null) {
            client.getConnection().sendCommand(command);
        }
        //#else
        //$$ if (client.player != null) {
        //$$     client.player.sendChatMessage("/" + command);
        //$$ }
        //#endif
    }

    public static String playerName(final ServerPlayer player) {
        //#if MC>=12100
        return player.getName().getString();
        //#else
        //$$ return player.getEntityName();
        //#endif
    }

    public static InputStream openResource(final ResourceManager resources, final Identifier id)
        throws IOException {
        //#if MC>=12000
        return resources.getResource(id)
            .orElseThrow(() -> new IOException("missing resource: " + id))
            .open();
        //#else
        //$$ return resources.getResource(id).getInputStream();
        //#endif
    }

    public static void sendFeedback(
        final CommandSourceStack source,
        final Component message,
        final boolean broadcastToOps
    ) {
        //#if MC>=12000
        source.sendSuccess(() -> message, broadcastToOps);
        //#else
        //$$ source.sendFeedback(message, broadcastToOps);
        //#endif
    }

    public static boolean hasPermission(final CommandSourceStack source, final int level) {
        //#if MC>=12111
        return source.permissions().hasPermission(
            new Permission.HasCommandLevel(PermissionLevel.byId(level))
        );
        //#else
        //$$ return source.hasPermissionLevel(level);
        //#endif
    }

    public static boolean hasPermission(final ServerPlayer player, final int level) {
        //#if MC>=12111
        return player.permissions().hasPermission(
            new Permission.HasCommandLevel(PermissionLevel.byId(level))
        );
        //#else
        //$$ return player.hasPermissionLevel(level);
        //#endif
    }
}

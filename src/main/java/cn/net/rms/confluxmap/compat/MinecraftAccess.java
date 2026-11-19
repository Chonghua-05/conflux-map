package cn.net.rms.confluxmap.compat;

import java.io.IOException;
import java.io.InputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

/** Small access seams for Minecraft methods whose signatures changed after 1.17.1. */
public final class MinecraftAccess {
    private MinecraftAccess() {
    }

    public static int viewDistance(final Minecraft client) {
        return client.options.renderDistance().get();
    }

    /** The client's selected language code (e.g. {@code "zh_cn"}). */
    public static String language(final Minecraft client) {
        return client.options.languageCode;
    }

    /**
     * Identity of the current integrated server (null without one): lets callers cache
     * per-session values on the client thread and re-resolve when the instance changes.
     */
    public static Object singleplayerServerRef(final Minecraft client) {
        return client.getSingleplayerServer();
    }

    /**
     * The current singleplayer world's save root, or null on a dedicated connection. Read
     * on the client thread; the MCA backfill scanner resolves region files under it.
     */
    public static java.nio.file.Path singleplayerSaveRoot(final Minecraft client) {
        return client.getSingleplayerServer() == null
            ? null
            : client.getSingleplayerServer().getWorldPath(
                net.minecraft.world.level.storage.LevelResource.ROOT);
    }

    /**
     * The current singleplayer world's {@code datapacks} directory, or null on a dedicated
     * connection. Read on the client thread; the returned path stays valid for the whole
     * session. Datapack translations only ever exist server-side, and the companion stays
     * inactive in an unpublished singleplayer world, so the client reads the local level
     * itself instead of waiting for a network answer that never comes.
     */
    public static java.nio.file.Path singleplayerDatapacksDir(final Minecraft client) {
        return client.getSingleplayerServer() == null
            ? null
            : client.getSingleplayerServer().getWorldPath(
                net.minecraft.world.level.storage.LevelResource.DATAPACK_DIR);
    }

    /**
     * The player's biome-blend radius in blocks: the tint at a position is averaged over the
     * square that many blocks around it, and 0 turns blending off entirely.
     */
    public static int biomeBlendRadius(final Minecraft client) {
        return client.options.biomeBlendRadius().get();
    }

    /**
     * Live video gamma. Tweakeroo Gamma Override deliberately writes through this vanilla
     * option, including values above the normal slider range, so no optional API is needed.
     */
    public static float gamma(final Minecraft client) {
        return client.options.gamma().get().floatValue();
    }

    /** Whether the configured vanilla player-list key is currently held. */
    public static boolean isPlayerListKeyPressed(final Minecraft client) {
        return client.options.keyPlayerList.isDown();
    }

    /** The active screen, whose owner moved from Minecraft to Gui in 26.2. */
    public static Screen screen(final Minecraft client) {
        return client.screen;
    }

    /** Changes the active screen through the version-appropriate owner. */
    public static void setScreen(final Minecraft client, final Screen screen) {
        client.setScreen(screen);
    }

    /** Inventory-style screens also host JEI/REI overlays, so the minimap HUD must yield to them. */
    public static boolean isContainerScreen(final Screen screen) {
        return screen instanceof AbstractContainerScreen<?>;
    }

    /** Whether the full vanilla debug overlay is visible. */
    public static boolean isFullDebugOverlayVisible(final Minecraft client) {
        return client.debugEntries.isOverlayVisible();
    }

    /**
     * Whether the player hid the vanilla HUD with F1. The state lived in the options until
     * 26.1 (hideGui) and moved to the Hud itself (26.2 toggles it, isHidden reports it), so
     * HUD-side mods must hide with it — Fabric's HUD callbacks keep firing either way.
     */
    public static boolean isHudHidden(final Minecraft client) {
        return client.options.hideGui;
    }

    public static void sendChat(final Minecraft client, final String message) {
        if (client.getConnection() != null) {
            client.getConnection().sendChat(message);
        }
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
        if (client.getConnection() != null) {
            client.getConnection().sendCommand(command);
        }
    }

    public static String playerName(final ServerPlayer player) {
        return player.getName().getString();
    }

    public static InputStream openResource(final ResourceManager resources, final Identifier id)
        throws IOException {
        return resources.getResource(id)
            .orElseThrow(() -> new IOException("missing resource: " + id))
            .open();
    }

    /** True when the default pack or a loaded resource pack provides the resource. */
    public static boolean resourceExists(final ResourceManager resources, final Identifier id) {
        return resources.getResource(id).isPresent();
    }

    public static void sendFeedback(
        final CommandSourceStack source,
        final Component message,
        final boolean broadcastToOps
    ) {
        source.sendSuccess(() -> message, broadcastToOps);
    }

    public static boolean hasPermission(final CommandSourceStack source, final int level) {
        return source.permissions().hasPermission(
            new Permission.HasCommandLevel(PermissionLevel.byId(level))
        );
    }

    public static boolean hasPermission(final ServerPlayer player, final int level) {
        return player.permissions().hasPermission(
            new Permission.HasCommandLevel(PermissionLevel.byId(level))
        );
    }
}
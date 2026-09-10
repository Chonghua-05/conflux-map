package cn.net.rms.confluxmap.paper;

import java.net.URISyntaxException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Captures the player state the companion needs on each player's own scheduler.
 *
 * <p>Coordinates, names, permissions and profiles all belong to the entity, so the global tick
 * that drives the companion may not read them: on a regionised server each player is ticked by a
 * different thread. This probe asks every player to publish an immutable snapshot of itself and
 * hands those snapshots to the global tick instead.
 */
final class PaperPlayerProbe {
    private static final int REFRESH_INTERVAL_TICKS = 5;

    /**
     * An immutable view of one player, taken while the owning region held it.
     *
     * @param spectator whether the client should be drawn as a spectator by the entity radar
     * @param hidden whether the web map should omit the marker entirely
     * @param admin whether the player may manage server-owned shared waypoints
     */
    record Snapshot(
        UUID id,
        String name,
        String worldKey,
        double x,
        double y,
        double z,
        boolean spectator,
        boolean hidden,
        boolean admin,
        String skinUrl
    ) {
        /** Resolves the stored skin URL, or null when it is absent or not a valid URI. */
        java.net.URI skin() {
            if (skinUrl == null) {
                return null;
            }
            try {
                return new java.net.URI(skinUrl);
            } catch (final URISyntaxException e) {
                // The avatar cache independently rejects non-Minecraft texture origins.
                return null;
            }
        }
    }

    private final Plugin plugin;
    private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();
    private int ticks;

    PaperPlayerProbe(final Plugin plugin) {
        this.plugin = plugin;
    }

    /** Asks every online player to republish itself, at most once per refresh interval. */
    void refresh() {
        if (++ticks < REFRESH_INTERVAL_TICKS) {
            return;
        }
        ticks = 0;
        final Set<UUID> online = new HashSet<>();
        // The player list itself is global state; only the per-player reads need the owner.
        for (final Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
            PaperPlatform.onPlayer(plugin, player, () -> publish(player));
        }
        snapshots.keySet().retainAll(online);
    }

    Collection<Snapshot> snapshots() {
        return List.copyOf(snapshots.values());
    }

    Snapshot snapshotOf(final UUID playerId) {
        return snapshots.get(playerId);
    }

    void forget(final UUID playerId) {
        snapshots.remove(playerId);
    }

    void clear() {
        snapshots.clear();
        ticks = 0;
    }

    private void publish(final Player player) {
        final Location location = player.getLocation();
        final GameMode mode = player.getGameMode();
        final java.net.URL skin = player.getPlayerProfile().getTextures().getSkin();
        snapshots.put(player.getUniqueId(), new Snapshot(
            player.getUniqueId(),
            player.getName(),
            player.getWorld().getKey().toString(),
            location.getX(),
            location.getY(),
            location.getZ(),
            mode == GameMode.SPECTATOR,
            mode == GameMode.SPECTATOR || player.isInvisible(),
            player.hasPermission("confluxmap.admin"),
            skin == null ? null : skin.toString()
        ));
    }
}

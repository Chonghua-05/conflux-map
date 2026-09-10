package cn.net.rms.confluxmap.paper;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Region-aware scheduling entry points for the Paper companion.
 *
 * <p>Every call reaches the {@code io.papermc.paper.threadedregions} schedulers, which Paper
 * implements even when it is not running a regionised world: on a standard Paper server the
 * global region scheduler resolves to the main thread and the region scheduler simply runs the
 * task there too. That is why this module keeps a single scheduling path instead of branching
 * per platform.
 *
 * <p>The three thread scopes below are the only ones the companion is allowed to use:
 * <ul>
 *   <li><b>Global</b> — owns the correction service, invalidation publishers and every
 *       subscription table. Those live in {@code common/} and are not thread safe, so they must
 *       stay on one logical thread, exactly as they did on the Bukkit main thread.</li>
 *   <li><b>Region</b> — owns chunks. Snapshots and load levels may only be read here.</li>
 *   <li><b>Entity</b> — owns players, and follows them across regions.</li>
 * </ul>
 */
final class PaperPlatform {
    /**
     * Whether the runtime splits each world into independently ticked regions (Folia and its
     * forks). Detected once through the server class that only regionised builds carry.
     */
    static final boolean FOLIA = detectFolia();

    private PaperPlatform() {
    }

    /** Starts the repeating driver that owns every piece of global companion state. */
    static ScheduledTask startTicking(
        final Plugin plugin,
        final Consumer<ScheduledTask> tick
    ) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, tick, 1L, 1L);
    }

    /** Runs {@code task} on the global region, which is the main thread on standard Paper. */
    static void global(final Plugin plugin, final Runnable task) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }

    /** Runs {@code task} on the region that owns the given chunk. */
    static void onChunk(
        final Plugin plugin,
        final World world,
        final int chunkX,
        final int chunkZ,
        final Runnable task
    ) {
        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, task);
    }

    /** Runs {@code task} on the player's own scheduler, which follows the player across regions. */
    static void onPlayer(final Plugin plugin, final Player player, final Runnable task) {
        player.getScheduler().execute(plugin, task, null, 0L);
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (final ClassNotFoundException e) {
            return false;
        }
    }
}

package cn.net.rms.confluxmap.server;

import java.util.Iterator;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Removes idle progressive tasks; under capacity pressure, completed unwatched entries are safe to
 * recreate, so they are evicted first. Shared by the Fabric and Paper companions.
 */
public final class ProgressiveTaskEviction {
    private ProgressiveTaskEviction() {
    }

    public static <K, T extends ProgressiveTask> void evict(
        final Map<K, T> tasks,
        final long nowNanos,
        final boolean forCapacity,
        final Predicate<K> watched
    ) {
        final Iterator<Map.Entry<K, T>> iterator = tasks.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<K, T> entry = iterator.next();
            final K key = entry.getKey();
            final T task = entry.getValue();
            final boolean isWatched = watched.test(key);
            final boolean expired = !isWatched
                && nowNanos - task.lastRequestedAtNanos() > ProgressivePolicy.IDLE_TTL_NANOS;
            final boolean capacityVictim = forCapacity
                && tasks.size() >= ProgressivePolicy.MAX_ACTIVE_TILES
                && task.complete() && !isWatched;
            if (expired || capacityVictim) {
                task.close();
                iterator.remove();
            }
            if (forCapacity && tasks.size() < ProgressivePolicy.MAX_ACTIVE_TILES) {
                return;
            }
        }
    }
}

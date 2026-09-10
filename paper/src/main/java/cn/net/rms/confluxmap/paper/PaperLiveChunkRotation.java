package cn.net.rms.confluxmap.paper;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Round-robin order over the chunks the live-summary budget is spent on.
 *
 * <p>Chunk handles belong to their region, so only keys are tracked here; the rotation itself is
 * what keeps the budget spread evenly instead of always re-summarising the same corner of the
 * world. A chunk unloaded while it waits in the rotation is dropped when it reaches the front,
 * which is why the queue may hold keys the map no longer knows about.
 *
 * @param <K> chunk key type
 */
final class PaperLiveChunkRotation<K> {
    private final Map<K, Boolean> tracked = new ConcurrentHashMap<>();
    private final Queue<K> rotation = new ConcurrentLinkedQueue<>();

    /** Tracks a chunk, keeping its place if it was already tracked. */
    void add(final K key) {
        if (tracked.put(key, Boolean.TRUE) == null) {
            rotation.add(key);
        }
    }

    void remove(final K key) {
        tracked.remove(key);
    }

    boolean contains(final K key) {
        return tracked.containsKey(key);
    }

    int size() {
        return tracked.size();
    }

    /** Returns the next tracked key after moving it to the back, or null when none is tracked. */
    K next() {
        while (!rotation.isEmpty()) {
            final K key = rotation.poll();
            if (key == null) {
                break;
            }
            if (!tracked.containsKey(key)) {
                continue;
            }
            rotation.add(key);
            return key;
        }
        return null;
    }

    void clear() {
        tracked.clear();
        rotation.clear();
    }
}

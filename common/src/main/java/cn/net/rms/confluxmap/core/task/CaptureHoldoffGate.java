package cn.net.rms.confluxmap.core.task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rate-limits full-chunk recaptures driven by live packet marks. A chunk whose live data keeps
 * changing - most visibly redstone toggling every tick - would otherwise be fully resampled on
 * every capture budget slot it can reach, because block-update and light-update packets each
 * re-mark it the moment the previous snapshot is stored. Marks arriving inside the hold-off
 * window after a capture are deferred; once the window expires, the latest deferred mark matures
 * and the chunk re-enters the normal queues, so no change is ever dropped - repeated changes
 * just collapse to one recapture per window. A window of zero restores the previous
 * mark-immediately-recapture behavior.
 *
 * <p>Only packet-driven marks are gated; chunk-load and session reseeds bypass the caller and
 * stay immediate. Memory is bounded by the capture budget: entries exist solely for chunks
 * captured within the current window plus the marks they deferred.
 */
public final class CaptureHoldoffGate {
    private final Map<Long, Long> recentlyCaptured = new HashMap<>();
    private final Set<Long> deferred = new HashSet<>();
    private int holdoffTicks;
    private long tick;

    public CaptureHoldoffGate(final int holdoffTicks) {
        this.holdoffTicks = holdoffTicks;
    }

    public void configure(final int holdoffTicks) {
        if (this.holdoffTicks == holdoffTicks) {
            return;
        }
        this.holdoffTicks = holdoffTicks;
        if (holdoffTicks <= 0) {
            recentlyCaptured.clear();
            deferred.clear();
        }
    }

    /**
     * Whether a packet-driven mark for this chunk should enter the capture queues now. A mark
     * inside the window is remembered as deferred and matures via {@link #tick()} instead.
     */
    public boolean tryMark(final long chunkKey) {
        if (holdoffTicks <= 0) {
            return true;
        }
        final Long capturedAt = recentlyCaptured.get(chunkKey);
        if (capturedAt == null || tick - capturedAt >= holdoffTicks) {
            deferred.remove(chunkKey);
            return true;
        }
        deferred.add(chunkKey);
        return false;
    }

    /** Records that this chunk was captured now, starting a fresh hold-off window for it. */
    public void recordCaptured(final long chunkKey) {
        if (holdoffTicks <= 0) {
            return;
        }
        recentlyCaptured.put(chunkKey, tick);
    }

    /**
     * Advances one tick and returns the chunk keys whose deferred marks matured: their hold-off
     * window expired while a newer mark waited, so they should re-enter the capture queues now.
     * Tracking for every other expired window is dropped with them.
     */
    public List<Long> tick() {
        tick++;
        if (recentlyCaptured.isEmpty()) {
            return List.of();
        }
        List<Long> matured = null;
        final Iterator<Map.Entry<Long, Long>> iterator = recentlyCaptured.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<Long, Long> entry = iterator.next();
            if (tick - entry.getValue() < holdoffTicks) {
                continue;
            }
            if (deferred.remove(entry.getKey())) {
                if (matured == null) {
                    matured = new ArrayList<>();
                }
                matured.add(entry.getKey());
            }
            iterator.remove();
        }
        return matured == null ? List.of() : matured;
    }

    /** Drops all state; used on session change so a previous world cannot suppress a new one. */
    public void clear() {
        recentlyCaptured.clear();
        deferred.clear();
    }
}

package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProgressivePolicyTest {
    private static final class FakeTask implements ProgressiveTask {
        long lastRequestedAtNanos;
        boolean complete;
        final AtomicInteger closes = new AtomicInteger();

        FakeTask(final long lastRequestedAtNanos, final boolean complete) {
            this.lastRequestedAtNanos = lastRequestedAtNanos;
            this.complete = complete;
        }

        @Override
        public long lastRequestedAtNanos() {
            return lastRequestedAtNanos;
        }

        @Override
        public boolean complete() {
            return complete;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    @Test
    void unwatchedIdleTasksExpireAfterTheTtl() {
        final Map<Integer, FakeTask> tasks = new LinkedHashMap<>();
        tasks.put(1, new FakeTask(0L, false));
        tasks.put(2, new FakeTask(System.nanoTime(), false));
        ProgressiveTaskEviction.evict(tasks, System.nanoTime(), false, key -> false);
        assertFalse(tasks.containsKey(1), "idle unwatched task must expire");
        assertTrue(tasks.containsKey(2), "recently requested task stays");
    }

    @Test
    void watchedTasksNeverExpire() {
        final Map<Integer, FakeTask> tasks = new LinkedHashMap<>();
        tasks.put(1, new FakeTask(0L, true));
        ProgressiveTaskEviction.evict(tasks, System.nanoTime(), false, key -> true);
        assertEquals(1, tasks.size(), "watched tasks are pinned regardless of age");
    }

    @Test
    void capacityPressureEvictsCompletedUnwatchedTasksOnly() {
        final Map<Integer, FakeTask> tasks = new LinkedHashMap<>();
        for (int i = 1; i < ProgressivePolicy.MAX_ACTIVE_TILES; i++) {
            tasks.put(i, new FakeTask(System.nanoTime(), true));
        }
        tasks.put(0, new FakeTask(System.nanoTime(), false));
        ProgressiveTaskEviction.evict(tasks, System.nanoTime(), true, key -> key == 0);
        assertEquals(
            ProgressivePolicy.MAX_ACTIVE_TILES - 1,
            tasks.size(),
            "one completed unwatched task makes room"
        );
        assertFalse(tasks.containsKey(1), "the oldest completed unwatched task is the victim");
        assertTrue(tasks.containsKey(0), "incomplete watched tasks survive");
    }

    @Test
    void requestBytesSplitAttributesEveryByteOnce() {
        assertArrayEquals(new int[] {0}, RequestBytes.spread(0, 1));
        assertArrayEquals(new int[] {2, 1}, RequestBytes.spread(3, 2));
        assertArrayEquals(new int[] {342, 341, 341}, RequestBytes.spread(1_024, 3));
        assertArrayEquals(new int[] {0, 0}, RequestBytes.spread(-5, 2), "negative totals clamp to zero");
        assertArrayEquals(new int[0], RequestBytes.spread(100, 0));
    }

    @Test
    void captureBudgetFollowsTheConfiguredRateWithBounds() {
        assertEquals(8, LiveCapturePolicy.perTickCaptures(1_000_000), "ceiling applies");
        assertEquals(1, LiveCapturePolicy.perTickCaptures(1));
        assertEquals(1, LiveCapturePolicy.perTickCaptures(0), "configured zero still yields one");
        assertEquals(5, LiveCapturePolicy.perTickCaptures(100), "100/s is five per tick");
        assertEquals(6, LiveCapturePolicy.perTickCaptures(101), "fractional rates round up");
    }
}

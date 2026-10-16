package cn.net.rms.confluxmap.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FullSyncTrackerTest {
    @Test
    void emitsStartAndPeriodicFiveSecondFeedbackAndCompletion() {
        final List<String> messages = new ArrayList<>();
        final FullSyncTracker tracker = new FullSyncTracker(messages::add);

        final long start = 1_000_000_000L;
        tracker.start(100, start);
        assertTrue(tracker.isRunning());
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("开始全量同步：已扫描到 100 个区域"));

        // Progress at 2 seconds (not 5 yet) -> no extra message
        tracker.recordProgress(1024, start + 2_000_000_000L);
        assertEquals(1, messages.size());

        // Progress at 4.9 seconds -> still no extra message
        tracker.recordProgress(1024, start + 4_900_000_000L);
        assertEquals(1, messages.size());

        // Progress at 5.0 seconds -> emits second message
        tracker.recordProgress(1024, start + 5_000_000_000L);
        assertEquals(2, messages.size());
        assertTrue(messages.get(1).contains("全量同步进度：3/100 (3.0%)"));

        // Progress at 8 seconds -> no extra message
        tracker.recordProgress(1024, start + 8_000_000_000L);
        assertEquals(2, messages.size());

        // Progress at 10.1 seconds -> emits third message
        tracker.recordProgress(1024, start + 10_100_000_000L);
        assertEquals(3, messages.size());
        assertTrue(messages.get(2).contains("全量同步进度：5/100 (5.0%)"));

        // Fast-forward to 99 items
        for (int i = 6; i <= 99; i++) {
            tracker.recordProgress(1024, start + 11_000_000_000L);
        }
        assertEquals(3, messages.size());

        // Final item (100) -> emits completion summary
        tracker.recordProgress(1024, start + 15_000_000_000L);
        assertFalse(tracker.isRunning());
        assertEquals(4, messages.size());
        assertTrue(messages.get(3).contains("全量同步已完成：共同步 100 个区域"));
    }

    @Test
    void cancelEmitsFeedbackAndStopsRunning() {
        final List<String> messages = new ArrayList<>();
        final FullSyncTracker tracker = new FullSyncTracker(messages::add);
        tracker.start(50, 1_000_000_000L);
        assertTrue(tracker.isRunning());

        tracker.cancel();
        assertFalse(tracker.isRunning());
        assertEquals(2, messages.size());
        assertTrue(messages.get(1).contains("全量同步已取消"));
    }
}

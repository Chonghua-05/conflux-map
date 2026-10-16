package cn.net.rms.confluxmap.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FullSyncTrackerTest {
    private static FullSyncTracker.Feedback last(final List<FullSyncTracker.Feedback> messages) {
        return messages.get(messages.size() - 1);
    }

    @Test
    void emitsStartedAndPeriodicFiveSecondFeedbackAndCompletion() {
        final List<FullSyncTracker.Feedback> messages = new ArrayList<>();
        final FullSyncTracker tracker = new FullSyncTracker(messages::add);

        final long start = 1_000_000_000L;
        tracker.start(100, start);
        assertTrue(tracker.isRunning());
        assertEquals(1, messages.size());
        assertEquals("confluxmap.fullsync.started", messages.get(0).key());
        assertEquals("100", messages.get(0).args()[0]);

        tracker.recordProgress(1024, start + 2_000_000_000L);
        assertEquals(1, messages.size());
        tracker.recordProgress(1024, start + 4_900_000_000L);
        assertEquals(1, messages.size());

        tracker.recordProgress(1024, start + 5_000_000_000L);
        assertEquals(2, messages.size());
        assertEquals("confluxmap.fullsync.progress", messages.get(1).key());
        assertEquals("3", messages.get(1).args()[0]);
        assertEquals("100", messages.get(1).args()[1]);
        assertEquals("3.0", messages.get(1).args()[2]);

        tracker.recordProgress(1024, start + 8_000_000_000L);
        assertEquals(2, messages.size());

        tracker.recordProgress(1024, start + 10_100_000_000L);
        assertEquals(3, messages.size());
        assertEquals("5", messages.get(2).args()[0]);
        assertEquals("5.0", messages.get(2).args()[2]);

        for (int i = 6; i <= 99; i++) {
            tracker.recordProgress(1024, start + 11_000_000_000L);
        }
        assertEquals(3, messages.size());

        tracker.recordProgress(1024, start + 15_000_000_000L);
        assertFalse(tracker.isRunning());
        assertEquals(4, messages.size());
        assertEquals("confluxmap.fullsync.done", last(messages).key());
        assertEquals("100", last(messages).args()[0]);
        assertEquals("15.00", last(messages).args()[1]);
        // 100 patches of 1024 bytes = 0.09765625 MiB
        assertEquals("0.10", last(messages).args()[2]);
    }

    @Test
    void emptyDimensionEmitsEmptyAndNeverRuns() {
        final List<FullSyncTracker.Feedback> messages = new ArrayList<>();
        final FullSyncTracker tracker = new FullSyncTracker(messages::add);
        tracker.start(0, 1_000_000_000L);
        assertFalse(tracker.isRunning());
        assertEquals(1, messages.size());
        assertEquals("confluxmap.fullsync.empty", messages.get(0).key());
        assertEquals(0, messages.get(0).args().length);
    }

    @Test
    void exhaustedRetriesFinishWithSkippedCount() {
        final List<FullSyncTracker.Feedback> messages = new ArrayList<>();
        final FullSyncTracker tracker = new FullSyncTracker(messages::add);
        final long start = 1_000_000_000L;
        tracker.start(10, start);
        for (int i = 0; i < 7; i++) {
            tracker.recordProgress(512, start);
        }
        tracker.noteFailure(start + 1_000_000_000L);
        tracker.noteFailure(start + 2_000_000_000L);
        tracker.noteFailure(start + 3_000_000_000L);
        assertFalse(tracker.isRunning());
        assertEquals("confluxmap.fullsync.done_skipped", last(messages).key());
        assertEquals("7", last(messages).args()[0]);
        assertEquals("10", last(messages).args()[1]);
        assertEquals("3", last(messages).args()[2]);
    }

    @Test
    void cancelEmitsFeedbackAndStopsRunning() {
        final List<FullSyncTracker.Feedback> messages = new ArrayList<>();
        final FullSyncTracker tracker = new FullSyncTracker(messages::add);
        tracker.start(50, 1_000_000_000L);
        assertTrue(tracker.isRunning());

        tracker.cancel(2_000_000_000L);
        assertFalse(tracker.isRunning());
        assertEquals(2, messages.size());
        assertEquals("confluxmap.fullsync.cancelled", last(messages).key());
    }

    @Test
    void abandonIsSilentAndFailStalledReportsTheStall() {
        final List<FullSyncTracker.Feedback> messages = new ArrayList<>();
        final FullSyncTracker tracker = new FullSyncTracker(messages::add);
        final long start = 1_000_000_000L;
        tracker.start(50, start);

        assertFalse(tracker.isStalled(start + FullSyncTracker.STALL_TIMEOUT_NANOS - 1));
        assertTrue(tracker.isStalled(start + FullSyncTracker.STALL_TIMEOUT_NANOS));

        tracker.failStalled(start + FullSyncTracker.STALL_TIMEOUT_NANOS);
        assertFalse(tracker.isRunning());
        assertEquals(2, messages.size());
        assertEquals("confluxmap.fullsync.stalled", last(messages).key());

        tracker.start(50, start);
        tracker.abandon();
        assertFalse(tracker.isRunning());
        assertEquals(3, messages.size());
    }
}

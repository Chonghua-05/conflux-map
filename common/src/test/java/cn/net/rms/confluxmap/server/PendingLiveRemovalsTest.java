package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PendingLiveRemovalsTest {
    private static final int SCAN_INTERVAL = 20;
    private static final int EXPIRY = 600;

    /** mtime source whose region mtimes can be flipped per region key between scans. */
    private static final class ProbedMtime {
        private final Map<String, Long> mtimes = new HashMap<>();
        private int probes;

        long get(int worldIndex, int mcaX, int mcaZ) {
            probes++;
            return mtimes.getOrDefault(worldIndex + ":" + mcaX + ":" + mcaZ, 0L);
        }

        void set(int worldIndex, int mcaX, int mcaZ, long mtime) {
            mtimes.put(worldIndex + ":" + mcaX + ":" + mcaZ, mtime);
        }
    }

    private static final class Sink implements PendingLiveRemovals.RemovalSink {
        private final List<String> removed = new ArrayList<>();

        @Override
        public void removeLive(int worldIndex, int chunkX, int chunkZ) {
            removed.add(worldIndex + ":" + chunkX + ":" + chunkZ);
        }
    }

    private static PendingLiveRemovals tracker(final ProbedMtime mtime) {
        return new PendingLiveRemovals(mtime::get, SCAN_INTERVAL, EXPIRY, 512);
    }

    @Test
    void entryWithoutAwaitedWriteIsRemovedOnFirstScan() {
        final ProbedMtime mtime = new ProbedMtime();
        final PendingLiveRemovals tracker = tracker(mtime);
        final Sink sink = new Sink();
        tracker.onUnload(0, 10, 20, 5_000L, false, 1L);
        tracker.tick(SCAN_INTERVAL, sink);
        assertEquals(List.of("0:10:20"), sink.removed);
        assertTrue(tracker.isEmpty());
    }

    @Test
    void awaitedWriteIsRemovedOnceRegionMtimeMoves() {
        final ProbedMtime mtime = new ProbedMtime();
        // chunk (100, 200) lives in region file (3, 6).
        mtime.set(0, 3, 6, 5_000L);
        final PendingLiveRemovals tracker = tracker(mtime);
        final Sink sink = new Sink();
        tracker.onUnload(0, 100, 200, 5_000L, true, 1L);
        tracker.tick(SCAN_INTERVAL, sink);
        assertTrue(sink.removed.isEmpty());
        mtime.set(0, 3, 6, 6_000L);
        tracker.tick(2 * SCAN_INTERVAL, sink);
        assertEquals(List.of("0:100:200"), sink.removed);
    }

    @Test
    void stalledEntrySurvivesUntilDeadlineThenExpires() {
        final ProbedMtime mtime = new ProbedMtime();
        mtime.set(0, 3, 6, 5_000L);
        final PendingLiveRemovals tracker = tracker(mtime);
        final Sink sink = new Sink();
        tracker.onUnload(0, 100, 200, 5_000L, true, 1L);
        for (long tick = SCAN_INTERVAL; tick <= EXPIRY; tick += SCAN_INTERVAL) {
            tracker.tick(tick, sink);
        }
        assertTrue(sink.removed.isEmpty());
        tracker.tick(EXPIRY + SCAN_INTERVAL, sink);
        assertEquals(List.of("0:100:200"), sink.removed);
        assertTrue(tracker.isEmpty());
    }

    @Test
    void absentRegionFileStallsUntilDeadline() {
        final ProbedMtime mtime = new ProbedMtime();
        final PendingLiveRemovals tracker = tracker(mtime);
        final Sink sink = new Sink();
        tracker.onUnload(0, 100, 200, 0L, true, 1L);
        tracker.tick(SCAN_INTERVAL, sink);
        assertTrue(sink.removed.isEmpty());
        tracker.tick(EXPIRY + SCAN_INTERVAL, sink);
        assertEquals(List.of("0:100:200"), sink.removed);
    }

    @Test
    void reloadCancelsPendingEntryWithoutRemoval() {
        final ProbedMtime mtime = new ProbedMtime();
        final PendingLiveRemovals tracker = tracker(mtime);
        final Sink sink = new Sink();
        tracker.onUnload(0, 100, 200, 5_000L, true, 1L);
        tracker.onChunkReload(0, 100, 200);
        tracker.tick(1L + EXPIRY, sink);
        assertTrue(sink.removed.isEmpty());
        assertTrue(tracker.isEmpty());
    }

    @Test
    void chunksSharingARegionCostOneMtimeProbePerScan() {
        final ProbedMtime mtime = new ProbedMtime();
        mtime.set(0, 0, 0, 5_000L);
        final PendingLiveRemovals tracker = tracker(mtime);
        final Sink sink = new Sink();
        tracker.onUnload(0, 1, 1, 5_000L, true, 1L);
        tracker.onUnload(0, 2, 2, 5_000L, true, 1L);
        tracker.onUnload(0, 3, 3, 5_000L, true, 1L);
        tracker.tick(SCAN_INTERVAL, sink);
        assertEquals(1, mtime.probes);
        mtime.set(0, 0, 0, 6_000L);
        tracker.tick(2 * SCAN_INTERVAL, sink);
        assertEquals(2, mtime.probes);
        assertEquals(List.of("0:1:1", "0:2:2", "0:3:3"), sink.removed);
        assertTrue(tracker.isEmpty());
    }

    @Test
    void offIntervalTicksDoNotScan() {
        final ProbedMtime mtime = new ProbedMtime();
        final PendingLiveRemovals tracker = tracker(mtime);
        final Sink sink = new Sink();
        tracker.onUnload(0, 1, 1, 5_000L, false, 1L);
        for (long tick = 1; tick < SCAN_INTERVAL; tick++) {
            tracker.tick(tick, sink);
        }
        assertEquals(0, mtime.probes);
        assertTrue(sink.removed.isEmpty());
        tracker.tick(SCAN_INTERVAL, sink);
        assertEquals(1, mtime.probes);
    }

    @Test
    void reinsertedRegionDoesNotBreakTheScanIterator() {
        final ProbedMtime mtime = new ProbedMtime();
        // Two stalled regions; the first one survives its scan, so the scan must keep going
        // into the second region after requeueing the first.
        mtime.set(0, 0, 0, 5_000L);
        mtime.set(0, 1, 0, 5_000L);
        final PendingLiveRemovals tracker = tracker(mtime);
        final Sink sink = new Sink();
        tracker.onUnload(0, 1, 1, 5_000L, true, 1L);
        tracker.onUnload(0, 40, 1, 5_000L, true, 1L);
        tracker.tick(SCAN_INTERVAL, sink);
        assertEquals(2, mtime.probes);
        assertTrue(sink.removed.isEmpty());
    }

    @Test
    void scanCapRotatesRetainedRegionsWithoutStarvation() {
        final ProbedMtime mtime = new ProbedMtime();
        // Two stalled regions beyond a per-scan cap of one.
        mtime.set(0, 0, 0, 5_000L);
        mtime.set(0, 1, 0, 5_000L);
        final PendingLiveRemovals tracker = new PendingLiveRemovals(mtime::get, SCAN_INTERVAL, EXPIRY, 1);
        final Sink sink = new Sink();
        tracker.onUnload(0, 1, 1, 5_000L, true, 1L);
        tracker.onUnload(0, 40, 1, 5_000L, true, 1L);
        tracker.tick(SCAN_INTERVAL, sink);
        assertEquals(1, mtime.probes);
        assertTrue(sink.removed.isEmpty());
        // The second scan reaches the region the cap skipped on the first pass.
        tracker.tick(2 * SCAN_INTERVAL, sink);
        assertEquals(2, mtime.probes);
        assertTrue(sink.removed.isEmpty());
        // Rotation continues: scans three and four each reach the other region, so neither
        // is starved by the cap.
        tracker.tick(3 * SCAN_INTERVAL, sink);
        assertEquals(3, mtime.probes);
        tracker.tick(4 * SCAN_INTERVAL, sink);
        assertEquals(4, mtime.probes);
    }

    @Test
    void negativeChunkCoordinatesFloorIntoTheCorrectRegion() {
        final ProbedMtime mtime = new ProbedMtime();
        mtime.set(0, -1, -1, 5_000L);
        final PendingLiveRemovals tracker = tracker(mtime);
        final Sink sink = new Sink();
        tracker.onUnload(0, -1, -1, 5_000L, true, 1L);
        tracker.tick(SCAN_INTERVAL, sink);
        assertEquals(1, mtime.probes);
        mtime.set(0, -1, -1, 6_000L);
        tracker.tick(2 * SCAN_INTERVAL, sink);
        assertEquals(List.of("0:-1:-1"), sink.removed);
    }

    @Test
    void duplicateUnloadReplacesEntryInsteadOfDuplicatingIt() {
        final ProbedMtime mtime = new ProbedMtime();
        final PendingLiveRemovals tracker = tracker(mtime);
        final Sink sink = new Sink();
        tracker.onUnload(0, 1, 1, 5_000L, true, 1L);
        tracker.onUnload(0, 1, 1, 5_000L, true, 1L);
        tracker.tick(EXPIRY + SCAN_INTERVAL, sink);
        assertEquals(List.of("0:1:1"), sink.removed);
    }
}

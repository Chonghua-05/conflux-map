package cn.net.rms.confluxmap.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Compares full-chunk recapture counts under a sustained redstone-style load - every machine
 * chunk re-marked each tick, the way flickering redstone drives block-update plus light-update
 * packets - against the ungated baseline. The baseline run uses a zero window, which is exactly
 * the previous behavior: every packet mark re-enters the queues and every tick's budget is spent
 * resampling the same few chunks. The gated run uses the default shipped window.
 */
@Tag("benchmark")
class CaptureHoldoffRedstoneBenchmarkTest {
    private static final int MACHINE_CHUNKS = 8;
    private static final int BUDGET_PER_TICK = 8;
    private static final int TOTAL_TICKS = 200;
    private static final int MACHINE_STOP_TICK = 150;
    private static final int DEFAULT_HOLDOFF = 10;

    @Test
    void measureRecapturesUnderSustainedFlicker() {
        final Run baseline = simulate(0);
        final Run gated = simulate(DEFAULT_HOLDOFF);

        // Ungated, the budget is fully spent on the machine every tick it runs.
        assertEquals(baseline.ticks, TOTAL_TICKS);
        assertEquals((long) MACHINE_CHUNKS * MACHINE_STOP_TICK, baseline.captures);
        assertEquals(baseline.lastCaptureTick, MACHINE_STOP_TICK - 1);

        // Gated: one immediate initial capture per chunk, then one per hold-off window.
        assertTrue(gated.captures <= baseline.captures / 6,
            "gated captures " + gated.captures + " should collapse the recapture loop");
        // The flicker's final state still lands: the last deferred mark matures within one
        // window of the machine stopping, instead of being dropped.
        assertTrue(gated.lastCaptureTick >= MACHINE_STOP_TICK,
            "last deferred change must eventually be captured");
        assertTrue(gated.lastCaptureTick <= MACHINE_STOP_TICK + DEFAULT_HOLDOFF + 1);

        System.out.println();
        System.out.printf("%-42s %10s%n", "redstone flicker, " + MACHINE_CHUNKS + " chunks x "
            + MACHINE_STOP_TICK + " ticks", "captures");
        System.out.printf("%-42s %10d%n", "baseline (holdoff=0)", baseline.captures);
        System.out.printf("%-42s %10d%n", "gated (holdoff=" + DEFAULT_HOLDOFF + ")", gated.captures);
        System.out.printf("reduction %.1f%%, last-change lag %d ticks%n",
            100.0 * (1.0 - (double) gated.captures / baseline.captures),
            gated.lastCaptureTick - (MACHINE_STOP_TICK - 1));
    }

    @Test
    void sporadicMiningPaceChangesIncurNoHoldoffDelay() {
        final CaptureHoldoffGate gate = new CaptureHoldoffGate(DEFAULT_HOLDOFF);
        final ArrayDeque<Long> dirty = new ArrayDeque<>();
        final HashSet<Long> queued = new HashSet<>();
        final long chunk = key(7, 7);
        int changes = 0;
        int captures = 0;
        for (int tick = 0; tick < TOTAL_TICKS; tick++) {
            // One isolated change every 30 ticks - mining pace, far beyond any hold-off window.
            if (tick % 30 == 0 && mark(gate, dirty, queued, chunk)) {
                changes++;
            }
            for (final long matured : gate.tick()) {
                mark(gate, dirty, queued, matured);
            }
            if (!dirty.isEmpty()) {
                dirty.pollFirst();
                queued.remove(chunk);
                gate.recordCaptured(chunk);
                captures++;
            }
            assertTrue(dirty.isEmpty(), "tick " + tick + " ended with the change still queued");
        }
        assertEquals(changes, captures);
    }

    private static Run simulate(final int holdoffTicks) {
        final CaptureHoldoffGate gate = new CaptureHoldoffGate(holdoffTicks);
        final ArrayDeque<Long> dirty = new ArrayDeque<>();
        final HashSet<Long> queued = new HashSet<>();
        final Run run = new Run();
        for (int tick = 0; tick < TOTAL_TICKS; tick++) {
            // Packet phase: while the machine runs, block and light packets re-mark every
            // machine chunk between ticks; both marks collapse into one queue entry.
            if (tick < MACHINE_STOP_TICK) {
                for (int i = 0; i < MACHINE_CHUNKS; i++) {
                    mark(gate, dirty, queued, key(-1 + (i >> 2), i & 3));
                }
            }
            // Capture phase, ordered as ChunkCaptureService.tick(): mature deferred marks first,
            // then drain up to the snapshot budget.
            for (final long matured : gate.tick()) {
                mark(gate, dirty, queued, matured);
            }
            for (int drained = 0; drained < BUDGET_PER_TICK && !dirty.isEmpty(); drained++) {
                final long chunk = dirty.pollFirst();
                queued.remove(chunk);
                gate.recordCaptured(chunk);
                run.captures++;
                run.lastCaptureTick = tick;
            }
            run.ticks++;
        }
        return run;
    }

    private static boolean mark(
        final CaptureHoldoffGate gate,
        final ArrayDeque<Long> dirty,
        final HashSet<Long> queued,
        final long chunkKey
    ) {
        if (gate.tryMark(chunkKey) && queued.add(chunkKey)) {
            dirty.addLast(chunkKey);
            return true;
        }
        return false;
    }

    private static long key(final int chunkX, final int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static final class Run {
        long ticks;
        long captures;
        long lastCaptureTick = -1;
    }
}

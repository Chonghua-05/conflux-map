package cn.net.rms.confluxmap.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CaptureHoldoffGateTest {
    private static final long CHUNK = key(3, -7);

    @Test
    void zeroWindowPassesEveryMarkThrough() {
        final CaptureHoldoffGate gate = new CaptureHoldoffGate(0);
        assertTrue(gate.tryMark(CHUNK));
        gate.recordCaptured(CHUNK);
        assertTrue(gate.tryMark(CHUNK));
        assertEquals(List.of(), gate.tick());
        assertTrue(gate.tryMark(CHUNK));
    }

    @Test
    void firstMarkOnANeverCapturedChunkIsImmediate() {
        final CaptureHoldoffGate gate = new CaptureHoldoffGate(10);
        assertTrue(gate.tryMark(CHUNK));
    }

    @Test
    void markInsideTheWindowIsDeferredAndMaturesOnTheExpiryTick() {
        final CaptureHoldoffGate gate = new CaptureHoldoffGate(3);
        gate.recordCaptured(CHUNK);
        gate.tick();
        assertFalse(gate.tryMark(CHUNK));
        gate.tick();
        assertFalse(gate.tryMark(CHUNK));
        // Captured at tick 0; the window expires as the gate reaches tick 3.
        assertEquals(List.of(CHUNK), gate.tick());
        assertTrue(gate.tryMark(CHUNK), "tracking is gone with the matured entry");
    }

    @Test
    void freshMarkAfterAnExpiredWindowIsImmediate() {
        final CaptureHoldoffGate gate = new CaptureHoldoffGate(2);
        gate.recordCaptured(CHUNK);
        gate.tick();
        gate.tick();
        assertTrue(gate.tryMark(CHUNK));
    }

    @Test
    void expiredWindowsWithoutDeferredMarksReleaseNoKeys() {
        final CaptureHoldoffGate gate = new CaptureHoldoffGate(2);
        gate.recordCaptured(key(0, 0));
        gate.recordCaptured(key(1, 0));
        gate.tick();
        // Only (1,0) was re-marked inside its window; (0,0) releases silently.
        assertFalse(gate.tryMark(key(1, 0)));
        assertEquals(List.of(key(1, 0)), gate.tick());
    }

    @Test
    void chunksAreTrackedIndependently() {
        final CaptureHoldoffGate gate = new CaptureHoldoffGate(5);
        final long other = key(0, 0);
        gate.recordCaptured(CHUNK);
        gate.recordCaptured(other);
        assertFalse(gate.tryMark(CHUNK));
        assertFalse(gate.tryMark(other));
        assertTrue(gate.tryMark(key(5, 5)), "untouched chunk is not affected");
    }

    @Test
    void clearDropsAllSuppression() {
        final CaptureHoldoffGate gate = new CaptureHoldoffGate(10);
        gate.recordCaptured(CHUNK);
        assertFalse(gate.tryMark(CHUNK));
        gate.clear();
        assertTrue(gate.tryMark(CHUNK));
    }

    @Test
    void configuringFromZeroEnablesSuppressionForNewCaptures() {
        final CaptureHoldoffGate gate = new CaptureHoldoffGate(0);
        gate.recordCaptured(CHUNK);
        assertTrue(gate.tryMark(CHUNK));
        gate.configure(4);
        assertTrue(gate.tryMark(CHUNK), "no capture was tracked while disabled");
        gate.recordCaptured(CHUNK);
        gate.tick();
        assertFalse(gate.tryMark(CHUNK));
    }

    @Test
    void configuringToZeroDropsAllSuppression() {
        final CaptureHoldoffGate gate = new CaptureHoldoffGate(10);
        gate.recordCaptured(CHUNK);
        assertFalse(gate.tryMark(CHUNK));
        gate.configure(0);
        assertTrue(gate.tryMark(CHUNK));
        assertEquals(List.of(), gate.tick());
    }

    private static long key(final int chunkX, final int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}

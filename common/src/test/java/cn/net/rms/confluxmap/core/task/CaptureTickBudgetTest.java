package cn.net.rms.confluxmap.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CaptureTickBudgetTest {
    @Test
    void fastVisibleCaptureCanFinishAWholeDefaultViewportInOneTick() {
        final AtomicLong clock = new AtomicLong();
        final CaptureTickBudget budget = CaptureTickBudget.visible(8, clock::get);

        int captured = 0;
        while (budget.canCapture(captured)) {
            captured++;
            clock.addAndGet(100_000L);
        }

        assertEquals(120, captured);
    }

    @Test
    void slowVisibleCaptureKeepsConfiguredProgressWithoutRunningUnbounded() {
        final AtomicLong clock = new AtomicLong();
        final CaptureTickBudget budget = CaptureTickBudget.visible(8, clock::get);

        int captured = 0;
        while (budget.canCapture(captured)) {
            captured++;
            clock.addAndGet(2_000_000L);
        }

        assertEquals(8, captured);
    }

    @Test
    void visibleCaptureHasAHardSnapshotCapEvenWithAnInvalidConfiguration() {
        final CaptureTickBudget budget = CaptureTickBudget.visible(1_000, () -> 0L);

        int captured = 0;
        while (budget.canCapture(captured)) {
            captured++;
        }

        assertEquals(256, captured);
    }

    @Test
    void resultFinalizationAndCaptureShareOneTickDeadline() {
        final AtomicLong clock = new AtomicLong();
        final CaptureTickBudget budget = CaptureTickBudget.visible(8, clock::get);

        assertEquals(true, budget.canFinish(0));
        clock.addAndGet(12_000_000L);

        assertEquals(false, budget.canFinish(1));
        assertEquals(false, budget.canCapture(0, true));
    }

    @Test
    void windowScalesWithTheConfiguredBudgetAndSaturates() {
        assertEquals(1_500_000L, CaptureTickBudget.windowNanos(1));
        assertEquals(1_500_000L, CaptureTickBudget.windowNanos(0));
        assertEquals(12_000_000L, CaptureTickBudget.windowNanos(8));
        assertEquals(24_000_000L, CaptureTickBudget.windowNanos(16));
        assertEquals(24_000_000L, CaptureTickBudget.windowNanos(64));
    }

    @Test
    void loweredBudgetShrinksEffectiveThroughputOnAFastMachine() {
        final AtomicLong clock = new AtomicLong();
        final CaptureTickBudget budget = CaptureTickBudget.visible(1, clock::get);

        int captured = 0;
        while (budget.canCapture(captured)) {
            captured++;
            clock.addAndGet(100_000L);
        }

        assertEquals(15, captured);
    }

    @Test
    void raisedBudgetExtendsTheBurstInsteadOfStallingAtTheConfiguredFloor() {
        final AtomicLong clock = new AtomicLong();
        final CaptureTickBudget budget = CaptureTickBudget.visible(64, clock::get);

        int captured = 0;
        while (budget.canCapture(captured)) {
            captured++;
            clock.addAndGet(100_000L);
        }

        assertEquals(240, captured);
    }

    @Test
    void raisedBudgetOnASlowTickStopsAtTheWindowInsteadOfTheFullBudget() {
        final AtomicLong clock = new AtomicLong();
        final CaptureTickBudget budget = CaptureTickBudget.visible(64, clock::get);

        int captured = 0;
        while (budget.canCapture(captured)) {
            captured++;
            clock.addAndGet(2_000_000L);
        }

        assertEquals(12, captured);
    }

}

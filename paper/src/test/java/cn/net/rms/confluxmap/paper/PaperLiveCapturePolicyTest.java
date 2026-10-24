package cn.net.rms.confluxmap.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PaperLiveCapturePolicyTest {
    @Test
    void firstCaptureGoesThroughImmediately() {
        assertTrue(
            PaperLiveCapturePolicy.shouldScanCapture(false, 0L),
            "a chunk with no live summary must be captured on demand without waiting"
        );
    }

    @Test
    void recaptureWaitsForTheFreshnessInterval() {
        assertFalse(
            PaperLiveCapturePolicy.shouldScanCapture(true, PaperLiveCapturePolicy.RECAPTURE_INTERVAL_TICKS - 1L),
            "a summary captured within the freshness interval must not be recaptured"
        );
        assertTrue(
            PaperLiveCapturePolicy.shouldScanCapture(true, PaperLiveCapturePolicy.RECAPTURE_INTERVAL_TICKS),
            "a summary at least one freshness interval old must be recaptured"
        );
    }

    @Test
    void summaryWithoutTickBookkeepingStillRecaptures() {
        assertTrue(
            PaperLiveCapturePolicy.shouldScanCapture(true, Long.MAX_VALUE),
            "a summary that lost its capture-tick record must refresh instead of going stale forever"
        );
    }
}

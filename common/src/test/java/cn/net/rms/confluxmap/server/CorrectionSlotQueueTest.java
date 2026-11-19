package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.ErrorS2C;
import cn.net.rms.confluxmap.core.net.MapPatchS2C;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.MsgCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ProtoException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CorrectionSlotQueueTest {
    private static final long T0 = 1_000_000_000L;

    @Test
    void budgetExhaustionQueuesTheTailInsteadOfDroppingIt() throws ProtoException {
        final int bodyBytes = 200_000;
        final int patchBytes = MsgCodec.encode(patch(0, 0, 1L, bodyBytes)).length;
        final PlayerBudget budget = new PlayerBudget(2 * patchBytes + 10, 0);
        final CorrectionSlotQueue<Integer> queue = new CorrectionSlotQueue<>(16, Runnable::run);
        // The token bucket anchors its refill clock to System.nanoTime() at construction, so the
        // simulated timeline must start after that instant.
        final long t0 = System.nanoTime();
        assertTrue(queue.enqueue(0, () -> response(0, 0, 1L, bodyBytes)));
        assertTrue(queue.enqueue(1, () -> response(1, 0, 1L, bodyBytes)));
        assertTrue(queue.enqueue(2, () -> response(2, 0, 1L, bodyBytes)));

        final List<MapPatchS2C> sent = new ArrayList<>();
        queue.drain(budget, collecting(sent), new SyncPerformanceMonitor(), t0);
        assertEquals(2, sent.size(), "bucket holds exactly two patches");
        assertEquals(1, queue.size(), "third tile must stay queued, not be dropped");

        queue.drain(budget, collecting(sent), new SyncPerformanceMonitor(), t0);
        assertEquals(2, sent.size(), "no tokens yet, nothing more may be sent");

        queue.drain(budget, collecting(sent), new SyncPerformanceMonitor(), t0 + 1_000_000_000L);
        assertEquals(3, sent.size(), "refilled bucket delivers the queued tail");
        assertEquals(0, queue.size());
        assertEquals(
            List.of(0, 1, 2),
            sent.stream().map(MapPatchS2C::tileX).toList(),
            "strict FIFO order"
        );
    }

    @Test
    void oneLegalPatchLargerThanThePerSecondRateCanDrain() throws ProtoException {
        final int bytesPerSecond = 65_536;
        final int bodyBytes = 400_000;
        final int wireBytes = MsgCodec.encode(patch(0, 0, 1L, bodyBytes)).length;
        final PlayerBudget budget = new PlayerBudget(bytesPerSecond, 0);
        final CorrectionSlotQueue<Integer> queue = new CorrectionSlotQueue<>(16, Runnable::run);
        final long t0 = System.nanoTime();
        queue.enqueue(0, () -> response(0, 0, 1L, bodyBytes));
        queue.enqueue(1, () -> response(1, 0, 1L, bodyBytes));

        final List<MapPatchS2C> sent = new ArrayList<>();
        queue.drain(budget, collecting(sent), new SyncPerformanceMonitor(), t0);
        assertEquals(1, sent.size(), "the initial burst must fit one legal atomic patch");
        assertEquals(1, queue.size());

        queue.drain(budget, collecting(sent), new SyncPerformanceMonitor(), t0 + 1_000_000_000L);
        assertEquals(1, sent.size(), "the configured sustained rate must still apply");

        final long refillNanos = (long) Math.ceil((double) wireBytes / bytesPerSecond * 1_000_000_000d);
        queue.drain(budget, collecting(sent), new SyncPerformanceMonitor(), t0 + refillNanos);
        assertEquals(2, sent.size(), "the queued patch must drain after its byte cost refills");
        assertEquals(0, queue.size());
    }

    @Test
    void resubmittedTileSupersedesInPlaceWithTheNewestRevision() {
        final CorrectionSlotQueue<Integer> queue = new CorrectionSlotQueue<>(16, Runnable::run);
        queue.enqueue(5, () -> response(5, 0, 1L, 8));
        queue.enqueue(5, () -> response(5, 0, 7L, 8));
        assertEquals(1, queue.size(), "same tile must not occupy two queue slots");

        final List<MapPatchS2C> sent = new ArrayList<>();
        queue.drain(new PlayerBudget(1 << 20, 0), collecting(sent), new SyncPerformanceMonitor(), T0);
        assertEquals(1, sent.size());
        assertEquals(7L, sent.get(0).tileRevision(), "resubmit must replace the stale build in place");
    }

    @Test
    void overflowRejectsOnlyTheExcess() {
        final CorrectionSlotQueue<Integer> queue = new CorrectionSlotQueue<>(2, Runnable::run);
        assertTrue(queue.enqueue(0, () -> response(0, 0, 1L, 8)));
        assertTrue(queue.enqueue(1, () -> response(1, 0, 1L, 8)));
        assertFalse(queue.enqueue(2, () -> response(2, 0, 1L, 8)));
        assertEquals(2, queue.size());
    }

    @Test
    void defaultQueueAcceptsOneNormalSubscribedViewport() {
        final ServerConfig config = new ServerConfig();
        final CorrectionSlotQueue<Integer> queue = new CorrectionSlotQueue<>(
            config.maxPendingTilesPerPlayer, Runnable::run
        );
        for (int tile = 0; tile < Proto.MAX_MAP_SYNC_VIEW_TILES; tile++) {
            final int tileX = tile;
            assertTrue(queue.enqueue(tile, () -> response(tileX, 0, 1L, 8)));
        }
        assertEquals(Proto.MAX_MAP_SYNC_VIEW_TILES, queue.size());
    }

    @Test
    void unencodableResponseDegradesToAClientErrorInsteadOfWedgingTheQueue() {
        final CorrectionSlotQueue<Integer> queue = new CorrectionSlotQueue<>(16, Runnable::run);
        queue.enqueue(0, () -> EncodedResponse.of(
            // Presence shorter than the protocol frame is unencodable.
            new MapPatchS2C(1, 0, 0, 0, 0, Proto.PATCH_MODE_ABSOLUTE, 1L, new byte[1], new byte[0]),
            0, 0, 0, 0, Proto.PATCH_MODE_ABSOLUTE, 10L, 20, 0L, 0L, 0L,
            SyncPerformanceMonitor.CumulativeWork.NONE
        ));
        queue.enqueue(1, () -> response(1, 0, 1L, 8));

        final List<Message> sent = new ArrayList<>();
        queue.drain(
            new PlayerBudget(1 << 20, 0),
            (message, payload) -> sent.add(message),
            new SyncPerformanceMonitor(),
            T0
        );
        assertEquals(2, sent.size(), "the healthy item behind the broken one must still be sent");
        assertEquals(ErrorS2C.ERR_MALFORMED_REQUEST, ((ErrorS2C) sent.get(0)).code());
        assertEquals(1, ((MapPatchS2C) sent.get(1)).tileX());
        assertEquals(0, queue.size());
    }

    @Test
    void drainMapsEncodedMeasurementsOntoTheDeliveryRecord() throws ProtoException {
        final MapPatchS2C patch = patch(4, 5, 1L, 8);
        final byte[] payload = MsgCodec.encode(patch);
        final EncodedResponse encoded = new EncodedResponse(
            patch, payload, 0, 2, 4, 5, Proto.PATCH_MODE_ABSOLUTE, 10L, 30L, 20,
            30L, 40L, 50L, SyncPerformanceMonitor.CumulativeWork.NONE
        );
        final CorrectionSlotQueue<Integer> queue = new CorrectionSlotQueue<>(16, Runnable::run);
        queue.enqueueCompleted(4, () -> encoded);

        final SyncPerformanceMonitor performance = new SyncPerformanceMonitor();
        queue.drain(new PlayerBudget(1 << 20, 0), (message, bytes) -> {
            assertEquals(payload.length, bytes.length);
        }, performance, T0);

        final List<SyncPerformanceMonitor.LodSnapshot> snapshots = performance.snapshots();
        assertEquals(1, snapshots.size());
        final SyncPerformanceMonitor.LodSnapshot snapshot = snapshots.get(0);
        assertEquals(1, snapshot.samples(), "one delivery must be recorded for the LOD");
        assertEquals(20L, snapshot.averageQueueNanos(), "queue time is build start minus receive");
        assertEquals(30L, snapshot.averageIoNanos());
        assertEquals(40L, snapshot.averageComputeNanos());
        assertEquals(50L, snapshot.averageEncodeNanos());
        assertEquals(20 + payload.length, snapshot.averageTrafficBytes());
    }

    @Test
    void immediateResultsAreDeliveredWithoutAWorkerRoundTrip() {
        final CorrectionSlotQueue<Integer> queue = new CorrectionSlotQueue<>(16, Runnable::run);
        final AtomicInteger builds = new AtomicInteger();
        assertTrue(queue.enqueueCompleted(3, () -> {
            builds.incrementAndGet();
            return response(3, 0, 1L, 8);
        }));

        final List<MapPatchS2C> sent = new ArrayList<>();
        queue.drain(new PlayerBudget(1 << 20, 0), collecting(sent), new SyncPerformanceMonitor(), T0);
        assertEquals(1, sent.size());
        assertEquals(1, builds.get());
        assertEquals(0, queue.size());
    }

    @Test
    void liveChangeRestartRebuildsCompletedResultsBeforeTheyAreSent() {
        final CorrectionSlotQueue<Integer> queue = new CorrectionSlotQueue<>(16, Runnable::run);
        final AtomicInteger builds = new AtomicInteger();
        queue.enqueue(9, () -> {
            builds.incrementAndGet();
            return response(9, 0, builds.get(), 8);
        });
        assertEquals(1, builds.get());
        queue.restartMatching(key -> key == 9);
        assertEquals(2, builds.get(), "restart must resubmit the async job");

        final List<MapPatchS2C> sent = new ArrayList<>();
        queue.drain(new PlayerBudget(1 << 20, 0), collecting(sent), new SyncPerformanceMonitor(), T0);
        assertEquals(2L, sent.get(0).tileRevision(), "the rebuilt generation is what gets delivered");
    }

    private static CorrectionSlotQueue.Sender collecting(final List<MapPatchS2C> sent) {
        return (message, payload) -> sent.add((MapPatchS2C) message);
    }

    private static EncodedResponse response(
        final int tileX, final int tileZ, final long revision, final int bodyBytes
    ) {
        return EncodedResponse.of(
            patch(tileX, tileZ, revision, bodyBytes),
            0, 0, tileX, tileZ, Proto.PATCH_MODE_ABSOLUTE, 1L, 0,
            0L, 0L
        );
    }

    private static MapPatchS2C patch(
        final int tileX, final int tileZ, final long revision, final int bodyBytes
    ) {
        return new MapPatchS2C(
            1, 0, 0, tileX, tileZ,
            Proto.PATCH_MODE_ABSOLUTE, revision, new byte[Proto.PATCH_PRESENCE_BYTES],
            new byte[bodyBytes]
        );
    }
}

package cn.net.rms.confluxmap.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PaperLiveChunkRotationTest {
    @Test
    void spreadsVisitsEvenlyAcrossEveryTrackedChunk() {
        final PaperLiveChunkRotation<String> rotation = new PaperLiveChunkRotation<>();
        rotation.add("a");
        rotation.add("b");
        rotation.add("c");

        assertEquals("a", rotation.next());
        assertEquals("b", rotation.next());
        assertEquals("c", rotation.next());
        assertEquals("a", rotation.next());
    }

    @Test
    void retrackingAChunkKeepsItsPlaceInsteadOfDuplicatingIt() {
        final PaperLiveChunkRotation<String> rotation = new PaperLiveChunkRotation<>();
        rotation.add("a");
        rotation.add("b");
        rotation.add("a");

        assertEquals(2, rotation.size());
        assertEquals("a", rotation.next());
        assertEquals("b", rotation.next());
        assertEquals("a", rotation.next());
    }

    @Test
    void dropsChunksUnloadedWhileTheyWaitInTheRotation() {
        final PaperLiveChunkRotation<String> rotation = new PaperLiveChunkRotation<>();
        rotation.add("a");
        rotation.add("b");
        rotation.remove("a");

        assertFalse(rotation.contains("a"));
        assertEquals("b", rotation.next());
        assertEquals("b", rotation.next());
    }

    @Test
    void reportsNothingOnceEveryChunkIsGone() {
        final PaperLiveChunkRotation<String> rotation = new PaperLiveChunkRotation<>();
        assertNull(rotation.next());

        rotation.add("a");
        rotation.remove("a");

        assertEquals(0, rotation.size());
        assertNull(rotation.next());
    }

    @Test
    void clearEmptiesBothTheIndexAndTheRotation() {
        final PaperLiveChunkRotation<String> rotation = new PaperLiveChunkRotation<>();
        rotation.add("a");
        rotation.add("b");
        rotation.clear();

        assertEquals(0, rotation.size());
        assertNull(rotation.next());
    }

    @Test
    void tracksConcurrentAddsWithoutLosingKeys() throws InterruptedException {
        final PaperLiveChunkRotation<Integer> rotation = new PaperLiveChunkRotation<>();
        final int threads = 4;
        final int perThread = 256;
        final Thread[] workers = new Thread[threads];
        for (int worker = 0; worker < threads; worker++) {
            final int offset = worker * perThread;
            workers[worker] = new Thread(() -> {
                for (int index = 0; index < perThread; index++) {
                    rotation.add(offset + index);
                }
            });
            workers[worker].start();
        }
        for (final Thread worker : workers) {
            worker.join();
        }

        assertEquals(threads * perThread, rotation.size());
        for (int visit = 0; visit < threads * perThread; visit++) {
            assertTrue(rotation.contains(rotation.next()));
        }
    }
}

package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.Message;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Per-player FIFO queue of correction slots. Each key (a tile or region page) owns one slot whose
 * build runs on the shared worker pool and whose newest submitted generation supersedes any older
 * one, so a re-request or a live-chunk change never delivers stale results. The owning server
 * thread never builds: {@link #drain} only applies the player's byte budget to already-encoded
 * results and sends, strictly head-first, so one large patch cannot be starved by cheaper items
 * behind it.
 *
 * <p>Thread-safe: requests may enqueue from network or HTTP threads while the server tick drains.
 */
public final class CorrectionSlotQueue<K> {
    private static final Logger LOGGER = LogManager.getLogger(CorrectionSlotQueue.class);

    @FunctionalInterface
    public interface Sender {
        void sendEncoded(Message message, byte[] payload);
    }

    private static final class JobSlot {
        volatile EncodedResponse completed;
        SupersedingTask<EncodedResponse> worker;
        Callable<EncodedResponse> asyncJob;
        Supplier<EncodedResponse> immediateJob;
    }

    private final int maxPending;
    private final Executor workers;
    private final Map<K, JobSlot> queue = new LinkedHashMap<>();

    public CorrectionSlotQueue(final int maxPending, final Executor workers) {
        this.maxPending = Math.max(1, maxPending);
        this.workers = workers;
    }

    /**
     * Queues an asynchronous build. The job runs on the worker pool; a newer submission for the
     * same key supersedes it. Returns false when the queue is full or the pool rejected the task.
     */
    public synchronized boolean enqueue(final K key, final Callable<EncodedResponse> job) {
        JobSlot slot = queue.get(key);
        if (slot == null) {
            if (queue.size() >= maxPending) {
                return false;
            }
            slot = new JobSlot();
            slot.worker = new SupersedingTask<>(workers);
            queue.put(key, slot);
        }
        slot.completed = null;
        slot.asyncJob = job;
        slot.immediateJob = null;
        if (!slot.worker.submit(job)) {
            if (queue.get(key) == slot) {
                queue.remove(key);
            }
            return false;
        }
        return true;
    }

    /**
     * Queues a result computed inline on the calling thread (progressive responses answered from
     * an already-scanned cache). Replaces any pending async build for the same key.
     */
    public synchronized boolean enqueueCompleted(final K key, final Supplier<EncodedResponse> job) {
        JobSlot slot = queue.get(key);
        if (slot == null) {
            if (queue.size() >= maxPending) {
                return false;
            }
            slot = new JobSlot();
            queue.put(key, slot);
        }
        slot.asyncJob = null;
        slot.immediateJob = job;
        slot.completed = job.get();
        return true;
    }

    public synchronized boolean isEmpty() {
        return queue.isEmpty();
    }

    public synchronized int size() {
        return queue.size();
    }

    public synchronized void clear() {
        queue.clear();
    }

    /**
     * Rebuilds every slot whose key matches, dropping results that a live-chunk change just made
     * stale. Immediate jobs recompute inline; async jobs are resubmitted and supersede any build
     * still in flight.
     */
    public synchronized void restartMatching(final Predicate<K> keyMatcher) {
        for (final Map.Entry<K, JobSlot> entry : queue.entrySet()) {
            if (!keyMatcher.test(entry.getKey())) {
                continue;
            }
            final JobSlot slot = entry.getValue();
            if (slot.immediateJob != null) {
                slot.completed = slot.immediateJob.get();
                continue;
            }
            slot.completed = null;
            if (slot.worker != null && slot.asyncJob != null && !slot.worker.submit(slot.asyncJob)) {
                LOGGER.warn("[ConfluxMap] companion: correction worker rejected a live-change restart");
            }
        }
    }

    /**
     * Sends completed results while the byte budget allows, head first. An unaffordable or still
     * building head stays queued and stops the drain; failed builds are dropped with a log line.
     */
    public synchronized void drain(
        final PlayerBudget budget,
        final Sender sender,
        final SyncPerformanceMonitor performance,
        final long nowNanos
    ) {
        final Iterator<Map.Entry<K, JobSlot>> iterator = queue.entrySet().iterator();
        while (iterator.hasNext()) {
            final JobSlot slot = iterator.next().getValue();
            final Exception failure = slot.worker == null ? null : slot.worker.failure();
            if (failure != null) {
                LOGGER.warn("[ConfluxMap] companion: correction job failed", failure);
                iterator.remove();
                continue;
            }
            final EncodedResponse completed = slot.completed != null
                ? slot.completed
                : slot.worker == null ? null : slot.worker.completed();
            if (completed == null
                || !budget.allowBytes(completed.payload().length, nowNanos)) {
                return;
            }
            sender.sendEncoded(completed.message(), completed.payload());
            iterator.remove();
            final long delivered = System.nanoTime();
            performance.record(new SyncPerformanceMonitor.Delivery(
                completed.dimIndex(), completed.lod(), completed.tileX(), completed.tileZ(),
                completed.receivedNanos(), completed.requestBytes(), completed.patchMode(),
                completed.payload().length,
                Math.max(0L, completed.buildStartedNanos() - completed.receivedNanos()),
                completed.encodeNanos(),
                delivered,
                new SyncPerformanceMonitor.DirectWork(
                    completed.ioNanos(), completed.computeNanos()
                ),
                completed.cumulativeWork()
            ));
        }
    }
}

package cn.net.rms.confluxmap.mc.radar;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Runs source-texture decoding and alpha inspection away from the render thread. Completed results
 * retain only projected geometry and compact bounds; the decoded source image is always closed by
 * the worker.
 */
final class PortraitTextureLoader<K> {
    interface SourceImage extends AutoCloseable {
        int width();

        int height();

        int alphaAt(int x, int y);

        @Override
        void close();
    }

    record Result<K>(
        K key,
        float[] geometry,
        int[] visibleBounds,
        int visibleArea,
        RuntimeException error
    ) {
        boolean success() {
            return error == null;
        }
    }

    private final Executor executor;
    private final Set<K> inFlight = new HashSet<>();
    private final ArrayDeque<Result<K>> completed = new ArrayDeque<>();
    private int generation;

    PortraitTextureLoader(final Executor executor) {
        this.executor = executor;
    }

    synchronized boolean request(
        final K key,
        final float[] geometry,
        final Supplier<SourceImage> image
    ) {
        if (!inFlight.add(key)) {
            return false;
        }
        final int taskGeneration = generation;
        try {
            executor.execute(() -> inspect(taskGeneration, key, geometry, image));
        } catch (final RejectedExecutionException e) {
            inFlight.remove(key);
            completed.addLast(new Result<>(key, geometry, null, 0, e));
        }
        return true;
    }

    synchronized Optional<Result<K>> poll() {
        return Optional.ofNullable(completed.pollFirst());
    }

    synchronized boolean isLoading(final K key) {
        return inFlight.contains(key);
    }

    synchronized void clear() {
        generation++;
        inFlight.clear();
        completed.clear();
    }

    private void inspect(
        final int taskGeneration,
        final K key,
        final float[] geometry,
        final Supplier<SourceImage> imageSupplier
    ) {
        Result<K> result;
        try (SourceImage image = imageSupplier.get()) {
            final EntityIconManager.VisiblePixels visible = EntityIconManager.visiblePixels(
                geometry, image.width(), image.height(), image::alphaAt
            );
            result = new Result<>(
                key,
                geometry,
                visible == null ? null : visible.bounds(),
                visible == null ? 0 : visible.area(),
                null
            );
        } catch (final RuntimeException e) {
            result = new Result<>(key, geometry, null, 0, e);
        }
        synchronized (this) {
            if (taskGeneration != generation) {
                // Retired by clear(): this task belongs to an earlier session and its key
                // may already be re-requested, so drop the result without touching the
                // in-flight marker or the completed queue.
                return;
            }
            inFlight.remove(key);
            completed.addLast(result);
        }
    }
}

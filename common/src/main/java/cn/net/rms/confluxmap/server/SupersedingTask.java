package cn.net.rms.confluxmap.server;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Runs asynchronous work while publishing only the newest submitted generation. */
public final class SupersedingTask<T> {
    private final Executor executor;
    private Callable<T> pending;
    private T completed;
    private Exception failure;
    private boolean scheduled;

    public SupersedingTask(final Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public synchronized boolean submit(final Callable<T> job) {
        Objects.requireNonNull(job, "job");
        pending = job;
        completed = null;
        failure = null;
        if (scheduled) {
            return true;
        }
        scheduled = true;
        try {
            executor.execute(this::runLatest);
            return true;
        } catch (final RejectedExecutionException e) {
            scheduled = false;
            pending = null;
            return false;
        }
    }

    public synchronized T completed() {
        return completed;
    }

    public synchronized Exception failure() {
        return failure;
    }

    private void runLatest() {
        while (true) {
            final Callable<T> job;
            synchronized (this) {
                job = pending;
                pending = null;
            }

            T result = null;
            Exception problem = null;
            try {
                result = job.call();
            } catch (final Exception e) {
                problem = e;
            }

            synchronized (this) {
                if (pending != null) {
                    continue;
                }
                scheduled = false;
                if (problem != null) {
                    failure = problem;
                    return;
                }
                completed = result;
                return;
            }
        }
    }
}

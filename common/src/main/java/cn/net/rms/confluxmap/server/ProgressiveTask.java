package cn.net.rms.confluxmap.server;

/** Common surface of one tracked progressive scan task, used by the shared eviction policy. */
public interface ProgressiveTask extends AutoCloseable {
    /** Nano-timestamp of the newest request that selected this task. */
    long lastRequestedAtNanos();

    /** True once every covered region has been scanned and validated. */
    boolean complete();

    @Override
    void close();
}

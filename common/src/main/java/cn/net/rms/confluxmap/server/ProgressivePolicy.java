package cn.net.rms.confluxmap.server;

/** Shared tuning bounds for progressive coarse-tile scans on every companion adapter. */
public final class ProgressivePolicy {
    /** LOD at and above which tiles are served by the progressive scanner instead of direct reads. */
    public static final int MIN_LOD = 3;

    /** Global cap on concurrently tracked progressive tiles across all players. */
    public static final int MAX_ACTIVE_TILES = 24;

    /** Unwatched tasks are dropped after this idle window. */
    public static final long IDLE_TTL_NANOS = 30_000_000_000L;

    /**
     * Per-tile cap on retained scan revisions; identical requests within one revision window reuse
     * the same validated scan instead of rebuilding it.
     */
    public static final int MAX_REVISION_VARIANTS = 8;

    private ProgressivePolicy() {
    }
}

package cn.net.rms.confluxmap.server;

/** One tile's pending correction work; identity includes its negotiated residual/absolute source mode. */
public record TileJob(
    int reqId,
    int dimIndex,
    int lod,
    int tileX,
    int tileZ,
    long sinceRevision,
    long receivedAtNanos,
    int requestBytes,
    boolean forceAbsolute
) {
    public TileJob(
        final int reqId,
        final int dimIndex,
        final int lod,
        final int tileX,
        final int tileZ,
        final long sinceRevision,
        final long receivedAtNanos,
        final int requestBytes
    ) {
        this(
            reqId, dimIndex, lod, tileX, tileZ, sinceRevision,
            receivedAtNanos, requestBytes, false
        );
    }

    public TileJob(
        final int reqId,
        final int dimIndex,
        final int lod,
        final int tileX,
        final int tileZ,
        final long sinceRevision
    ) {
        this(reqId, dimIndex, lod, tileX, tileZ, sinceRevision, 0L, 0, false);
    }
}

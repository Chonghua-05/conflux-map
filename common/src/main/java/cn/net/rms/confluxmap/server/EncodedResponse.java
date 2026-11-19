package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.ErrorS2C;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.MsgCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ProtoException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A fully built, encoded correction response plus the delivery measurements gathered while
 * preparing it. Workers hand these to the server thread, which only applies the byte budget
 * and sends.
 */
public record EncodedResponse(
    Message message,
    byte[] payload,
    int dimIndex,
    int lod,
    int tileX,
    int tileZ,
    int patchMode,
    long receivedNanos,
    long buildStartedNanos,
    int requestBytes,
    long ioNanos,
    long computeNanos,
    long encodeNanos,
    SyncPerformanceMonitor.CumulativeWork cumulativeWork
) {
    private static final Logger LOGGER = LogManager.getLogger(EncodedResponse.class);

    /** Encodes {@code response} on the calling (worker) thread, degrading to a client error on overflow. */
    public static EncodedResponse of(
        final Message response,
        final int dimIndex,
        final int lod,
        final int tileX,
        final int tileZ,
        final int mode,
        final long receivedNanos,
        final int requestBytes,
        final long buildStartedNanos,
        final long ioNanos,
        final long computeNanos,
        final SyncPerformanceMonitor.CumulativeWork cumulativeWork
    ) {
        final long started = System.nanoTime();
        try {
            final byte[] payload = MsgCodec.encode(response);
            return new EncodedResponse(
                response, payload, dimIndex, lod, tileX, tileZ, mode, receivedNanos,
                buildStartedNanos,
                requestBytes, ioNanos, computeNanos,
                Math.max(0L, System.nanoTime() - started), cumulativeWork
            );
        } catch (final ProtoException e) {
            LOGGER.warn("[ConfluxMap] companion: failed to encode correction response", e);
            final ErrorS2C error = new ErrorS2C(
                ErrorS2C.ERR_MALFORMED_REQUEST,
                "map correction response exceeded protocol limits"
            );
            try {
                return new EncodedResponse(
                    error, MsgCodec.encode(error), dimIndex, lod, tileX, tileZ,
                    Proto.PATCH_MODE_UNAVAILABLE, receivedNanos, buildStartedNanos,
                    requestBytes, ioNanos, computeNanos,
                    Math.max(0L, System.nanoTime() - started), cumulativeWork
                );
            } catch (final ProtoException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    /** Compact form for responses built and encoded in one pass, starting when received. */
    public static EncodedResponse of(
        final Message response,
        final int dimIndex,
        final int lod,
        final int tileX,
        final int tileZ,
        final int mode,
        final long receivedNanos,
        final int requestBytes,
        final long ioNanos,
        final long computeNanos
    ) {
        return of(
            response, dimIndex, lod, tileX, tileZ, mode, receivedNanos, requestBytes,
            receivedNanos, ioNanos, computeNanos, SyncPerformanceMonitor.CumulativeWork.NONE
        );
    }
}

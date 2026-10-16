package cn.net.rms.confluxmap.core.net;

import java.util.List;

/**
 * S2C: one page of the generated-region manifest for a server-initiated full sync.
 * The server enumerates the dimension's {@code .mca} files, sorts the resulting summary
 * regions by distance to the requesting player, and streams them as bounded pages under
 * one {@code syncId}; the client starts pulling only after the last page. A page with
 * {@code cancel} set aborts the sync currently identified by the same {@code syncId}.
 */
public record MapFullSyncManifestS2C(
    int syncId,
    int dimIndex,
    int lod,
    int pageIndex,
    int pageCount,
    boolean cancel,
    List<RegionCoord> regions
) implements Message {
    public record RegionCoord(int regionX, int regionZ) {
    }

    public MapFullSyncManifestS2C {
        regions = regions == null ? List.of() : List.copyOf(regions);
    }

    /** Control page the server sends to abort a running sync. */
    public static MapFullSyncManifestS2C cancel(final int syncId) {
        return new MapFullSyncManifestS2C(syncId, 0, 0, 0, 1, true, List.of());
    }

    @Override
    public int typeId() {
        return Proto.MSG_MAP_FULL_SYNC_MANIFEST_S2C;
    }
}

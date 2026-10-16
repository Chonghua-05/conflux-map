package cn.net.rms.confluxmap.core.net;

import java.util.List;

/**
 * S2C: Informs the client of all existing summary-region positions in the requested dimension,
 * so the client can prepare a batched region sync with progress tracking.
 */
public record MapFullSyncManifestS2C(
    int dimIndex,
    int lod,
    List<RegionCoord> regions
) implements Message {
    public record RegionCoord(int regionX, int regionZ) {
    }

    public MapFullSyncManifestS2C {
        regions = regions == null ? List.of() : List.copyOf(regions);
    }

    @Override
    public int typeId() {
        return Proto.MSG_MAP_FULL_SYNC_MANIFEST_S2C;
    }
}

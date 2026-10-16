package cn.net.rms.confluxmap.core.net;

/**
 * C2S: Requests server to initiate a full sync for the given dimension,
 * or cancel an ongoing full sync.
 */
public record MapFullSyncReqC2S(int dimIndex, int lod, boolean cancel) implements Message {
    @Override
    public int typeId() {
        return Proto.MSG_MAP_FULL_SYNC_REQ_C2S;
    }
}

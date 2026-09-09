package cn.net.rms.confluxmap.mc.snapshot;

import cn.net.rms.confluxmap.core.terrain.MaterialDescriptor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/** Resolves the small Minecraft-dependent material predicate table requested by the child. */
final class McTerrainMaterialResolver {
    private final Minecraft client;

    McTerrainMaterialResolver(final Minecraft client) {
        this.client = client;
    }

    Map<Integer, MaterialDescriptor> resolve(final Set<Integer> stateIds) {
        final ClientLevel world = client.level;
        if (world == null || stateIds.isEmpty()) {
            return Map.of();
        }
        final BlockPos position = client.player == null
            ? BlockPos.ZERO : client.player.blockPosition();
        final Map<Integer, MaterialDescriptor> result = new LinkedHashMap<>();
        for (final int stateId : stateIds) {
            final BlockState state = McChunkSnapshotFactory.collapse(Block.stateById(stateId));
            result.put(stateId, new MaterialDescriptor(
                McChunkSnapshotFactory.isOpenForFloorScan(state, world, position),
                McChunkSnapshotFactory.isFloorOverlayCandidate(state)
            ));
        }
        return result;
    }
}

package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.server.ServerChunkDirtyHandler;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldChunk.class)
public abstract class WorldChunkMixin {
    /**
     * Live block writes are hot on the server thread, so the first fault latches: it is logged
     * once with the block's position and the handler is never called again this process.
     */
    @Unique
    private static boolean confluxmap$chunkDirtyFaulted;

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void confluxmap$afterBlockStateChanged(
        final BlockPos pos,
        final BlockState state,
        //#if MC>=12105
        //$$ final int flags,
        //#else
        final boolean moved,
        //#endif
        final CallbackInfoReturnable<BlockState> cir
    ) {
        if (cir.getReturnValue() == null || confluxmap$chunkDirtyFaulted) {
            return;
        }
        final WorldChunk chunk = (WorldChunk) (Object) this;
        try {
            ServerChunkDirtyHandler.chunkDirty(chunk);
        } catch (final Throwable fault) {
            confluxmap$reportChunkDirtyFault(pos, chunk, fault);
        }
    }

    @Unique
    private static synchronized void confluxmap$reportChunkDirtyFault(
        final BlockPos pos,
        final WorldChunk chunk,
        final Throwable fault
    ) {
        if (confluxmap$chunkDirtyFaulted) {
            return;
        }
        confluxmap$chunkDirtyFaulted = true;
        ConfluxMapMod.LOGGER.error(
            "server chunk dirty tracking disabled after fault at block {},{},{} in chunk {},{}",
            pos.getX(), pos.getY(), pos.getZ(), chunk.getPos().x, chunk.getPos().z, fault
        );
    }
}

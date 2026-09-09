package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.server.ServerChunkDirtyHandler;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class WorldChunkMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void confluxmap$afterBlockStateChanged(
        final BlockPos pos,
        final BlockState state,
        final int flags,
        final CallbackInfoReturnable<BlockState> cir
    ) {
        if (cir.getReturnValue() != null) {
            ServerChunkDirtyHandler.chunkDirty((LevelChunk) (Object) this);
        }
    }
}

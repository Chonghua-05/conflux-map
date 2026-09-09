package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.mc.snapshot.ChunkCaptureHandler;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkManagerMixin {
    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void confluxmap$onChunkLoaded(final CallbackInfoReturnable<LevelChunk> cir) {
        final LevelChunk chunk = cir.getReturnValue();
        if (chunk != null) {
            ChunkCaptureHandler.chunkLoaded(chunk.getPos().x(), chunk.getPos().z());
        }
    }
}

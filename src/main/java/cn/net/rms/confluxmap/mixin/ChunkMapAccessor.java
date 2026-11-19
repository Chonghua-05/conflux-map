package cn.net.rms.confluxmap.mixin;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** ChunkMap.readChunk is private; the region exporter needs the on-disk NBT through it. */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Invoker("readChunk")
    CompletableFuture<Optional<CompoundTag>> confluxmap$readChunk(ChunkPos pos);
}

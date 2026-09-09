package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;

/** Main-thread adapter that exposes one loaded {@link LevelChunk} through {@link ChunkColumnSource}. */
final class WorldChunkColumnSource implements ChunkColumnSource {
    private final ServerLevel world;
    private final LevelChunk chunk;
    private final long revision;
    private final int startX;
    private final int startZ;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final Map<Block, String> blockNames = new IdentityHashMap<>();
    private final Map<BiomeSample, Integer> biomeIds = new HashMap<>();

    private record BiomeSample(int x, int y, int z) {
    }

    WorldChunkColumnSource(final ServerLevel world, final LevelChunk chunk, final long revision) {
        this.world = world;
        this.chunk = chunk;
        this.revision = revision;
        startX = chunk.getPos().getMinBlockX();
        startZ = chunk.getPos().getMinBlockZ();
    }

    @Override
    public boolean generated() {
        return true;
    }

    @Override
    public long revision() {
        return revision;
    }

    @Override
    public int bottomY() {
        return world.getMinY();
    }

    @Override
    public int motionBlockingHeight(final int x, final int z) {
        return toExclusiveHeight(chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
    }

    @Override
    public int oceanFloorHeight(final int x, final int z) {
        return toExclusiveHeight(chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z));
    }

    static int toExclusiveHeight(final int topBlockY) {
        // WorldChunk.sampleHeightmap returns the top block's Y, while ChunkColumnSource exposes
        // vanilla's stored heightmap value: the first Y above that block.
        return topBlockY + 1;
    }

    @Override
    public String blockNameAt(final int x, final int y, final int z) {
        pos.set(startX + x, y, startZ + z);
        final BlockState state = chunk.getBlockState(pos);
        return blockNames.computeIfAbsent(state.getBlock(), block -> {
            final Identifier id = Regs.blockId(block);
            return id == null ? "minecraft:air" : id.toString();
        });
    }

    @Override
    public SurfaceKind fluidKindAt(final int x, final int y, final int z) {
        pos.set(startX + x, y, startZ + z);
        final net.minecraft.world.level.material.FluidState fluid = chunk.getBlockState(pos).getFluidState();
        if (fluid.is(FluidTags.WATER)) {
            return SurfaceKind.WATER;
        }
        if (fluid.is(FluidTags.LAVA)) {
            return SurfaceKind.LAVA;
        }
        return SurfaceKind.UNKNOWN;
    }

    @Override
    public int blockLightAbove(final int x, final int surfaceY, final int z) {
        pos.set(startX + x, Math.min(surfaceY + 1, world.getMaxY() - 1), startZ + z);
        return world.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos);
    }

    @Override
    public int biomeIdAt(final int x, final int y, final int z) {
        final int worldX = startX + x;
        final int worldZ = startZ + z;
        final BiomeSample sample = new BiomeSample(worldX >> 2, y >> 2, worldZ >> 2);
        return biomeIds.computeIfAbsent(sample, ignored -> {
            pos.set(worldX, y, worldZ);
            final Identifier id = Regs.biomeIdAt(world, pos);
            return id == null ? 1 : CubiomesBiomeIds.idForName(id.getPath()).orElse(1);
        });
    }
}

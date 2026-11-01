package cn.net.rms.confluxmap.mc.snapshot;

import cn.net.rms.confluxmap.mc.color.SpriteColorSampler;
import net.minecraft.block.BlockState;

/**
 * Per-column world data behind the shared surface scan. Live capture reads it
 * straight off a loaded {@link net.minecraft.world.chunk.WorldChunk}; the
 * singleplayer MCA import backs it with parsed region-file NBT. Both paths run
 * one sampling implementation ({@link McChunkSnapshotFactory#surfaceScan}), so
 * imported terrain cannot drift from captured terrain.
 */
interface SurfaceColumn {
    int bottomY();

    int topY();

    /**
     * Vanilla heightmap semantics: the first y above the highest motion-blocking
     * block, exactly what {@code Heightmap.get(x, z)} returns on a live chunk.
     */
    int motionBlockingTop(int localX, int localZ);

    /** Never null; air outside stored sections. */
    BlockState stateAt(int localX, int y, int localZ);

    /** Block-light level (0-15) at y; 0 outside stored sections. */
    int blockLightAt(int localX, int y, int localZ);

    /**
     * Colors, detail profile and detail-pattern salt derived from the state's baked
     * model. The scan applies the profile per position, so off-thread sources cache
     * one resolution per distinct state instead of hopping per column.
     */
    SpriteColorSampler.SampledColors material(BlockState state);

    /** Tint for the state at a world position, same categories as {@code BiomeTintResolver}. */
    int tint(BlockState state, int worldX, int y, int worldZ);

    /**
     * Stable registry biome id of the surface column (for example
     * {@code minecraft:plains}); null when the source stores no biome names.
     */
    String biomeIdAt(int localX, int surfaceY, int localZ);
}

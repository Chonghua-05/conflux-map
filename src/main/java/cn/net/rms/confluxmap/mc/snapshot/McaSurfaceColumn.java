package cn.net.rms.confluxmap.mc.snapshot;

import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.core.color.MaterialDetailProfile;
import cn.net.rms.confluxmap.mc.color.McaTintResolver;
import cn.net.rms.confluxmap.mc.color.SpriteColorSampler;
import cn.net.rms.confluxmap.server.NbtChunkColumnSource;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;

/**
 * The singleplayer MCA import's {@link SurfaceColumn}: states, heights, light and biome names
 * straight from parsed region-file NBT ({@link NbtChunkColumnSource}), fed through the same
 * surface scan as live capture ({@link McChunkSnapshotFactory#snapshotSurfaceFromColumn}) so
 * imported terrain cannot drift from captured terrain.
 *
 * <p>Threading mirrors {@link XaeroSnapshotBaker}: the NBT delegates
 * ({@link #motionBlockingTop}/{@link #stateAt}/{@link #blockLightAt}/{@link #biomeIdAt}) are
 * pure and worker-safe, model sampling is main-thread only, so uncached states resolve through
 * a main-thread hop and then cache once per distinct {@link BlockState}; tints resolve the
 * column's stored biome palette name against the client registry (cached by name, including
 * negative results) and delegate to {@link McaTintResolver}, which needs no live world.
 *
 * <p>{@code client} and {@code world} may be null for the bare unit-test bootstrap, which only
 * exercises the NBT delegates; every path touching them then falls back to neutral values
 * (unknown material, white tint).
 */
final class McaSurfaceColumn implements SurfaceColumn {
    /** Xaero-style neutral material for states that cannot be sampled without a client. */
    private static final int UNKNOWN_BASE = 0xFF8C8C8C;
    /**
     * Light-clamp build height when no live world is available. Only the test bootstrap runs
     * without a world; production callers pass the world they grabbed alongside the client,
     * exactly like {@link McChunkSnapshotFactory#snapshot} does.
     */
    private static final int FALLBACK_TOP_Y = 320;

    private final NbtChunkColumnSource source;
    private final MinecraftClient client;
    private final ClientWorld world;
    private final int topY;
    private final int baseX;
    private final int baseZ;
    private final McaTintResolver tints;
    private final SpriteColorSampler sampler;
    private final Map<BlockState, SpriteColorSampler.SampledColors> materials = new ConcurrentHashMap<>();
    private final Map<String, Optional<Biome>> biomeCache = new ConcurrentHashMap<>();

    McaSurfaceColumn(
        final int chunkX,
        final int chunkZ,
        final NbtChunkColumnSource source,
        final MinecraftClient client,
        final ClientWorld world,
        final McaTintResolver tints,
        final SpriteColorSampler sampler
    ) {
        this.source = source;
        this.client = client;
        this.world = world;
        this.topY = world == null ? FALLBACK_TOP_Y : world.getTopY();
        this.tints = tints;
        this.sampler = sampler;
        this.baseX = chunkX << 4;
        this.baseZ = chunkZ << 4;
    }

    @Override
    public int bottomY() {
        return source.bottomY();
    }

    @Override
    public int topY() {
        return topY;
    }

    @Override
    public int motionBlockingTop(final int localX, final int localZ) {
        // Verified to share Heightmap.get(x, z)'s first-free-cell semantics with the live path.
        return source.motionBlockingHeight(localX, localZ);
    }

    @Override
    public BlockState stateAt(final int localX, final int y, final int localZ) {
        return source.blockStateAt(localX, y, localZ);
    }

    @Override
    public int blockLightAt(final int localX, final int y, final int localZ) {
        // blockLightAbove(x, surfaceY, z) reads the stored nibble at surfaceY + 1, so asking
        // for y here means passing y - 1 there.
        return source.blockLightAbove(localX, y - 1, localZ);
    }

    @Override
    public SpriteColorSampler.SampledColors material(final BlockState state) {
        final SpriteColorSampler.SampledColors cached = materials.get(state);
        if (cached != null) {
            return cached;
        }
        final SpriteColorSampler.SampledColors resolved = resolveMaterial(state);
        materials.put(state, resolved);
        return resolved;
    }

    /** Sprite sampling touches baked models; hop to the render thread for each uncached state. */
    private SpriteColorSampler.SampledColors resolveMaterial(final BlockState state) {
        if (client == null || world == null) {
            return unknown();
        }
        if (client.isOnThread()) {
            return sampleMaterial(state);
        }
        final CountDownLatch done = new CountDownLatch(1);
        final SpriteColorSampler.SampledColors[] out = new SpriteColorSampler.SampledColors[1];
        client.execute(() -> {
            try {
                out[0] = sampleMaterial(state);
            } finally {
                done.countDown();
            }
        });
        try {
            done.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out[0] != null ? out[0] : unknown();
    }

    private SpriteColorSampler.SampledColors sampleMaterial(final BlockState state) {
        // The live column samples at its chunk corner on the world bottom; the reference only
        // feeds map-color fallbacks, and reusing the same point keeps imported colors bitwise
        // identical to captured ones.
        return sampler.sampledColorsFor(state, world, new BlockPos(baseX, source.bottomY(), baseZ));
    }

    private static SpriteColorSampler.SampledColors unknown() {
        return new SpriteColorSampler.SampledColors(
            UNKNOWN_BASE, UNKNOWN_BASE, MaterialDetailProfile.flat(), 0
        );
    }

    @Override
    public int tint(final BlockState state, final int worldX, final int y, final int worldZ) {
        return tints.resolve(state, biomeAt(worldX - baseX, y, worldZ - baseZ), worldX, worldZ);
    }

    /**
     * The biome behind the column's stored palette name, resolved against the client's biome
     * registry and cached by name (negative results included, so an unresolvable name pays one
     * registry lookup rather than one per sampled column). Null when nothing is stored or
     * resolves - {@link McaTintResolver} then falls back per category.
     */
    private Biome biomeAt(final int localX, final int surfaceY, final int localZ) {
        final String name = source.biomeNameAt(localX, surfaceY, localZ);
        if (name == null) {
            return null;
        }
        return biomeCache.computeIfAbsent(name, this::resolveBiome).orElse(null);
    }

    private Optional<Biome> resolveBiome(final String name) {
        if (world == null) {
            return Optional.empty();
        }
        final Identifier id = Identifier.tryParse(name);
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(Regs.biome(Regs.biomes(world), id));
    }

    @Override
    public String biomeIdAt(final int localX, final int surfaceY, final int localZ) {
        return source.biomeNameAt(localX, surfaceY, localZ);
    }
}

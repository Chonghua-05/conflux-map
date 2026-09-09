package cn.net.rms.confluxmap.mc.color;

import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.compat.NativeImages;
import cn.net.rms.confluxmap.core.color.MaterialDetailProfile;
import cn.net.rms.confluxmap.core.util.Argb;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;

/**
 * Per-BlockState cached base color and 4x4 luminance profile, per
 * surface-color-sampling.md §2/§7. Colors come from sampling the live stitched
 * block-texture atlas (via each sprite's own un-stitched frame-0 source image) -
 * never from Minecraft's built-in map-item palette.
 *
 * <p>Main-thread only (touches {@link BlockStateModel}s); the returned colors are
 * plain ints safe to hand to worker threads afterward.
 */
public final class SpriteColorSampler {
    /** §2: the alpha floor - both a "skip this pixel" threshold while averaging and a final clamp. */
    private static final int ALPHA_FLOOR = 27;
    private static final int UNRESOLVED_ARGB = Argb.pack(ALPHA_FLOOR, 0, 0, 0);
    private static final Identifier WATER_STILL = Ids.of("block/water_still");
    private static final Identifier LAVA_STILL = Ids.of("block/lava_still");

    private final Minecraft client;
    private final RandomSource modelRandom = RandomSource.create(42L);
    private final RandomSource xaeroModelRandom = RandomSource.create(0L);
    private SampledMaterial[] cache = new SampledMaterial[4096];
    private int[] xaeroCache = new int[4096];
    private boolean[] xaeroCached = new boolean[4096];

    public SpriteColorSampler(final Minecraft client) {
        this.client = client;
    }

    /** Resource-reload listener hook: the atlas is being restitched, every cached color is stale. */
    public void clearCache() {
        cache = new SampledMaterial[4096];
        xaeroCache = new int[4096];
        xaeroCached = new boolean[4096];
    }

    /** The cached base color (tint not applied) for {@code state}, sampling and caching it if new. */
    public int colorFor(final BlockState state, final BlockGetter world, final BlockPos pos) {
        final int id = Block.getId(state);
        final SampledMaterial material = materialFor(state, world, pos, id);
        return material.detail().apply(material.argb(), pos.getX(), pos.getZ(), material.patternSalt());
    }

    /** Resource-derived base color before the stable world-position detail profile is applied. */
    public int baseColorFor(final BlockState state, final BlockGetter world, final BlockPos pos) {
        final int id = Block.getId(state);
        return materialFor(state, world, pos, id).argb();
    }

    /** Xaero's raw top-texture average, before biome tint and terrain lighting. */
    public int xaeroColorFor(final BlockState state, final BlockGetter world, final BlockPos pos) {
        final int id = Block.getId(state);
        if (id >= 0 && id < xaeroCached.length && xaeroCached[id]) {
            return xaeroCache[id];
        }
        final int color = computeXaeroColor(state, world, pos);
        if (id >= 0) {
            if (id >= xaeroCache.length) {
                final int size = Math.max(id + 1, xaeroCache.length * 2);
                xaeroCache = Arrays.copyOf(xaeroCache, size);
                xaeroCached = Arrays.copyOf(xaeroCached, size);
            }
            xaeroCache[id] = color;
            xaeroCached[id] = true;
        }
        return color;
    }

    private int computeXaeroColor(final BlockState state, final BlockGetter world, final BlockPos pos) {
        // Xaero's pinned oracle is 1.21.11; newer unobfuscated versions retain the normal
        // raw average until an oracle artifact for that game line exists.
        return baseColorFor(state, world, pos);
    }

    private static float xaeroTopArea(final BakedQuad quad) {
        return 1f;
    }

    private Integer sampleOneSpriteXaero(final TextureAtlasSprite sprite) {
        if (sprite == null || isMissing(sprite)) {
            return null;
        }
        final NativeImage[] images = sprite.contents().byMipLevel;
        if (images == null || images.length == 0 || images[0] == null) {
            return null;
        }
        final NativeImage image = images[0];
        final int size = Math.min(
            Math.min(sprite.contents().width(), image.getWidth()),
            Math.min(sprite.contents().height(), image.getHeight())
        );
        if (size <= 0) {
            return null;
        }
        final int stride = Math.max(1, Math.min(4, size / 8));
        long alpha = 0;
        long red = 0;
        long green = 0;
        long blue = 0;
        int count = 0;
        final int parts = size / stride;
        for (int partY = 0; partY < parts; partY++) {
            for (int partX = 0; partX < parts; partX++) {
                final int x = partX * stride;
                final int y = partY * stride;
                final int argb = NativeImages.getArgb(image, x, y);
                final int a = Argb.alpha(argb);
                if (argb == 0 || a <= 10) {
                    continue;
                }
                alpha += a;
                red += Argb.red(argb);
                green += Argb.green(argb);
                blue += Argb.blue(argb);
                count++;
            }
        }
        if (count == 0) {
            return null;
        }
        final int averageRed = (int) (red / count);
        final int averageGreen = (int) (green / count);
        final int averageBlue = (int) (blue / count);
        if (averageRed == 0 && averageGreen == 0 && averageBlue == 0) {
            return null;
        }
        return Argb.pack((int) (alpha / count), averageRed, averageGreen, averageBlue);
    }

    /** Resource-derived luminance profile for prediction's representative material palette. */
    public MaterialDetailProfile detailProfileFor(final BlockState state, final BlockGetter world, final BlockPos pos) {
        final int id = Block.getId(state);
        return materialFor(state, world, pos, id).detail();
    }

    private SampledMaterial materialFor(
        final BlockState state,
        final BlockGetter world,
        final BlockPos pos,
        final int id
    ) {
        if (id >= 0 && id < cache.length && cache[id] != null) {
            return cache[id];
        }
        final SampledMaterial material = compute(state, world, pos);
        store(id, material);
        return material;
    }

    private void store(final int id, final SampledMaterial material) {
        if (id < 0) {
            return;
        }
        if (id >= cache.length) {
            final SampledMaterial[] grown = new SampledMaterial[Math.max(id + 1, cache.length * 2)];
            System.arraycopy(cache, 0, grown, 0, cache.length);
            cache = grown;
        }
        cache[id] = material;
    }

    private SampledMaterial compute(final BlockState state, final BlockGetter world, final BlockPos pos) {
        final Block block = state.getBlock();
        if (block instanceof RedStoneWireBlock) {
            // §2: baked in unconditionally via the power-level color function, no texture sampling at all.
            final int level = state.getValue(RedStoneWireBlock.POWER);
            return new SampledMaterial(
                0xFF000000 | (RedStoneWireBlock.getColorForPower(level) & 0xFFFFFF),
                MaterialDetailProfile.flat(),
                state.toString().hashCode()
            );
        }
        final RawMaterial sampled = sampleModel(state, world, pos);
        int color = sampled.argb();
        if (block instanceof WebBlock) {
            color = withAlpha(color, 255);
        } else if (block instanceof SignBlock) {
            color = withAlpha(color, 31);
        } else if (block instanceof DoorBlock) {
            color = withAlpha(color, 47);
        } else if (block instanceof LadderBlock || block instanceof VineBlock) {
            color = withAlpha(color, 15);
        }
        final double maxDetail = !state.getFluidState().isEmpty()
            || block == Blocks.ICE || block instanceof SnowLayerBlock
            ? 0.04
            : 0.08;
        return new SampledMaterial(
            color,
            MaterialDetailProfile.fromLuminance(sampled.cellLuminance(), maxDetail),
            state.toString().hashCode()
        );
    }

    private RawMaterial sampleModel(final BlockState state, final BlockGetter world, final BlockPos pos) {
        // 26.1 moved block-state models off the render dispatcher onto the model manager.
        final BlockStateModel model = client.getModelManager().getBlockStateModelSet().get(state);
        if (model == null) {
            // §2 tier 1 (model sprite average) is unavailable for this state - fall straight
            // through to tier 3 (MapColor) rather than crash. Seen for some states very early
            // after a world join, before every block's model is baked.
            return fallbackToMapColor(state, world, pos);
        }
        final List<TextureAtlasSprite> faceSprites = new ArrayList<>();
        // 26.1 turned the part list into an out-parameter and moved a quad's sprite behind
        // its material record.
        final List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(modelRandom, parts);
        for (final BlockStateModelPart part : parts) {
            for (final BakedQuad quad : part.getQuads(Direction.UP)) {
                faceSprites.add(quad.materialInfo().sprite());
            }
            for (final BakedQuad quad : part.getQuads(null)) {
                faceSprites.add(quad.materialInfo().sprite());
            }
        }
        final RawMaterial primary = averageSprites(faceSprites);
        if (primary != null) {
            return primary.withArgb(clampAlphaFloor(primary.argb()));
        }
        final TextureAtlasSprite particle = model.particleMaterial().sprite();
        final boolean isFluid = !state.getFluidState().isEmpty();
        if (particle == null || isMissing(particle)) {
            if (isFluid) {
                final TextureAtlasSprite fluidSprite = fluidSprite(state);
                final RawMaterial sampled = fluidSprite == null ? null : sampleOneSprite(fluidSprite);
                if (sampled != null) {
                    return sampled.withArgb(clampAlphaFloor(sampled.argb()));
                }
            }
            return fallbackToMapColor(state, world, pos);
        }
        final RawMaterial sampled = sampleOneSprite(particle);
        return sampled != null
            ? sampled.withArgb(clampAlphaFloor(sampled.argb()))
            : fallbackToMapColor(state, world, pos);
    }

    private RawMaterial fallbackToMapColor(final BlockState state, final BlockGetter world, final BlockPos pos) {
        try {
            final int rgb = state.getMapColor(world, pos).col;
            if (rgb != 0) {
                return RawMaterial.flat(0xFF000000 | (rgb & 0xFFFFFF));
            }
        } catch (final RuntimeException ignored) {
            // Some blocks' getMapColor implementations touch world state we don't have here; fall through.
        }
        return RawMaterial.flat(UNRESOLVED_ARGB);
    }

    private TextureAtlasSprite fluidSprite(final BlockState state) {
        final Identifier id = state.is(Blocks.LAVA) ? LAVA_STILL : WATER_STILL;
        final TextureAtlas atlas = client.getAtlasManager().getAtlasOrThrow(
            TextureAtlas.LOCATION_BLOCKS
        );
        return atlas.getSprite(id);
    }

    private static boolean isMissing(final TextureAtlasSprite sprite) {
        return sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation());
    }

    /** Equal-weighted average across every quad's resolved sprite color; null if none were usable. */
    private RawMaterial averageSprites(final List<TextureAtlasSprite> sprites) {
        long sumA = 0;
        long sumR = 0;
        long sumG = 0;
        long sumB = 0;
        final long[] cellLuminance = new long[MaterialDetailProfile.CELLS];
        int count = 0;
        for (final TextureAtlasSprite sprite : sprites) {
            final RawMaterial sample = sampleOneSprite(sprite);
            if (sample == null) {
                continue;
            }
            final int c = sample.argb();
            sumA += Argb.alpha(c);
            sumR += Argb.red(c);
            sumG += Argb.green(c);
            sumB += Argb.blue(c);
            for (int i = 0; i < cellLuminance.length; i++) {
                cellLuminance[i] += sample.cellLuminance()[i];
            }
            count++;
        }
        if (count == 0) {
            return null;
        }
        final int[] cells = new int[MaterialDetailProfile.CELLS];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = (int) (cellLuminance[i] / count);
        }
        return new RawMaterial(
            Argb.pack((int) (sumA / count), (int) (sumR / count), (int) (sumG / count), (int) (sumB / count)),
            cells
        );
    }

    /**
     * Box-filter/downsample one sprite's frame-0 pixels to a single alpha-weighted average
     * color. Pixels below {@link #ALPHA_FLOOR} are skipped so mostly-transparent decorative
     * textures (leaves, vines) average toward their visible color rather than toward black.
     * Null if the sprite is unresolvable or has no usable pixels at all.
     */
    private RawMaterial sampleOneSprite(final TextureAtlasSprite sprite) {
        if (sprite == null || isMissing(sprite)) {
            return null;
        }
        final NativeImage[] images = sprite.contents().byMipLevel;
        if (images == null || images.length == 0 || images[0] == null) {
            return null;
        }
        final NativeImage image = images[0];
        final int w = Math.min(sprite.contents().width(), image.getWidth());
        final int h = Math.min(sprite.contents().height(), image.getHeight());
        if (w <= 0 || h <= 0) {
            return null;
        }
        long sumA = 0;
        long sumR = 0;
        long sumG = 0;
        long sumB = 0;
        final long[] cellWeightedLuminance = new long[MaterialDetailProfile.CELLS];
        final long[] cellAlpha = new long[MaterialDetailProfile.CELLS];
        int count = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int argb = NativeImages.getArgb(image, x, y);
                final int a = Argb.alpha(argb);
                if (a < ALPHA_FLOOR) {
                    continue;
                }
                sumA += a;
                sumR += (long) Argb.red(argb) * a;
                sumG += (long) Argb.green(argb) * a;
                sumB += (long) Argb.blue(argb) * a;
                final int cellX = Math.min(3, x * 4 / w);
                final int cellY = Math.min(3, y * 4 / h);
                final int cell = cellY * 4 + cellX;
                cellWeightedLuminance[cell] += (long) luminance(argb) * a;
                cellAlpha[cell] += a;
                count++;
            }
        }
        if (count == 0 || sumA == 0) {
            return null;
        }
        final int argb = Argb.pack(
            (int) (sumA / count), (int) (sumR / sumA), (int) (sumG / sumA), (int) (sumB / sumA)
        );
        final int fallbackLuminance = luminance(argb);
        final int[] cells = new int[MaterialDetailProfile.CELLS];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = cellAlpha[i] == 0
                ? fallbackLuminance
                : (int) (cellWeightedLuminance[i] / cellAlpha[i]);
        }
        return new RawMaterial(argb, cells);
    }

    private static int luminance(final int argb) {
        return (54 * Argb.red(argb) + 183 * Argb.green(argb) + 19 * Argb.blue(argb)) >> 8;
    }

    private static int clampAlphaFloor(final int argb) {
        return Argb.alpha(argb) < ALPHA_FLOOR ? withAlpha(argb, ALPHA_FLOOR) : argb;
    }

    private static int withAlpha(final int argb, final int alpha) {
        return (argb & 0x00FFFFFF) | (alpha & 0xFF) << 24;
    }

    private record SampledMaterial(int argb, MaterialDetailProfile detail, int patternSalt) {
    }

    private record RawMaterial(int argb, int[] cellLuminance) {
        static RawMaterial flat(final int argb) {
            final int[] cells = new int[MaterialDetailProfile.CELLS];
            Arrays.fill(cells, luminance(argb));
            return new RawMaterial(argb, cells);
        }

        RawMaterial withArgb(final int replacement) {
            return new RawMaterial(replacement, cellLuminance);
        }
    }
}

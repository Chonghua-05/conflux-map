package cn.net.rms.confluxmap.mc.predict;

import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.core.color.MaterialDetailProfile;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.predict.BiomeTable;
import cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds;
import cn.net.rms.confluxmap.core.predict.PredictionPalette;
import cn.net.rms.confluxmap.core.predict.PredictionState;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.mc.color.SpriteColorSampler;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;

/**
 * Session listener that samples every biome the client's live registry knows about (grass/
 * foliage/water tint at a fixed reference point, per surface-color-sampling.md's own "live"
 * sampling APIs) once per session, on the main thread, and publishes the result into {@link
 * PredictionState} as a fresh {@link PredictionPalette}. A biome id {@link CubiomesBiomeIds}
 * can't resolve (a registry path cubiomes has no matching id for - modded biomes, mainly) simply
 * has no entry and keeps using {@code core.predict.BiomeTable}'s fallback forever, per {@link
 * PredictionPalette}'s own per-id lookup contract. Palette data only ever affects color, never
 * which pixels are baseline water/land/foliage.
 */
public final class PredictionPaletteBuilder {
    private final Minecraft client;
    private final PredictionState state;
    private final SpriteColorSampler sampler;

    public PredictionPaletteBuilder(
        final Minecraft client,
        final PredictionState state,
        final SpriteColorSampler sampler
    ) {
        this.client = client;
        this.state = state;
        this.sampler = sampler;
    }

    /** Main thread, from the session tracker. */
    public void onSessionChanged(final SessionGuard.Session session) {
        final ClientLevel world = client.level;
        if (!session.active() || world == null) {
            state.setPalette(PredictionPalette.defaults());
            return;
        }
        rebuild(world);
    }

    /** Main-thread resource-reload hook: rebuild representative textures for the active world. */
    public void refreshCurrentWorld() {
        final ClientLevel world = client.level;
        if (world == null) {
            state.setPalette(PredictionPalette.defaults());
            return;
        }
        rebuild(world);
    }

    private void rebuild(final ClientLevel world) {
        final Map<Integer, int[]> sampled = new HashMap<>();
        final var registry = Regs.biomes(world);
        for (final Identifier id : registry.keySet()) {
            final OptionalInt cubiomesId = CubiomesBiomeIds.idForName(id.getPath());
            if (cubiomesId.isEmpty()) {
                continue;
            }
            final Biome biome = Regs.biome(registry, id);
            if (biome == null) {
                continue;
            }
            final int grass = 0xFF000000 | biome.getGrassColor(0.0, 0.0);
            final int foliage = 0xFF000000 | biome.getFoliageColor();
            final int water = 0xFF000000 | biome.getWaterColor();
            sampled.put(cubiomesId.getAsInt(), new int[] {grass, foliage, water});
        }
        final BlockPos reference = client.player == null ? BlockPos.ZERO : client.player.blockPosition();
        final Map<SurfaceKind, MaterialDetailProfile> materials = new EnumMap<>(SurfaceKind.class);
        final Map<SurfaceKind, Integer> materialBaseColors = new EnumMap<>(SurfaceKind.class);
        materials.put(
            SurfaceKind.LAND, sampler.detailProfileFor(Blocks.GRASS_BLOCK.defaultBlockState(), world, reference)
        );
        materials.put(
            SurfaceKind.SAND, sampler.detailProfileFor(Blocks.SAND.defaultBlockState(), world, reference)
        );
        materials.put(
            SurfaceKind.SNOW, sampler.detailProfileFor(Blocks.SNOW_BLOCK.defaultBlockState(), world, reference)
        );
        materials.put(
            SurfaceKind.ICE, sampler.detailProfileFor(Blocks.ICE.defaultBlockState(), world, reference)
        );
        materials.put(
            SurfaceKind.WATER, sampler.detailProfileFor(Blocks.WATER.defaultBlockState(), world, reference)
        );
        materials.put(
            SurfaceKind.FOLIAGE, sampler.detailProfileFor(Blocks.OAK_LEAVES.defaultBlockState(), world, reference)
        );
        materials.put(
            SurfaceKind.LAVA, sampler.detailProfileFor(Blocks.LAVA.defaultBlockState(), world, reference)
        );
        materials.put(
            SurfaceKind.BEDROCK_CEILING,
            sampler.detailProfileFor(Blocks.BEDROCK.defaultBlockState(), world, reference)
        );
        materialBaseColors.put(
            SurfaceKind.BEDROCK_CEILING,
            sampler.baseColorFor(Blocks.BEDROCK.defaultBlockState(), world, reference)
        );
        final MaterialDetailProfile endStone = sampler.detailProfileFor(
            Blocks.END_STONE.defaultBlockState(), world, reference
        );
        final Map<Integer, MaterialDetailProfile> groundMaterials = new HashMap<>();
        groundMaterials.put(
            CubiomesBiomeIds.NETHER_WASTES,
            sampler.detailProfileFor(Blocks.NETHERRACK.defaultBlockState(), world, reference)
        );
        groundMaterials.put(
            CubiomesBiomeIds.SOUL_SAND_VALLEY,
            sampler.detailProfileFor(Blocks.SOUL_SAND.defaultBlockState(), world, reference)
        );
        groundMaterials.put(
            CubiomesBiomeIds.CRIMSON_FOREST,
            sampler.detailProfileFor(Blocks.CRIMSON_NYLIUM.defaultBlockState(), world, reference)
        );
        groundMaterials.put(
            CubiomesBiomeIds.WARPED_FOREST,
            sampler.detailProfileFor(Blocks.WARPED_NYLIUM.defaultBlockState(), world, reference)
        );
        groundMaterials.put(
            CubiomesBiomeIds.BASALT_DELTAS,
            sampler.detailProfileFor(Blocks.BASALT.defaultBlockState(), world, reference)
        );
        for (final int biomeId : BiomeTable.knownIds()) {
            if (BiomeTable.isEnd(biomeId)) {
                groundMaterials.put(biomeId, endStone);
            }
        }
        state.setPalette(PredictionPalette.fromSamples(
            sampled, materials, groundMaterials, materialBaseColors
        ));
    }
}

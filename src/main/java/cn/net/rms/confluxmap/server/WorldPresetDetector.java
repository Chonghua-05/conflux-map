package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.predict.WorldPreset;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.DebugLevelSource;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/**
 * Classifies one dimension's live generator into a {@link WorldPreset}. Runs on whichever side
 * owns the {@link ServerLevel}: the integrated server in singleplayer ({@code
 * mc.predict.PredictionBootstrap}) and the companion on a dedicated server ({@code
 * ServerNetworking}, {@code RegionSummaryService}).
 *
 * <p>Classification is deliberately biased toward not regressing normal worlds: a vanilla
 * layered biome source is enough to call the layout predictable, and {@code matchesSettings}
 * (an identity check against the builtin registry) is used only to <em>positively</em> identify
 * Amplified - if it false-negatives on an exotic registry copy, the world degrades to
 * {@code DEFAULT}/{@code LARGE_BIOMES}, which matches pre-preset behavior. Vanilla Nether
 * multi-noise is recognized through its builtin preset identity; direct/custom multi-noise,
 * single-biome buffet, datapack, and modded sources remain {@code CUSTOM}.
 */
public final class WorldPresetDetector {
    private WorldPresetDetector() {
    }

    public static WorldPreset detect(final ServerLevel world) {
        final ChunkGenerator generator = world.getChunkSource().getGenerator();
        if (generator instanceof FlatLevelSource) {
            return WorldPreset.FLAT;
        }
        if (generator instanceof DebugLevelSource) {
            return WorldPreset.DEBUG;
        }
        if (!(generator instanceof final NoiseBasedChunkGenerator noise)) {
            return WorldPreset.CUSTOM;
        }
        final BiomeSource source = generator.getBiomeSource();
        if (source instanceof final MultiNoiseBiomeSource multiNoise) {
            if (multiNoise.stable(MultiNoiseBiomeSourceParameterLists.NETHER)) {
                return WorldPreset.DEFAULT;
            }
            if (multiNoise.stable(MultiNoiseBiomeSourceParameterLists.OVERWORLD)) {
                if (noise.stable(NoiseGeneratorSettings.AMPLIFIED)) {
                    return WorldPreset.AMPLIFIED;
                }
                return noise.stable(NoiseGeneratorSettings.LARGE_BIOMES)
                    ? WorldPreset.LARGE_BIOMES
                    : WorldPreset.DEFAULT;
            }
        }
        if (source instanceof TheEndBiomeSource) {
            return WorldPreset.DEFAULT;
        }
        return WorldPreset.CUSTOM;
    }
}

package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds;
import java.util.List;
import java.util.Map;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

/**
 * One resolution chain for every biome name this mod draws: the client's own language table
 * first (mods and resource packs the user actually installed), then datapack-provided names
 * synced from the level (see {@link DatapackBiomeNameService}), then the registry id — the
 * fallback vanilla's F3 uses everywhere, never a raw translation key. Datapack custom biomes
 * have no client-side translation at all, which used to leak {@code biome.pack.name} into the
 * minimap and the map footer.
 */
public final class BiomeLabels {
    private static volatile Map<String, String> datapackNames = Map.of();

    private BiomeLabels() {
    }

    /** Replaces the synced datapack set; an empty map clears it. */
    public static void acceptDatapackNames(final Map<String, String> names) {
        datapackNames = names == null ? Map.of() : Map.copyOf(names);
    }

    public static void clearDatapackNames() {
        datapackNames = Map.of();
    }

    /** Translated → datapack → registry id; the id fallback matches vanilla F3. */
    public static String displayName(final Identifier biomeId) {
        final String key = Util.createTranslationKey("biome", biomeId);
        final String translated = Texts.translatable(key).getString();
        if (!translated.equals(key)) {
            return translated;
        }
        final String fromDatapack = datapackNames.get(key);
        return fromDatapack != null ? fromDatapack : biomeId.toString();
    }

    /**
     * A predicted biome only knows its cubiomes id, whose canonical vanilla name is the 1.17-era
     * one; biomes renamed since (snowy_tundra -> snowy_plains, ...) have no translation under the
     * old key on modern versions. Try every spelling the id has carried, then the datapack set,
     * and fall back to the canonical id rather than a raw translation key.
     */
    public static String predictedDisplayName(final int cubiomesId) {
        final List<String> spellings = CubiomesBiomeIds.namesForId(cubiomesId);
        String canonicalName = null;
        for (final String name : spellings) {
            final String key = Util.createTranslationKey("biome", Ids.of("minecraft", name));
            final String translated = Texts.translatable(key).getString();
            if (!translated.equals(key)) {
                return translated;
            }
            if (canonicalName == null) {
                canonicalName = name;
            }
        }
        if (canonicalName == null) {
            return "";
        }
        final String canonicalKey =
            Util.createTranslationKey("biome", Ids.of("minecraft", canonicalName));
        final String fromDatapack = datapackNames.get(canonicalKey);
        return fromDatapack != null ? fromDatapack : "minecraft:" + canonicalName;
    }
}

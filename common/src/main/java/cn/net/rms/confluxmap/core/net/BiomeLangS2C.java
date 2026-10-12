package cn.net.rms.confluxmap.core.net;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code 0x17 S2C BIOME_LANG}: the datapack-provided biome display names for the requesting
 * locale. Keys are full translation keys ({@code "biome.<namespace>.<path>"}); the client only
 * consults entries its own language table failed to translate, so a pack shipping vanilla-key
 * overrides cannot change vanilla names. An empty map is a valid answer ("this world's packs
 * ship no biome names") and clears any previously synced set.
 *
 * @param names translation key → display name; ≤ {@value Proto#MAX_BIOME_LANG_ENTRIES} entries
 */
public record BiomeLangS2C(Map<String, String> names) implements Message {
    public BiomeLangS2C {
        names = new LinkedHashMap<>(names == null ? Map.of() : names);
    }

    @Override
    public int typeId() {
        return Proto.MSG_BIOME_LANG_S2C;
    }
}

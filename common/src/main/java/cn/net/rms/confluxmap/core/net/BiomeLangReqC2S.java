package cn.net.rms.confluxmap.core.net;

/**
 * {@code 0x16 C2S BIOME_LANG_REQ}: the client asks for the datapack-side {@code biome.*}
 * translations in its own locale. Datapack lang files never load into the client's language
 * table (vanilla serves both F3 and /locate from the registry id), so the companion is the only
 * side that can read them; the client sends its selected locale because pre-1.19 servers
 * discard the client-settings language and no version exposes it reliably.
 *
 * @param locale the client's selected language code (e.g. {@code "zh_cn"}), {@code en_us}
 *               when the field is unusable; ≤ {@value Proto#MAX_UTF8_BYTES} UTF-8 bytes
 */
public record BiomeLangReqC2S(String locale) implements Message {
    @Override
    public int typeId() {
        return Proto.MSG_BIOME_LANG_REQ_C2S;
    }
}

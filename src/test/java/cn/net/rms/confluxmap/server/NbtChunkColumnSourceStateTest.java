package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@link NbtChunkColumnSource}'s exact-state and biome-name extensions: the palette-to-BlockState
 * resolution rules the single-player snapshot path shares with the live capture (unknown ids
 * collapse to air, unknown properties or values fall back to the block default) and the raw biome
 * palette names. {@link AnvilMcaScannerTest} already covers the summarized surface fields.
 */
class NbtChunkColumnSourceStateTest {
    private static final int MODERN_BOTTOM_Y_SECTION = -4;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        //#if MC>=11800
        //$$ Assumptions.abort(
        //$$     "Yarn's named 1.18+ test jars split vanilla package-private registry access; "
        //$$         + "this Minecraft-backed behavior is verified under Fabric Loader by GameTest"
        //$$ );
        //#else
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        //#endif
    }

    @Test
    void blockStateAtResolvesPalettePropertiesWithVanillaFallbacks() {
        final NbtChunkColumnSource source = new NbtChunkColumnSource(modernChunk(statesSection()));

        // Palette slot 0 (the default for every undecoded position): stairs with two properties.
        final BlockState stairs = source.blockStateAt(0, 64, 0);
        assertEquals(Blocks.OAK_STAIRS.getDefaultState()
            .with(Properties.HORIZONTAL_FACING, Direction.EAST)
            .with(Properties.WATERLOGGED, true), stairs);
        assertTrue(stairs.get(Properties.WATERLOGGED));
        assertEquals("minecraft:oak_stairs", source.blockNameAt(0, 64, 0));
        // The name/fluid paths keep their existing semantics: waterlogged stairs read as water.
        assertEquals(SurfaceKind.WATER, source.fluidKindAt(0, 64, 0));

        // Palette slot 1: a block id no registry knows collapses to air, while blockNameAt still
        // hands out the raw serialized name.
        assertEquals(Blocks.AIR.getDefaultState(), source.blockStateAt(1, 64, 0));
        assertEquals("minecraft:not_a_real_block", source.blockNameAt(1, 64, 0));

        // Palette slot 2: an unknown property is skipped without losing the state.
        assertEquals(Blocks.STONE.getDefaultState(), source.blockStateAt(2, 64, 0));
        assertEquals("minecraft:stone", source.blockNameAt(2, 64, 0));

        // Palette slot 3: a value the property cannot parse falls back to the block default.
        assertEquals(Blocks.OAK_STAIRS.getDefaultState(), source.blockStateAt(3, 64, 0));

        // Palette slot 4: the mixed-palette {"" : id} string form resolves to a real state.
        assertEquals(Blocks.DIRT.getDefaultState(), source.blockStateAt(4, 64, 0));
        assertEquals("minecraft:dirt", source.blockNameAt(4, 64, 0));

        // Repeat reads hit the per-section cache and stay identical.
        assertSame(stairs, source.blockStateAt(0, 64, 0));

        // Missing sections and out-of-range Y return air.
        assertEquals(Blocks.AIR.getDefaultState(), source.blockStateAt(0, 80, 0));
        assertEquals(Blocks.AIR.getDefaultState(), source.blockStateAt(0, 300, 0));
    }

    @Test
    void biomeNameAtReturnsRawPaletteNamesAndLeavesBiomeIdAtUntouched() {
        final NbtChunkColumnSource source = new NbtChunkColumnSource(modernChunk(biomeSection()));

        assertEquals("minecraft:plains", source.biomeNameAt(0, 80, 0));
        assertEquals("minecraft:desert", source.biomeNameAt(4, 80, 0));
        assertEquals("minecraft:desert", source.biomeNameAt(0, 84, 0));

        // The int mapping behind biomeIdAt is unchanged by the added names.
        assertEquals(1, source.biomeIdAt(0, 80, 0));
        assertEquals(2, source.biomeIdAt(4, 80, 0));
        assertEquals(2, source.biomeIdAt(0, 84, 0));
    }

    @Test
    void biomeNameAtReturnsNullWhenNoNameIsStored() {
        // No sections at all.
        assertNull(new NbtChunkColumnSource(modernChunk(new NbtList())).biomeNameAt(0, 64, 0));

        // A section without a biomes compound: biomeIdAt keeps its plains default, the name is null.
        final NbtChunkColumnSource source = new NbtChunkColumnSource(modernChunk(statesSection()));
        assertNull(source.biomeNameAt(0, 64, 0));
        assertEquals(1, source.biomeIdAt(0, 64, 0));

        // Out-of-range Y.
        assertNull(source.biomeNameAt(0, 300, 0));
    }

    @Test
    void legacyBiomeIntArrayKeepsIntPathAndYieldsNoNames() {
        final NbtCompound level = new NbtCompound();
        level.putString("Status", "full");
        level.putLong("LastUpdate", 7L);
        final NbtCompound heightmaps = new NbtCompound();
        heightmaps.putLongArray("MOTION_BLOCKING", new long[(256 + 6) / 7]);
        level.put("Heightmaps", heightmaps);
        final int[] biomes = new int[1_024];
        biomes[0] = 2;
        level.putIntArray("Biomes", biomes);
        final NbtList sections = new NbtList();
        final NbtCompound section = new NbtCompound();
        section.putByte("Y", (byte) 0);
        final NbtList palette = new NbtList();
        final NbtCompound stone = new NbtCompound();
        stone.putString("Name", "minecraft:stone");
        palette.add(stone);
        section.put("Palette", palette);
        sections.add(section);
        level.put("Sections", sections);
        final NbtCompound root = new NbtCompound();
        root.put("Level", level);

        final NbtChunkColumnSource source = new NbtChunkColumnSource(root);

        assertEquals(2, source.biomeIdAt(0, 0, 0));
        assertNull(source.biomeNameAt(0, 0, 0));
        // The legacy Palette path resolves states through the same rules.
        assertEquals(Blocks.STONE.getDefaultState(), source.blockStateAt(0, 0, 0));
        assertEquals("minecraft:stone", source.blockNameAt(0, 0, 0));
    }

    /** A minimal modern (1.18+ layout) full chunk wrapping {@code sections} at yPos -4. */
    private static NbtCompound modernChunk(final NbtList sections) {
        final NbtCompound root = new NbtCompound();
        root.putString("Status", "full");
        root.putLong("LastUpdate", 42L);
        root.putInt("yPos", MODERN_BOTTOM_Y_SECTION);
        final NbtCompound heightmaps = new NbtCompound();
        heightmaps.putLongArray("MOTION_BLOCKING", new long[(256 + 6) / 7]);
        root.put("Heightmaps", heightmaps);
        root.put("sections", sections);
        return root;
    }

    /**
     * Section Y=4 (world Y 64..79) whose palette holds the five entry forms the resolver must
     * distinguish. Only local indices 1-4 are pointed at entries 1-4; everything else decodes to
     * entry 0, so one packed long covers the whole section.
     */
    private static NbtList statesSection() {
        final NbtList palette = new NbtList();
        palette.add(entry("minecraft:oak_stairs", "facing", "east", "waterlogged", "true"));
        palette.add(entry("minecraft:not_a_real_block"));
        palette.add(entry("minecraft:stone", "not_a_property", "weird"));
        palette.add(entry("minecraft:oak_stairs", "facing", "up"));
        final NbtCompound bareString = new NbtCompound();
        bareString.putString("", "minecraft:dirt");
        palette.add(bareString);

        final NbtCompound blockStates = new NbtCompound();
        blockStates.put("palette", palette);
        blockStates.putLongArray("data", pack(4, 0, 1, 2, 3, 4));

        final NbtCompound section = new NbtCompound();
        section.putByte("Y", (byte) 4);
        section.put("block_states", blockStates);
        final NbtList sections = new NbtList();
        sections.add(section);
        return sections;
    }

    /** Section Y=5 (world Y 80..95): stone everywhere, biomes plains at quart index 0/16, desert at 1. */
    private static NbtList biomeSection() {
        final NbtList palette = new NbtList();
        palette.add(entry("minecraft:stone"));

        final NbtCompound blockStates = new NbtCompound();
        blockStates.put("palette", palette);

        final NbtCompound biomes = new NbtCompound();
        final NbtList biomePalette = new NbtList();
        biomePalette.add(NbtString.of("minecraft:plains"));
        biomePalette.add(NbtString.of("minecraft:desert"));
        biomes.put("palette", biomePalette);
        biomes.putLongArray("data", new long[] {(1L << 1) | (1L << 16)});

        final NbtCompound section = new NbtCompound();
        section.putByte("Y", (byte) 5);
        section.put("block_states", blockStates);
        section.put("biomes", biomes);
        final NbtList sections = new NbtList();
        sections.add(section);
        return sections;
    }

    private static NbtCompound entry(final String name, final String... properties) {
        final NbtCompound compound = new NbtCompound();
        compound.putString("Name", name);
        if (properties.length != 0) {
            final NbtCompound propertyCompound = new NbtCompound();
            for (int i = 0; i < properties.length; i += 2) {
                propertyCompound.putString(properties[i], properties[i + 1]);
            }
            compound.put("Properties", propertyCompound);
        }
        return compound;
    }

    /** Packs values the way {@code PackedBits} decodes them: LSB-first, non-spanning words. */
    private static long[] pack(final int bits, final int... values) {
        final int perWord = 64 / bits;
        final long[] words = new long[(values.length + perWord - 1) / perWord];
        for (int i = 0; i < values.length; i++) {
            words[i / perWord] |= (long) values[i] << ((i % perWord) * bits);
        }
        return words;
    }
}

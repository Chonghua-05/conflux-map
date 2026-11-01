package cn.net.rms.confluxmap.mc.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import cn.net.rms.confluxmap.core.color.MaterialDetailProfile;
import cn.net.rms.confluxmap.mc.color.McaTintResolver;
import cn.net.rms.confluxmap.mc.color.SpriteColorSampler;
import cn.net.rms.confluxmap.server.NbtChunkColumnSource;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Blocks;
import net.minecraft.client.color.world.FoliageColors;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@link McaSurfaceColumn}'s NBT-backed {@link SurfaceColumn} delegates: written heightmap in,
 * vanilla heightmap semantics out; palette properties resolve to exact registry states; the
 * block-light y convention and the biome palette names survive the hop through the column.
 * Everything here runs without a client - the material/tint assertions pin the neutral
 * fallbacks and the biome-independent fixed spruce color that {@link McaTintResolver} serves
 * on the no-world paths. Modeled on {@code NbtChunkColumnSourceStateTest}'s bare bootstrap.
 */
class McaSurfaceColumnTest {
    private static final int CHUNK_X = 3;
    private static final int CHUNK_Z = -2;
    /** modernChunk writes yPos = -4, so the column's world bottom is Y -64. */
    private static final int BOTTOM_Y = -64;

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
    void surfaceColumnDelegatesHeightStateLightAndBiome() {
        final McaSurfaceColumn column = column();

        assertEquals(BOTTOM_Y, column.bottomY());
        // No live world: the build-height clamp falls back to the modern constant.
        assertEquals(320, column.topY());

        // The written 9-bit heightmap entries come back with Heightmap.get semantics
        // (first free cell above the surface).
        assertEquals(71, column.motionBlockingTop(0, 0));
        assertEquals(7, column.motionBlockingTop(15, 15));
        assertEquals(BOTTOM_Y, column.motionBlockingTop(0, 1));

        // Palette properties resolve exactly like the live capture's states.
        assertEquals(
            Blocks.OAK_STAIRS.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, Direction.EAST)
                .with(Properties.WATERLOGGED, true),
            column.stateAt(0, 70, 0)
        );
        assertEquals(Blocks.STONE.getDefaultState(), column.stateAt(5, 70, 5));
        // Missing section reads as air.
        assertEquals(Blocks.AIR.getDefaultState(), column.stateAt(0, 80, 0));

        // blockLightAt(y) surfaces the stored nibble AT y: the test wrote 13/5 into y=71 and
        // nothing into y=70, so both the value and the y-1 mapping through blockLightAbove
        // are pinned from both sides.
        assertEquals(13, column.blockLightAt(0, 71, 0));
        assertEquals(5, column.blockLightAt(1, 71, 0));
        assertEquals(0, column.blockLightAt(0, 70, 0));
        assertEquals(0, column.blockLightAt(0, 80, 0));

        assertEquals("minecraft:plains", column.biomeIdAt(0, 70, 0));
        assertNull(column.biomeIdAt(0, 300, 0));
    }

    @Test
    void materialAndTintFallBackWithoutClient() {
        final McaSurfaceColumn column = column();

        // No client to sample baked models with: the neutral Xaero-style fallback, cached per state.
        final SpriteColorSampler.SampledColors material = column.material(Blocks.STONE.getDefaultState());
        assertEquals(0xFF8C8C8C, material.baseArgb());
        assertEquals(0xFF8C8C8C, material.xaeroBaseArgb());
        assertSame(MaterialDetailProfile.flat(), material.detail());
        assertEquals(0, material.patternSalt());
        assertSame(material, column.material(Blocks.STONE.getDefaultState()));

        // Fixed spruce coloring never consults the biome, so it survives the client-less path;
        // the biome-sampled categories fall back to white while no world resolves the palette
        // name (the stored "minecraft:plains" is cached as a negative lookup).
        //#if MC>=12104
        //$$ assertEquals(
        //$$     0xFF000000 | FoliageColors.SPRUCE,
        //$$     column.tint(Blocks.SPRUCE_LEAVES.getDefaultState(), 48, 70, -30)
        //$$ );
        //#else
        assertEquals(
            0xFF000000 | FoliageColors.getSpruceColor(),
            column.tint(Blocks.SPRUCE_LEAVES.getDefaultState(), 48, 70, -30)
        );
        //#endif
        assertEquals(0xFFFFFFFF, column.tint(Blocks.GRASS_BLOCK.getDefaultState(), 48, 70, -30));
        // No WATER-tint assert: the shared syncedTint classifies water through FluidTags.WATER,
        // whose required-tag wrapper throws "used before it was bound" under the bare 1.17.1
        // bootstrap - the same GRASS branch above already pins the null-biome white fallback.
    }

    private static McaSurfaceColumn column() {
        final NbtChunkColumnSource source = new NbtChunkColumnSource(modernChunk(surfaceSection()));
        return new McaSurfaceColumn(
            CHUNK_X, CHUNK_Z, source, null, null,
            new McaTintResolver(null), new SpriteColorSampler(null)
        );
    }

    /** A minimal modern (1.18+ layout) full chunk at yPos -4 with the given heightmap. */
    private static NbtCompound modernChunk(final NbtList sections) {
        final NbtCompound root = new NbtCompound();
        root.putString("Status", "full");
        root.putLong("LastUpdate", 99L);
        root.putInt("yPos", BOTTOM_Y / 16);
        final NbtCompound heightmaps = new NbtCompound();
        heightmaps.putLongArray("MOTION_BLOCKING", heightmap());
        root.put("Heightmaps", heightmaps);
        root.put("sections", sections);
        return root;
    }

    /**
     * 256 nine-bit heightmap entries (index {@code z * 16 + x}), LSB-first non-spanning like
     * {@code PackedBits} decodes them: column (0, 0) holds its first free cell at Y 71 and
     * column (15, 15) at Y 7, everything else at the world bottom.
     */
    private static long[] heightmap() {
        final int[] values = new int[256];
        values[0] = 71 - BOTTOM_Y;
        values[255] = 7 - BOTTOM_Y;
        final int perWord = 64 / 9;
        final long[] words = new long[(values.length + perWord - 1) / perWord];
        for (int i = 0; i < values.length; i++) {
            words[i / perWord] |= (long) values[i] << ((i % perWord) * 9);
        }
        return words;
    }

    /**
     * Section Y=4 (world Y 64..79): stone everywhere except oak stairs (facing east,
     * waterlogged) at (x=0, y=70, z=0); block light 13/5 stored at y=71 for x=0/1 of z=0; a
     * single-entry biome palette naming minecraft:plains for the whole section.
     */
    private static NbtList surfaceSection() {
        final NbtList palette = new NbtList();
        palette.add(entry("minecraft:stone"));
        palette.add(entry("minecraft:oak_stairs", "facing", "east", "waterlogged", "true"));

        // 4096 entries at 4 bits (16 per long): index (floorMod(70, 16) * 16 + 0) * 16 + 0
        // = 1536 is the stairs slot; every other position decodes to palette slot 0.
        final long[] data = new long[4096 / 16];
        data[1536 / 16] = 1L;

        final NbtCompound blockStates = new NbtCompound();
        blockStates.put("palette", palette);
        blockStates.putLongArray("data", data);

        // Nibble index (floorMod(71, 16) * 16 + 0) * 16 + 0 = 1792: low nibble x=0, high x=1.
        final byte[] blockLight = new byte[2048];
        blockLight[1792 / 2] = (byte) 0x5D;

        final NbtCompound biomes = new NbtCompound();
        final NbtList biomePalette = new NbtList();
        biomePalette.add(NbtString.of("minecraft:plains"));
        biomes.put("palette", biomePalette);

        final NbtCompound section = new NbtCompound();
        section.putByte("Y", (byte) 4);
        section.put("block_states", blockStates);
        section.putByteArray("BlockLight", blockLight);
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
}

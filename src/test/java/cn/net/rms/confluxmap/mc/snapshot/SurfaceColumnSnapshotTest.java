package cn.net.rms.confluxmap.mc.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.net.rms.confluxmap.mc.color.McaTintResolver;
import cn.net.rms.confluxmap.mc.color.SpriteColorSampler;
import cn.net.rms.confluxmap.server.NbtChunkColumnSource;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The NBT-to-{@link SurfaceColumn} handoff behind the singleplayer MCA import, extended past
 * {@link McaSurfaceColumnTest}'s single-section fixture: negative world Y decodes through
 * floorDiv/floorMod into negative sections (never clamped or bled from a neighbor), the
 * y=15/16 section boundary reads two distinct blocks with per-section light nibbles, and the
 * biome palette index follows its quart layout in x, z and y with one palette per section.
 *
 * <p>This is deliberately NOT the full end-to-end {@code NBT -> ChunkSnapshot}: the shared
 * scan only runs through {@link McChunkSnapshotFactory#snapshotSurfaceFromColumn}, which
 * dereferences {@code client.world} before any guard, and a bare JUnit bootstrap can neither
 * construct a {@code MinecraftClient} (RunArgs, GLFW, resource loader) nor reach the private
 * {@code surfaceScan}. The last test pins that gap as executable documentation; closing it
 * needs a gametest or a null-world seam in the factory.
 */
class SurfaceColumnSnapshotTest {
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
    void negativeWorldYDecodesThroughFloorDivIntoNegativeSections() {
        final McaSurfaceColumn column = column();

        // Fixture sanity: the 9-bit heightmap entry for column (0,0) reads back with
        // Heightmap.get semantics (first free cell, offset from the chunk's yPos bottom).
        assertEquals(BOTTOM_Y, column.bottomY());
        assertEquals(20, column.motionBlockingTop(0, 0));

        // Y -64 is the first chunk of section -4: floorDiv(-64, 16) = -4 exactly.
        assertEquals(Blocks.BEDROCK.getDefaultState(), column.stateAt(0, -64, 0));
        // Y -1 is the LAST chunk of section -1: floorDiv(-1, 16) = -1, floorMod(-1, 16) = 15.
        // A truncating-division decode would look in section 0 (or 15) instead.
        assertEquals(Blocks.DIRT.getDefaultState(), column.stateAt(0, -1, 0));
        assertEquals(Blocks.BEDROCK.getDefaultState(), column.stateAt(0, -2, 0));
        assertEquals(Blocks.BEDROCK.getDefaultState(), column.stateAt(0, -16, 0));
        // Gaps read as air, never as a neighboring section's block: section -2 is missing
        // entirely and Y -65 lies below the chunk's yPos bottom.
        assertEquals(Blocks.AIR.getDefaultState(), column.stateAt(0, -17, 0));
        assertEquals(Blocks.AIR.getDefaultState(), column.stateAt(0, -65, 0));
    }

    @Test
    void y15Y16BoundaryReadsDistinctBlocksAcrossSections() {
        final McaSurfaceColumn column = column();

        // Clay fills one chunk at the top of section 0; gravel fills all of section 1.
        assertEquals(Blocks.STONE.getDefaultState(), column.stateAt(8, 14, 8));
        assertEquals(Blocks.CLAY.getDefaultState(), column.stateAt(8, 15, 8));
        assertEquals(Blocks.GRAVEL.getDefaultState(), column.stateAt(8, 16, 8));
        assertEquals(Blocks.GRAVEL.getDefaultState(), column.stateAt(8, 31, 8));
        assertEquals(Blocks.AIR.getDefaultState(), column.stateAt(8, 32, 8));
    }

    @Test
    void blockLightNibblesDecodePerSectionAcrossNegativeY() {
        final NbtChunkColumnSource source = source();
        final McaSurfaceColumn column = column();

        // The column's y convention: blockLightAt(x, y, z) surfaces the nibble stored AT y,
        // because the scan calls it with y-1 against blockLightAbove's surfaceY+1 read.
        // Both sides of that hop are pinned here: the source reads the stored nibble at
        // surfaceY + 1, the column shifts the query down by one.
        assertEquals(5, source.blockLightAbove(1, -2, 0));
        assertEquals(5, column.blockLightAt(1, -1, 0));
        assertEquals(9, source.blockLightAbove(0, -2, 0));
        assertEquals(9, column.blockLightAt(0, -1, 0));

        // The same section one chunk lower (local 14): untouched nibble stays 0.
        assertEquals(0, column.blockLightAt(0, -2, 0));

        // Section 0 carries its own nibbles: 9 at (0, 15, 0) and 12 in the high nibble at
        // (15, 15, 15) - the last nibble of the section's 2048-byte array.
        assertEquals(9, column.blockLightAt(0, 15, 0));
        assertEquals(12, column.blockLightAt(15, 15, 15));

        // Section 1 exists but stores no BlockLight array: 0, never section 0's value at the
        // mirrored local position (floorMod(16, 16) = 0 would alias local 15 without the
        // per-section lookup).
        assertEquals(0, column.blockLightAt(0, 16, 0));
        // Missing sections read 0 on both sides of the stored range.
        assertEquals(0, column.blockLightAt(0, -17, 0));
        assertEquals(0, column.blockLightAt(0, 32, 0));
    }

    @Test
    void biomePaletteIndexingIsQuartScopedXThenZThenY() {
        final McaSurfaceColumn column = column();

        // Section 0's quart layout (index = (quartY * 4 + z/4) * 4 + x/4): plains default,
        // desert at quart x 1, jungle at quart z 1, desert again at quart y 1. The x and z
        // steps are the orientation the shared scan relies on when it queries surfaceY.
        assertEquals("minecraft:plains", column.biomeIdAt(0, 0, 0));
        assertEquals("minecraft:plains", column.biomeIdAt(3, 3, 3));
        assertEquals("minecraft:desert", column.biomeIdAt(4, 0, 0));
        assertEquals("minecraft:desert", column.biomeIdAt(7, 0, 0));
        assertEquals("minecraft:plains", column.biomeIdAt(8, 0, 0));
        assertEquals("minecraft:jungle", column.biomeIdAt(0, 0, 4));
        assertEquals("minecraft:jungle", column.biomeIdAt(0, 0, 7));
        assertEquals("minecraft:plains", column.biomeIdAt(0, 0, 8));
        // Y moves in quarts of four within the section.
        assertEquals("minecraft:desert", column.biomeIdAt(0, 4, 0));
        assertEquals("minecraft:desert", column.biomeIdAt(0, 7, 0));
        assertEquals("minecraft:plains", column.biomeIdAt(0, 8, 0));
        assertEquals("minecraft:plains", column.biomeIdAt(0, 15, 0));
    }

    @Test
    void biomePalettesArePerSectionAndNegativeYQuartMapsInsideThem() {
        final McaSurfaceColumn column = column();

        // Section -1 names its own single-entry palette. Y -1 (local 15) lands in quart y 3
        // (index 48) and Y -16 (local 0) in quart y 0 (index 0); both decode to the one entry,
        // proving the negative-y quart arithmetic stays inside the section's own container.
        assertEquals("minecraft:snowy_taiga", column.biomeIdAt(0, -1, 0));
        assertEquals("minecraft:snowy_taiga", column.biomeIdAt(0, -16, 0));

        // Section 1 stores no biomes compound: a null name (the scan falls back), while the
        // neighboring section 0 still names plains at the same x/z.
        assertNull(column.biomeIdAt(0, 16, 0));
        assertEquals("minecraft:plains", column.biomeIdAt(0, 15, 0));
        // Missing sections yield null too.
        assertNull(column.biomeIdAt(0, 32, 0));
        assertNull(column.biomeIdAt(0, 300, 0));
    }

    @Test
    void snapshotSurfaceFromColumnStaysOutOfBareBootstrapReach() {
        // The shared scan is only reachable through snapshotSurfaceFromColumn, which needs a
        // real ClientWorld for the color/opacity context. A real MinecraftClient cannot exist
        // under this bootstrap (RunArgs/GLFW/resource loading), so the entry declines with
        // null instead of scanning. The true end-to-end region-NBT -> ChunkSnapshot color
        // pipeline therefore needs a gametest or a null-world seam in the factory; until then
        // this pins the guarded decline.
        final McChunkSnapshotFactory factory = new McChunkSnapshotFactory(null, null, null);
        assertNull(factory.snapshotSurfaceFromColumn(CHUNK_X, CHUNK_Z, column(), 42L, 7L));
    }

    private static McaSurfaceColumn column() {
        return new McaSurfaceColumn(
            CHUNK_X, CHUNK_Z, source(), null, null,
            new McaTintResolver(null), new SpriteColorSampler(null)
        );
    }

    private static NbtChunkColumnSource source() {
        return new NbtChunkColumnSource(modernChunk(sections()));
    }

    /** A minimal modern (1.18+ layout) full chunk at yPos -4 with a one-column heightmap. */
    private static NbtCompound modernChunk(final NbtList sections) {
        final NbtCompound root = new NbtCompound();
        root.putString("Status", "full");
        root.putLong("LastUpdate", 42L);
        root.putInt("yPos", BOTTOM_Y / 16);
        final NbtCompound heightmaps = new NbtCompound();
        heightmaps.putLongArray("MOTION_BLOCKING", heightmap());
        root.put("Heightmaps", heightmaps);
        root.put("sections", sections);
        return root;
    }

    /**
     * 256 nine-bit heightmap entries (index {@code z * 16 + x}), LSB-first non-spanning like
     * {@code PackedBits} decodes them: column (0, 0) holds its first free cell at Y 20
     * (84 above the Y -64 bottom), everything else stays at the world bottom.
     */
    private static long[] heightmap() {
        final long[] words = newPacked(9, 256);
        setPacked(words, 9, 0, 20 - BOTTOM_Y);
        return words;
    }

    /**
     * Four sections around the negative-y and 15/16 boundaries:
     *
     * <ul>
     *   <li>Y -4 (world -64..-49): uniform bedrock, no packed data (empty data decodes to
     *       palette slot 0).</li>
     *   <li>Y -1 (world -16..-1): bedrock with dirt at (x=0, y=-1, z=0); block light 9/5 at
     *       y -1 for x 0/1 of z 0; a single-entry snowy_taiga biome palette.</li>
     *   <li>Y 0 (world 0..15): stone with clay at (x=8, y=15, z=8); block light 9 at
     *       (0, 15, 0) and 12 at (15, 15, 15); a three-entry biome palette spread over the
     *       x/z/y quart layout.</li>
     *   <li>Y 1 (world 16..31): uniform gravel, no BlockLight array and no biomes.</li>
     * </ul>
     */
    private static NbtList sections() {
        final NbtList sections = new NbtList();

        final NbtList bottomPalette = palette("minecraft:bedrock");
        sections.add(section(-4, bottomPalette, null, null, null));

        final long[] negativeStates = newPacked(4, 4096);
        setPacked(negativeStates, 4, (15 * 16 + 0) * 16 + 0, 1); // (x=0, y=-1, z=0) -> dirt
        final byte[] negativeLight = new byte[2048];
        negativeLight[3840 >>> 1] = (byte) (9 | (5 << 4)); // low nibble x=0, high x=1 at y=-1
        sections.add(section(
            -1,
            palette("minecraft:bedrock", "minecraft:dirt"),
            negativeStates,
            negativeLight,
            biomes(biomePalette("minecraft:snowy_taiga"), null)
        ));

        final long[] surfaceStates = newPacked(4, 4096);
        setPacked(surfaceStates, 4, (15 * 16 + 8) * 16 + 8, 1); // (x=8, y=15, z=8) -> clay
        final byte[] surfaceLight = new byte[2048];
        surfaceLight[3840 >>> 1] = 9; // low nibble at (x=0, y=15, z=0)
        surfaceLight[4095 >>> 1] = (byte) (12 << 4); // high nibble at (x=15, y=15, z=15)
        final long[] surfaceBiomes = newPacked(2, 64);
        setPacked(surfaceBiomes, 2, 1, 1); // quart x 1 -> desert
        setPacked(surfaceBiomes, 2, 4, 2); // quart z 1 -> jungle
        setPacked(surfaceBiomes, 2, 16, 1); // quart y 1 -> desert
        sections.add(section(
            0,
            palette("minecraft:stone", "minecraft:clay"),
            surfaceStates,
            surfaceLight,
            biomes(
                biomePalette("minecraft:plains", "minecraft:desert", "minecraft:jungle"),
                surfaceBiomes
            )
        ));

        sections.add(section(1, palette("minecraft:gravel"), null, null, null));
        return sections;
    }

    private static NbtCompound section(
        final int y,
        final NbtList blockPalette,
        final long[] stateData,
        final byte[] blockLight,
        final NbtCompound biomeContainer
    ) {
        final NbtCompound blockStates = new NbtCompound();
        blockStates.put("palette", blockPalette);
        if (stateData != null) {
            blockStates.putLongArray("data", stateData);
        }
        final NbtCompound compound = new NbtCompound();
        compound.putByte("Y", (byte) y);
        compound.put("block_states", blockStates);
        if (blockLight != null) {
            compound.putByteArray("BlockLight", blockLight);
        }
        if (biomeContainer != null) {
            compound.put("biomes", biomeContainer);
        }
        return compound;
    }

    private static NbtList palette(final String... names) {
        final NbtList palette = new NbtList();
        for (final String name : names) {
            final NbtCompound entry = new NbtCompound();
            entry.putString("Name", name);
            palette.add(entry);
        }
        return palette;
    }

    private static NbtList biomePalette(final String... names) {
        final NbtList palette = new NbtList();
        for (final String name : names) {
            palette.add(NbtString.of(name));
        }
        return palette;
    }

    private static NbtCompound biomes(final NbtList palette, final long[] data) {
        final NbtCompound biomes = new NbtCompound();
        biomes.put("palette", palette);
        if (data != null) {
            biomes.putLongArray("data", data);
        }
        return biomes;
    }

    /** A fresh non-spanning packed array for {@code entries} values of {@code bits} each. */
    private static long[] newPacked(final int bits, final int entries) {
        final int perWord = 64 / bits;
        return new long[(entries + perWord - 1) / perWord];
    }

    /** Writes one value the way {@code PackedBits.decode} reads it: LSB-first, non-spanning. */
    private static void setPacked(final long[] words, final int bits, final int index, final int value) {
        final int perWord = 64 / bits;
        words[index / perWord] |= (long) value << ((index % perWord) * bits);
    }
}

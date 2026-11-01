package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.compat.Nbts;
import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.PackedBits;
import cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

/**
 * Serialized-region implementation of {@link ChunkColumnSource}.
 *
 * <p>Public (rather than package-private like its peers) because it backs two consumers across
 * package boundaries: the companion's chunk summaries and the singleplayer MCA import, whose
 * {@code mc.snapshot.McaSurfaceColumn} threads parsed region NBT into the shared surface scan
 * through this class's exact-state and biome-name extensions.
 */
public final class NbtChunkColumnSource implements ChunkColumnSource {
    private final boolean generated;
    private final long revision;
    private final int bottomY;
    private final long[] heights;
    private final long[] oceanFloor;
    private final int[] legacyBiomes;
    private final List<Section> sections;

    public NbtChunkColumnSource(final NbtCompound root) {
        if (root == null) {
            generated = false;
            revision = 0L;
            bottomY = 0;
            heights = new long[0];
            oceanFloor = new long[0];
            legacyBiomes = new int[0];
            sections = List.of();
            return;
        }
        final boolean legacy = Nbts.hasCompound(root, "Level");
        final NbtCompound level = legacy ? Nbts.compound(root, "Level") : root;
        final String status = Nbts.string(level, "Status");
        final NbtCompound heightmaps = Nbts.compound(level, "Heightmaps");
        final long[] motionBlocking = Nbts.longArray(heightmaps, "MOTION_BLOCKING");
        generated = ("full".equals(status) || "minecraft:full".equals(status))
            && motionBlocking.length != 0;
        revision = generated ? Nbts.longValue(level, "LastUpdate") : 0L;
        bottomY = legacy ? 0 : Nbts.integer(level, "yPos") * 16;
        heights = generated ? motionBlocking : new long[0];
        oceanFloor = generated ? Nbts.longArray(heightmaps, "OCEAN_FLOOR") : new long[0];
        legacyBiomes = generated ? Nbts.intArray(level, "Biomes") : new int[0];
        final String sectionsKey = legacy ? "Sections" : "sections";
        sections = generated ? parseSections(Nbts.list(level, sectionsKey, 10)) : List.of();
    }

    @Override
    public boolean generated() {
        return generated;
    }

    @Override
    public long revision() {
        return revision;
    }

    @Override
    public int bottomY() {
        return bottomY;
    }

    @Override
    public int motionBlockingHeight(final int x, final int z) {
        return bottomY + PackedBits.decode(heights, 9, columnIndex(x, z));
    }

    @Override
    public int oceanFloorHeight(final int x, final int z) {
        return oceanFloor.length == 0
            ? NO_HEIGHT
            : bottomY + PackedBits.decode(oceanFloor, 9, columnIndex(x, z));
    }

    @Override
    public String blockNameAt(final int x, final int y, final int z) {
        final Section section = sectionAt(y);
        return section == null ? "minecraft:air" : section.names[paletteIndex(section, x, y, z)];
    }

    @Override
    public SurfaceKind fluidKindAt(final int x, final int y, final int z) {
        final Section section = sectionAt(y);
        return section == null ? SurfaceKind.UNKNOWN : section.fluidKinds[paletteIndex(section, x, y, z)];
    }

    /**
     * The exact registry state behind {@link #blockNameAt}, for the single-player MCA snapshot
     * path that must match live capture with zero color drift. Missing sections and unresolvable
     * block ids fall back to air; unknown properties or values fall back to the block default per
     * property. Resolution is lazy per section - the companion path never calls this, so its
     * summaries neither touch the client registry nor pay for it.
     */
    public BlockState blockStateAt(final int x, final int y, final int z) {
        final Section section = sectionAt(y);
        return section == null
            ? Blocks.AIR.getDefaultState()
            : section.stateAt(paletteIndex(section, x, y, z));
    }

    @Override
    public int biomeIdAt(final int x, final int y, final int z) {
        if (legacyBiomes.length != 0) {
            final int quartY = Math.max(0, Math.min(63, Math.floorDiv(y, 4)));
            final int index = (x >>> 2) + ((z >>> 2) * 4) + quartY * 16;
            return index >= 0 && index < legacyBiomes.length ? legacyBiomes[index] : 1;
        }
        final int sectionY = Math.floorDiv(y, 16);
        for (final Section section : sections) {
            if (section.y != sectionY) {
                continue;
            }
            final int quartY = Math.floorMod(y, 16) >>> 2;
            final int index = (quartY * 4 + (z >>> 2)) * 4 + (x >>> 2);
            final int paletteIndex = section.biomeData.length == 0
                ? 0
                : PackedBits.decode(section.biomeData, section.biomeBits, index);
            return section.biomeIds[Math.min(paletteIndex, section.biomeIds.length - 1)];
        }
        return 1;
    }

    /**
     * The raw biome palette name ("minecraft:plains" form) behind {@link #biomeIdAt}, for exact
     * client tint matching. Returns null whenever no name is stored - the legacy {@code Biomes}
     * int-array path, a missing section, or an empty palette - and the caller falls back to
     * {@link #biomeIdAt} itself.
     */
    public String biomeNameAt(final int x, final int y, final int z) {
        if (legacyBiomes.length != 0) {
            return null;
        }
        final int sectionY = Math.floorDiv(y, 16);
        for (final Section section : sections) {
            if (section.y != sectionY) {
                continue;
            }
            final int quartY = Math.floorMod(y, 16) >>> 2;
            final int index = (quartY * 4 + (z >>> 2)) * 4 + (x >>> 2);
            final int paletteIndex = section.biomeData.length == 0
                ? 0
                : PackedBits.decode(section.biomeData, section.biomeBits, index);
            return section.biomeNames[Math.min(paletteIndex, section.biomeNames.length - 1)];
        }
        return null;
    }

    @Override
    public int blockLightAbove(final int x, final int surfaceY, final int z) {
        final int y = surfaceY + 1;
        final Section section = sectionAt(y);
        return section == null ? 0 : nibble(section.blockLight, x, y, z);
    }

    private static List<Section> parseSections(final NbtList list) {
        final List<Section> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            final NbtCompound section = Nbts.compound(list, i);
            final int y = Nbts.byteValue(section, "Y");
            final boolean modern = Nbts.hasCompound(section, "block_states");
            final NbtCompound blockStates = modern ? Nbts.compound(section, "block_states") : section;
            final String paletteKey = modern ? "palette" : "Palette";
            final String dataKey = modern ? "data" : "BlockStates";
            final NbtList typedPalette = Nbts.list(blockStates, paletteKey, 10);
            // Versions before 1.21.5 pick the list by element type; 26.3 writes default states
            // as a string-typed palette, so a compound-typed miss falls back to a string read.
            final NbtList palette = typedPalette.isEmpty()
                ? Nbts.list(blockStates, paletteKey, 8)
                : typedPalette;
            final String[] names = new String[Math.max(1, palette.size())];
            final SurfaceKind[] fluidKinds = new SurfaceKind[names.length];
            final NbtCompound[] properties = new NbtCompound[names.length];
            for (int p = 0; p < palette.size(); p++) {
                // Palette entry forms: {"Name": ..., "Properties": {...}} before 26.3 and
                // {"id": ..., "properties": {...}} after; default states are bare id strings.
                // A mixed palette serializes its strings as {"" : id} (ListTag#wrapIfNeeded),
                // but vanilla's reader unwraps that form while loading, so in-memory lists hold
                // real strings next to the compounds and the empty-key probe only guards data
                // that never passed through a vanilla reader. The compound read must go first:
                // NbtList.getString returns the element's NBT text for compound entries on
                // older versions, so a string probe would mangle those.
                final NbtCompound entry = Nbts.compound(palette, p);
                String name = Nbts.string(entry, "Name");
                if (name.isEmpty()) {
                    name = Nbts.string(entry, "id");
                }
                if (name.isEmpty()) {
                    name = Nbts.string(entry, "");
                }
                final NbtCompound entryProperties = Nbts.hasCompound(entry, "properties")
                    ? Nbts.compound(entry, "properties")
                    : Nbts.compound(entry, "Properties");
                names[p] = name.isEmpty() ? Nbts.string(palette, p) : name;
                fluidKinds[p] = fluidKind(names[p], entryProperties);
                properties[p] = entryProperties;
            }
            if (palette.isEmpty()) {
                names[0] = "minecraft:air";
                fluidKinds[0] = SurfaceKind.UNKNOWN;
            }
            final long[] states = Nbts.longArray(blockStates, dataKey);
            final int bits = Math.max(4, bitsFor(names.length));
            final NbtCompound biomeContainer = modern ? Nbts.compound(section, "biomes") : new NbtCompound();
            final NbtList biomePalette = Nbts.list(biomeContainer, "palette", 8);
            final int[] biomeIds = new int[Math.max(1, biomePalette.size())];
            final String[] biomeNames = new String[Math.max(1, biomePalette.size())];
            for (int p = 0; p < biomePalette.size(); p++) {
                final String biomeName = Nbts.string(biomePalette, p);
                biomeNames[p] = biomeName;
                biomeIds[p] = biomeId(biomeName);
            }
            if (biomePalette.isEmpty()) {
                // No stored name to hand out either; biomeNameAt reports null and the caller
                // falls back to biomeIdAt's plains default here.
                biomeIds[0] = 1;
            }
            final long[] biomeData = Nbts.longArray(biomeContainer, "data");
            result.add(new Section(
                y,
                names,
                fluidKinds,
                properties,
                states,
                bits,
                biomeIds,
                biomeNames,
                biomeData,
                Math.max(1, bitsFor(biomeIds.length)),
                Nbts.byteArray(section, "BlockLight"),
                new AtomicReference<>()
            ));
        }
        return result;
    }

    private Section sectionAt(final int y) {
        final int sectionY = Math.floorDiv(y, 16);
        for (final Section section : sections) {
            if (section.y == sectionY) {
                return section;
            }
        }
        return null;
    }

    private static int paletteIndex(final Section section, final int x, final int y, final int z) {
        final int localIndex = (Math.floorMod(y, 16) * 16 + z) * 16 + x;
        final int decoded = section.states.length == 0
            ? 0
            : PackedBits.decode(section.states, section.bits, localIndex);
        return Math.min(decoded, section.names.length - 1);
    }

    private static SurfaceKind fluidKind(final String name, final NbtCompound properties) {
        if ("true".equals(Nbts.string(properties, "waterlogged"))
            || name.contains("water") || "minecraft:kelp".equals(name) || "minecraft:kelp_plant".equals(name)
            || "minecraft:seagrass".equals(name) || "minecraft:tall_seagrass".equals(name)
            || "minecraft:bubble_column".equals(name) || "minecraft:sea_pickle".equals(name)) {
            return SurfaceKind.WATER;
        }
        return name.contains("lava") ? SurfaceKind.LAVA : SurfaceKind.UNKNOWN;
    }

    private static int columnIndex(final int x, final int z) {
        return z * 16 + x;
    }

    private static int biomeId(final String name) {
        final int separator = name.indexOf(':');
        final String path = separator >= 0 ? name.substring(separator + 1) : name;
        return CubiomesBiomeIds.idForName(path).orElse(1);
    }

    /**
     * Resolves one serialized palette entry against the live registry with the snapshot baker's
     * rules: an unknown id yields null (the caller substitutes air), and each property falls back
     * to the block default when the name or value is unknown, so version drift on one property
     * cannot lose the whole state. Looping over the state's own properties and probing the stored
     * compound keeps every NBT access inside {@link Nbts}.
     */
    private static BlockState resolveBlockState(final String name, final NbtCompound properties) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        final Identifier id = Identifier.tryParse(name);
        final Block block = id == null ? null : Regs.block(id).orElse(null);
        if (block == null) {
            return null;
        }
        BlockState resolved = block.getDefaultState();
        for (final Property<?> property : resolved.getProperties()) {
            resolved = withProperty(
                resolved,
                property,
                properties == null ? "" : Nbts.string(properties, property.getName())
            );
        }
        return resolved;
    }

    private static BlockState withProperty(
        final BlockState state,
        final Property<?> property,
        final String value
    ) {
        if (value.isEmpty()) {
            return state;
        }
        final Optional<?> parsed = property.parse(value);
        return parsed.isPresent() ? withParsed(state, property, parsed.get()) : state;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState withParsed(final BlockState state, final Property property, final Object value) {
        return state.with(property, (Comparable) value);
    }

    private static int bitsFor(final int size) {
        int bits = 0;
        int value = Math.max(1, size - 1);
        while (value > 0) {
            bits++;
            value >>>= 1;
        }
        return bits;
    }

    private static int nibble(final byte[] values, final int x, final int y, final int z) {
        final int index = (Math.floorMod(y, 16) * 16 + z) * 16 + x;
        final int byteIndex = index >>> 1;
        if (byteIndex >= values.length) {
            return 0;
        }
        return (values[byteIndex] >>> ((index & 1) * 4)) & 0xF;
    }

    private record Section(
        int y,
        String[] names,
        SurfaceKind[] fluidKinds,
        NbtCompound[] properties,
        long[] states,
        int bits,
        int[] biomeIds,
        String[] biomeNames,
        long[] biomeData,
        int biomeBits,
        byte[] blockLight,
        AtomicReference<BlockState[]> resolvedStates
    ) {

        /**
         * The resolved registry state for one palette slot, or air when the block id is unknown.
         * The array is parsed once per section on first use and published through the atomic
         * reference, so racing worker threads share one parse and the companion path (which never
         * calls this) sees no registry work at all.
         */
        BlockState stateAt(final int paletteIndex) {
            BlockState[] resolved = resolvedStates.get();
            if (resolved == null) {
                resolved = resolveStates();
                if (!resolvedStates.compareAndSet(null, resolved)) {
                    resolved = resolvedStates.get();
                }
            }
            final BlockState state = resolved[paletteIndex];
            return state == null ? Blocks.AIR.getDefaultState() : state;
        }

        private BlockState[] resolveStates() {
            final BlockState[] resolved = new BlockState[names.length];
            for (int p = 0; p < resolved.length; p++) {
                resolved[p] = resolveBlockState(names[p], properties[p]);
            }
            return resolved;
        }
    }
}

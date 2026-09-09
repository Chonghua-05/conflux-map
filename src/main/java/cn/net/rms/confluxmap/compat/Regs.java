package cn.net.rms.confluxmap.compat;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;

import java.util.Optional;

/**
 * The one place that knows how this Minecraft version exposes registries.
 *
 * <p>Three separate breaks are folded in here: 1.19.3 moved the registry classes out of
 * {@code net.minecraft.util.registry} and split the static keys into {@code Registries} /
 * {@code RegistryKeys}, 1.21.3 replaced {@code DynamicRegistryManager.get} with
 * {@code getOrThrow}, and 1.21.5 replaced {@code Registry.getOrEmpty} with
 * {@code getOptionalValue}. Since 1.18 a world also hands out a {@code RegistryEntry<Biome>}
 * rather than a bare {@code Biome}, so biome lookups go through {@link #biomeIdAt} instead of
 * leaking that type difference into callers.
 */
public final class Regs {
    private Regs() {
    }

    /** The biome registry backing {@code world}. */
    public static Registry<Biome> biomes(final Level world) {
        return world.registryAccess().lookupOrThrow(Registries.BIOME);
    }

    /** The static block registry. */
    public static Registry<Block> blocks() {
        return BuiltInRegistries.BLOCK;
    }

    /** The static item registry. */
    public static Registry<Item> items() {
        return BuiltInRegistries.ITEM;
    }

    /** The static entity-type registry. */
    public static Registry<EntityType<?>> entityTypes() {
        return BuiltInRegistries.ENTITY_TYPE;
    }

    /** Looks up a biome without exposing the registry API rename at 1.21.3. */
    public static Biome biome(final Registry<Biome> registry, final Identifier id) {
        return registry.getOptional(id).orElse(null);
    }

    /**
     * Looks a block up by identifier. Deliberately not {@code blocks().get(id)}: the block
     * registry is defaulted, so an unknown id silently resolves to air instead of reporting
     * absence, which would turn "unknown block" into "block with the air map colour".
     */
    public static Optional<Block> block(final Identifier id) {
        return blocks().getOptional(id);
    }

    /** Looks an item up by identifier without defaulting an unknown id to air. */
    public static Optional<Item> item(final Identifier id) {
        return items().getOptional(id);
    }

    /** Looks an entity type up by identifier without depending on generated constant owners. */
    public static Optional<EntityType<?>> entityType(final Identifier id) {
        return entityTypes().getOptional(id);
    }

    /** The registry identifier of an entity type. */
    public static Identifier entityTypeId(final EntityType<?> type) {
        return entityTypes().getKey(type);
    }

    /** The registry identifier of {@code block}. */
    public static Identifier blockId(final Block block) {
        return blocks().getKey(block);
    }

    /** The registry identifier of {@code item}. */
    public static Identifier itemId(final Item item) {
        return items().getKey(item);
    }

    /** The registry identifier of the biome at {@code pos}, or null when it has none. */
    public static Identifier biomeIdAt(final Level world, final BlockPos pos) {
        return world.getBiome(pos).unwrapKey().map(ResourceKey::identifier).orElse(null);
    }
}

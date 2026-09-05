package cn.net.rms.confluxmap.compat;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

//#if MC>=11903
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
//#else
//$$ import net.minecraft.util.registry.Registry;
//#endif

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
        //#if MC>=12103
        return world.registryAccess().lookupOrThrow(Registries.BIOME);
        //#elseif MC>=11903
        //$$ return world.getRegistryManager().get(RegistryKeys.BIOME);
        //#else
        //$$ return world.getRegistryManager().get(Registry.BIOME_KEY);
        //#endif
    }

    /** The static block registry. */
    public static Registry<Block> blocks() {
        //#if MC>=11903
        return BuiltInRegistries.BLOCK;
        //#else
        //$$ return Registry.BLOCK;
        //#endif
    }

    /** The static item registry. */
    public static Registry<Item> items() {
        //#if MC>=11903
        return BuiltInRegistries.ITEM;
        //#else
        //$$ return Registry.ITEM;
        //#endif
    }

    /** The static entity-type registry. */
    public static Registry<EntityType<?>> entityTypes() {
        //#if MC>=11903
        return BuiltInRegistries.ENTITY_TYPE;
        //#else
        //$$ return Registry.ENTITY_TYPE;
        //#endif
    }

    /** Looks up a biome without exposing the registry API rename at 1.21.3. */
    public static Biome biome(final Registry<Biome> registry, final Identifier id) {
        //#if MC>=12103
        return registry.getOptional(id).orElse(null);
        //#else
        //$$ return registry.get(id);
        //#endif
    }

    /**
     * Looks a block up by identifier. Deliberately not {@code blocks().get(id)}: the block
     * registry is defaulted, so an unknown id silently resolves to air instead of reporting
     * absence, which would turn "unknown block" into "block with the air map colour".
     */
    public static Optional<Block> block(final Identifier id) {
        //#if MC>=12105
        return blocks().getOptional(id);
        //#else
        //$$ return blocks().getOptionalValue(id);
        //#endif
    }

    /** Looks an item up by identifier without defaulting an unknown id to air. */
    public static Optional<Item> item(final Identifier id) {
        //#if MC>=12105
        return items().getOptional(id);
        //#else
        //$$ return items().getOptionalValue(id);
        //#endif
    }

    /** Looks an entity type up by identifier without depending on generated constant owners. */
    public static Optional<EntityType<?>> entityType(final Identifier id) {
        //#if MC>=12105
        return entityTypes().getOptional(id);
        //#else
        //$$ return entityTypes().getOptionalValue(id);
        //#endif
    }

    /** The registry identifier of an entity type. */
    public static Identifier entityTypeId(final EntityType<?> type) {
        //#if MC>=260100
        return entityTypes().getKey(type);
        //#else
        //$$ return entityTypes().getId(type);
        //#endif
    }

    /** The registry identifier of {@code block}. */
    public static Identifier blockId(final Block block) {
        //#if MC>=260100
        return blocks().getKey(block);
        //#else
        //$$ return blocks().getId(block);
        //#endif
    }

    /** The registry identifier of {@code item}. */
    public static Identifier itemId(final Item item) {
        //#if MC>=260100
        return items().getKey(item);
        //#else
        //$$ return items().getId(item);
        //#endif
    }

    /** The registry identifier of the biome at {@code pos}, or null when it has none. */
    public static Identifier biomeIdAt(final Level world, final BlockPos pos) {
        //#if MC>=11903
        return world.getBiome(pos).unwrapKey().map(ResourceKey::identifier).orElse(null);
        //#elseif MC>=11800
        //$$ return biomes(world).getId(world.getBiome(pos).value());
        //#else
        //$$ return biomes(world).getId(world.getBiome(pos));
        //#endif
    }
}

package cn.net.rms.confluxmap.compat;

import cn.net.rms.confluxmap.core.model.DimensionId;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/**
 * The one seam between Minecraft dimension identity (worlds, registry keys, identifiers) and
 * the core {@link DimensionId}. Same reasoning as {@link Ids}: one conversion here beats a
 * different inline shape at each call site, and any version drift in registry-key access
 * lands in one place.
 */
public final class Dimensions {
    private Dimensions() {
    }

    /** The dimension identity of a live world, client or server side. */
    public static DimensionId of(final World world) {
        return of(world.getRegistryKey().getValue());
    }

    /** The dimension identity of an {@link Identifier} such as a registry key's value. */
    public static DimensionId of(final Identifier id) {
        return DimensionId.of(id.getNamespace(), id.getPath());
    }

    /**
     * Position of {@code dimension} in {@code server.getWorlds()} iteration order. That order
     * is the wire contract: {@code HelloPolicyS2C} dimension descriptors and every indexed
     * dimension field are built from the same loop, so all index lookups must match it.
     * Returns -1 when the server does not hold the dimension.
     */
    public static int indexOf(final MinecraftServer server, final DimensionId dimension) {
        int index = 0;
        for (final ServerWorld world : server.getWorlds()) {
            if (of(world).equals(dimension)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    /** Position of {@code world} in {@code server.getWorlds()} iteration order; -1 when absent. */
    public static int indexOf(final MinecraftServer server, final ServerWorld world) {
        int index = 0;
        for (final ServerWorld candidate : server.getWorlds()) {
            if (candidate == world) {
                return index;
            }
            index++;
        }
        return -1;
    }
}

package cn.net.rms.confluxmap.compat;

import net.minecraft.resources.Identifier;

/**
 * The one place that knows how this Minecraft version builds an {@link Identifier}.
 *
 * <p>1.21 made the {@code Identifier} constructors private and moved construction to static
 * factories. Same reasoning as {@link Texts}: one seam here keeps version-sensitive details at
 * of the mod's channel, texture and registry-key call sites.
 */
public final class Ids {
    private Ids() {
    }

    /** Parses a full {@code namespace:path} identifier, defaulting the namespace to minecraft. */
    public static Identifier of(final String id) {
        return Identifier.parse(id);
    }

    /** Builds an identifier from an explicit namespace and path. */
    public static Identifier of(final String namespace, final String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }
}

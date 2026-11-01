package cn.net.rms.confluxmap.mc.color;

import cn.net.rms.confluxmap.core.predict.SyncedMaterialPalette;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.world.biome.Biome;

/**
 * Biome tint resolution for the singleplayer MCA import, per surface-color-sampling.md §3's
 * tint categories. Unlike {@link BiomeTintResolver} it never reads a live {@code ClientWorld}:
 * the caller hands in the {@link Biome} resolved from the column's stored biome palette, and
 * this class only asks that biome for its own grass/foliage/water colors. That makes it safe
 * to call from import worker threads and from the bare unit-test bootstrap alike.
 *
 * <p>The tint category of a state is decided by delegating to {@link
 * BiomeTintResolver#syncedTint} / {@link BiomeTintResolver#fixedSyncedTint}, the same
 * classification the live capture and the synchronized-palette paths use, so an imported chunk
 * cannot drift from a captured one on which states count as GRASS/FOLIAGE/WATER/FIXED. One
 * deliberate difference to {@link BiomeTintResolver#resolve}: states outside those four
 * categories fall back to white here instead of probing {@code BlockColors}, since that probe
 * needs the client's tint-provider registry plus a live world; vanilla's tinted blocks are all
 * covered by the four categories (redstone wire bakes its color into the sprite sample, §6),
 * so the fallback only concerns exotic modded providers.
 *
 * <p>Stateless beyond the wrapped resolver, whose category checks are pure; safe for
 * concurrent use.
 */
public final class McaTintResolver {
    /** Same no-tint convention as {@link BiomeTintResolver}: opaque white. */
    private static final int NO_TINT = 0xFFFFFFFF;

    private final BiomeTintResolver tints;

    /**
     * {@code client} is only carried for the wrapped resolver's (unused here) live path, so a
     * null client keeps every non-client resolution working - the MCA test bootstrap passes
     * null and exercises the FIXED/none categories plus the biome-missing fallbacks.
     */
    public McaTintResolver(final MinecraftClient client) {
        this.tints = new BiomeTintResolver(client);
    }

    /**
     * The tint (opaque ARGB, white = none) for {@code state} at {@code worldX}/{@code worldZ}
     * under {@code biome}. A null biome (palette name the client registry does not know, or no
     * stored name at all) still yields the fixed spruce/birch colors - those never consult the
     * biome - and white for the biome-sampled categories.
     */
    public int resolve(final BlockState state, final Biome biome, final int worldX, final int worldZ) {
        final SyncedMaterialPalette.Tint category = tints.syncedTint(state);
        switch (category) {
            case FIXED:
                return tints.fixedSyncedTint(state);
            case WATER:
                return biome == null ? NO_TINT : 0xFF000000 | biome.getWaterColor();
            case FOLIAGE:
                return biome == null ? NO_TINT : 0xFF000000 | biome.getFoliageColor();
            case GRASS:
                // Per-position like the game's own grass resolver: biome modifiers (swamp,
                // dark forest, badlands plateau) and the plain-temperature noise path both
                // key off the world x/z, so the imported map matches what the live scan
                // samples at the same column.
                return biome == null ? NO_TINT : 0xFF000000 | biome.getGrassColorAt(worldX, worldZ);
            default:
                return NO_TINT;
        }
    }
}

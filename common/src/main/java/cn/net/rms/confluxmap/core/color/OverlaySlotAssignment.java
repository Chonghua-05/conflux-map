package cn.net.rms.confluxmap.core.color;

import java.util.function.Predicate;

/**
 * The single interpretation of a walked block column into surface/overlay slots, shared by the
 * client's authoritative capture (BlockState tokens via {@code McChunkSnapshotFactory}) and the
 * name-based projections used by the companion's {@code ChunkColumnSummarizer} and pinned into
 * the native Anvil scanner by {@code OverlaySlotAssignmentSourceParityTest}. Before this seam
 * existed each caller re-derived the rules and they drifted: the client's descent branch filled
 * the foliage slot with air (a phantom tint baked into surface and water-floor colors), while
 * the no-descent branch already guarded against it.
 *
 * <p>The invariant, "column descent interpretation": walking a column top-down from the
 * heightmap through non-opaque blocks to the surface,
 * <ul>
 *   <li>the <em>surface</em> is the first opaque block the walk stops at, except when a thin
 *       cover sits directly on it (snow layer, carpet) - then the cover is promoted to the
 *       surface because it is what a viewer actually sees;</li>
 *   <li>the <em>transparent-overlay slot</em> holds a cover the map draws the surface through
 *       (the glass family) - the top-most block the walk crossed;</li>
 *   <li>the <em>foliage-overlay slot</em> holds decoration sitting directly on the surface -
 *       the bottom-most block the walk crossed, when distinct from the transparent one;</li>
 *   <li>{@code air} is nothing. It never fills any slot, in either walk direction, so an air
 *       gap inside a descended column ([glass][air][water]) contributes no overlay at all.</li>
 * </ul>
 *
 * <p>Both factories are pure: the caller performs the walk (which needs live opacity queries
 * on the client, or name tests on the server) and passes its results in; only the
 * interpretation lives here. Slot tokens are {@code null} when absent and their Y components
 * are meaningful only when the token is non-null.
 *
 * @param <B> the block token type (BlockState on the client, material id on the server)
 */
public record OverlaySlotAssignment<B>(
    B surface,
    int surfaceY,
    B transparentOverlay,
    int transparentOverlayY,
    B foliageOverlay,
    int foliageOverlayY
) {

    /** Exact material ids meaning "nothing"; the name-based projection of BlockState.isAir(). */
    public static final String[] AIR_MATERIAL_IDS = {
        "minecraft:air", "minecraft:cave_air", "minecraft:void_air"
    };

    /** Thin snow covers promoted to the surface; the name projection of SnowBlock layers. */
    public static final String[] SNOW_COVER_MATERIAL_IDS = {
        "minecraft:snow", "minecraft:powder_snow"
    };

    /** Suffixes of thin carpet covers promoted to the surface; the CarpetBlock name projection. */
    public static final String[] PROMOTED_COVER_SUFFIXES = {"_carpet"};

    /**
     * Interpret a column whose heightmap block was itself opaque, so the walk never ran. The
     * block above the surface ({@code above}, which the heightmap ignores - a snow layer,
     * carpet, flower, or air) is the only overlay candidate: it is either promoted to the
     * surface, becomes the foliage overlay, or - when air - fills no slot.
     */
    public static <B> OverlaySlotAssignment<B> forSurfaceColumn(
        final B surface,
        final int surfaceY,
        final B above,
        final Predicate<? super B> isAir,
        final Predicate<? super B> isPromotedCover
    ) {
        if (isAir.test(above)) {
            return new OverlaySlotAssignment<>(surface, surfaceY, null, 0, null, 0);
        }
        if (isPromotedCover.test(above)) {
            return new OverlaySlotAssignment<>(above, surfaceY + 1, null, 0, null, 0);
        }
        return new OverlaySlotAssignment<>(surface, surfaceY, null, 0, above, surfaceY + 1);
    }

    /**
     * Interpret a column the walk descended through: {@code topOverlay}/{@code bottomOverlay}
     * are the first and last non-opaque blocks it crossed (above the opaque {@code surface}).
     * Air is dropped from both ends before assignment, so an air gap directly above the
     * surface yields no promotion and no foliage slot.
     */
    public static <B> OverlaySlotAssignment<B> forDescent(
        final B surface,
        final int surfaceY,
        final B topOverlay,
        final int topOverlayY,
        final B bottomOverlay,
        final int bottomOverlayY,
        final Predicate<? super B> isAir,
        final Predicate<? super B> isPromotedCover
    ) {
        final B top = isAir.test(topOverlay) ? null : topOverlay;
        final B bottom = isAir.test(bottomOverlay) ? null : bottomOverlay;
        if (bottom != null && isPromotedCover.test(bottom)) {
            // Thin surface-cover promotion: the cover becomes the surface and only a distinct
            // block above it can still be seen through (a glass roof over a snow layer).
            final boolean separate = top != null && topOverlayY != bottomOverlayY;
            return new OverlaySlotAssignment<>(
                bottom, bottomOverlayY, separate ? top : null, separate ? topOverlayY : 0, null, 0
            );
        }
        final boolean separate = bottom != null && bottomOverlayY != topOverlayY;
        return new OverlaySlotAssignment<>(
            surface, surfaceY, top, top == null ? 0 : topOverlayY,
            separate ? bottom : null, separate ? bottomOverlayY : 0
        );
    }

    /**
     * Name-based air rule for material-id consumers. Exact ids only: a suffix match would
     * swallow unrelated blocks whose id merely ends in "air". The native scanner's
     * {@code cfxIsAir} must enumerate the same set - OverlaySlotAssignmentSourceParityTest
     * pins it.
     */
    public static boolean isAirMaterial(final String materialId) {
        for (final String air : AIR_MATERIAL_IDS) {
            if (air.equals(materialId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Name-based thin-cover rule for material-id consumers (snow layers and carpets). The
     * native scanner's {@code cfxIsSnow}/{@code cfxIsCarpet} must enumerate the same names -
     * OverlaySlotAssignmentSourceParityTest pins them.
     */
    public static boolean isPromotedCoverMaterial(final String materialId) {
        if (materialId == null) {
            return false;
        }
        for (final String snow : SNOW_COVER_MATERIAL_IDS) {
            if (snow.equals(materialId)) {
                return true;
            }
        }
        for (final String suffix : PROMOTED_COVER_SUFFIXES) {
            if (materialId.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}

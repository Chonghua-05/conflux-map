package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import java.util.Arrays;

/**
 * The uniform surface of a superflat dimension: every column is the same (kind, height, map
 * color, biome, fluid depth) sample, so the whole predicted underlay - and the companion's
 * residual-diff baseline - is this one value. Computed from the live {@code
 * FlatChunkGeneratorConfig} on whichever side owns the world ({@code server.FlatWorldBaseline})
 * and carried to multiplayer clients in {@code FLAT_BASELINE_S2C}.
 *
 * <p>No seed and no native library are involved anywhere downstream of this record: a flat
 * underlay composes from constants, and real-world differences (villages, player builds) arrive
 * as ordinary correction patches diffed against the same uniform sample.
 *
 * @param biomeId       cubiomes-space biome id (equal to the 1.17 raw registry id for vanilla biomes)
 * @param surfaceY      Y of the topmost non-air layer (the water surface for water-topped presets)
 * @param kind          {@link cn.net.rms.confluxmap.core.model.SurfaceKind} ordinal of the top layer
 * @param mapColorId    vanilla map color of the top block; {@code 255} = none (render via biome palette)
 * @param fluidDepth    consecutive water layers below the surface (0 for solid tops)
 * @param topMaterialId registry id of the top block; empty when unknown. A translucent top block
 *                      (a glass floor) renders with its own sampled colour and alpha instead of
 *                      the opaque map colour, like the authoritative capture of the same column.
 */
public record FlatBaseline(
    int biomeId,
    int surfaceY,
    int kind,
    int mapColorId,
    int fluidDepth,
    String topMaterialId
) {

    public FlatBaseline {
        topMaterialId = topMaterialId == null ? "" : topMaterialId;
        // Server-synced values feed the packing planes and grid fills directly; a hostile or
        // broken companion must fail loudly at this trust boundary instead of throwing from a
        // worker's first compose forever.
        if (surfaceY < -2048 || surfaceY > 2047
            || kind < 0 || kind >= SurfaceKind.values().length
            || fluidDepth < 0 || fluidDepth > 255) {
            throw new IllegalArgumentException(
                "flat baseline out of range: surfaceY=" + surfaceY
                    + " kind=" + kind + " fluidDepth=" + fluidDepth
            );
        }
    }

    public FlatBaseline(
        final int biomeId,
        final int surfaceY,
        final int kind,
        final int mapColorId,
        final int fluidDepth
    ) {
        this(biomeId, surfaceY, kind, mapColorId, fluidDepth, "");
    }

    /** A margin-inclusive grid where every cell is this surface's biome and height. */
    public BaselineGrid toBaselineGrid() {
        final BaselineGrid grid = new BaselineGrid();
        Arrays.fill(grid.biomeId, biomeId);
        Arrays.fill(grid.terrainY, surfaceY);
        return grid;
    }

    /** A margin-inclusive derived grid where every cell is this surface's kind/height/fluid. */
    public DerivedGrid toDerivedGrid() {
        final DerivedGrid derived = new DerivedGrid();
        // A VOID top layer is "known, no surface" - the sentinel the deriver writes - so packed
        // round-trips and teleport staging agree with sampled dimensions instead of seeing Y=0.
        Arrays.fill(derived.surfaceY,
            kind == SurfaceKind.VOID.ordinal() ? BaselineGrid.NO_SURFACE : surfaceY);
        Arrays.fill(derived.kind, (byte) kind);
        Arrays.fill(derived.fluidDepth, fluidDepth);
        return derived;
    }
}

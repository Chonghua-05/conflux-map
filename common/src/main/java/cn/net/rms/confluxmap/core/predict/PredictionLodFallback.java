package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.util.TileMath;
import java.util.Optional;

/**
 * Geometry for the "parent-LOD underlay" the fullscreen map draws while a predicted tile is still
 * composing: the nearest already-uploaded coarser tile covers the same ground, so the missing child
 * can be drawn as one quadrant of its parent instead of a black hole.
 *
 * <p>Kept in {@code core} (and free of any renderer type) so the quadrant math is unit-testable;
 * the screen only has to supply the quad and the UV window.
 */
public final class PredictionLodFallback {
    private PredictionLodFallback() {
    }

    /** UV window of one child tile inside its ancestor texture, all values in {@code [0, 1]}. */
    public record Window(float u0, float v0, float u1, float v1) {
        public Window {
            if (!(u0 >= 0f && v0 >= 0f && u1 <= 1f && v1 <= 1f && u0 <= u1 && v0 <= v1)) {
                throw new IllegalArgumentException("invalid fallback uv window");
            }
        }

        public float width() {
            return u1 - u0;
        }

        public float height() {
            return v1 - v0;
        }
    }

    /**
     * The ancestor {@code levels} LODs coarser that contains {@code child}, or empty when that
     * would leave the LOD domain (the top of the pyramid has no parent to draw).
     */
    public static Optional<TileKey> ancestor(final TileKey child, final int levels) {
        if (child == null || levels <= 0) {
            return Optional.empty();
        }
        final int ancestorLod = child.lod() + levels;
        // Bounded by MAX_LOD, so the span shift below can never overflow.
        if (ancestorLod > TileMath.MAX_LOD) {
            return Optional.empty();
        }
        final int span = 1 << levels;
        return Optional.of(new TileKey(
            child.world(), child.dimension(), child.layerId(), ancestorLod,
            Math.floorDiv(child.tileX(), span), Math.floorDiv(child.tileZ(), span)
        ));
    }

    /**
     * Where {@code child} sits inside {@link #ancestor(TileKey, int)}'s texture. Empty for the same
     * cases as {@link #ancestor}: callers draw the full quad only when there is no parent.
     */
    public static Optional<Window> window(final TileKey child, final int levels) {
        if (ancestor(child, levels).isEmpty()) {
            return Optional.empty();
        }
        final int span = 1 << levels;
        // floorMod keeps negative tile coordinates in the northern/western half instead of
        // mirroring the quadrant, which floorDiv above already resolves pairwise.
        final int offsetX = Math.floorMod(child.tileX(), span);
        final int offsetZ = Math.floorMod(child.tileZ(), span);
        final float cell = 1f / span;
        final float u0 = offsetX * cell;
        final float v0 = offsetZ * cell;
        return Optional.of(new Window(u0, v0, u0 + cell, v0 + cell));
    }
}

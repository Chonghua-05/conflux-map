package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.SurfaceKind;

/**
 * One derived tile's surface plane compacted to a {@code short} per cell: the low 12 bits hold a
 * signed surface height, the high 4 bits a {@link SurfaceKind} ordinal. A {@link DerivedGrid}
 * keeps {@code kind == VOID} and {@code surfaceY == NO_SURFACE} in lockstep ({@link
 * BaselineDeriver} establishes that 1:1 mapping and {@link CanopyStylizer} never writes on void
 * columns), so VOID doubles as the no-surface encoding and no height sentinel is wasted: VOID
 * cells simply carry zero in the height slot, which {@link #unpackHeight} maps back to {@link
 * BaselineGrid#NO_SURFACE}.
 *
 * <p>Planes are margin-inclusive ({@link BaselineGrid#SIZE}<sup>2</sup> cells, laid out by {@link
 * BaselineGrid#index}) because directional relief reads the neighbour cells around each output
 * pixel — packing only the inner 256² would lose the ring the derived grid still needs.
 */
final class PackedSurfacePlane {
    /** Cells in one margin-inclusive plane, matching every {@link DerivedGrid} array. */
    static final int CELLS = BaselineGrid.SIZE * BaselineGrid.SIZE;

    private static final int HEIGHT_BITS = 12;
    /** Low {@link #HEIGHT_BITS} bits: the surface height's two's-complement value. */
    private static final int HEIGHT_MASK = (1 << HEIGHT_BITS) - 1;
    /** Kind ordinals the 4-bit slot can address; {@link SurfaceKind} must never outgrow this. */
    private static final int KINDS = 1 << (Short.SIZE - HEIGHT_BITS);
    /** Signed range of {@link #HEIGHT_BITS} bits; vanilla's -64..320 world fits with room to spare. */
    private static final int MIN_HEIGHT = -(1 << (HEIGHT_BITS - 1));
    private static final int MAX_HEIGHT = (1 << (HEIGHT_BITS - 1)) - 1;

    static {
        // Fail at class-init rather than silently truncating a kind ordinal into a neighbour's slot.
        if (SurfaceKind.values().length > KINDS) {
            throw new IllegalStateException(
                "SurfaceKind has " + SurfaceKind.values().length
                    + " constants but the packed kind slot only fits " + KINDS
            );
        }
    }

    private PackedSurfacePlane() {
    }

    /**
     * Packs one cell. {@code surfaceY} is ignored for {@link SurfaceKind#VOID}, whose packed form
     * is the no-surface encoding regardless of the caller's height value.
     */
    static short pack(final int surfaceY, final int kindOrdinal) {
        if (kindOrdinal < 0 || kindOrdinal >= SurfaceKind.values().length) {
            throw new IllegalArgumentException("invalid surface kind ordinal " + kindOrdinal);
        }
        if (kindOrdinal == SurfaceKind.VOID.ordinal()) {
            return (short) (kindOrdinal << HEIGHT_BITS);
        }
        if (surfaceY < MIN_HEIGHT || surfaceY > MAX_HEIGHT) {
            throw new IllegalArgumentException("surface height " + surfaceY + " does not fit the packed plane");
        }
        return (short) ((surfaceY & HEIGHT_MASK) | (kindOrdinal << HEIGHT_BITS));
    }

    /** The cell's surface height, or {@link BaselineGrid#NO_SURFACE} for a void cell. */
    static int unpackHeight(final short packed) {
        if (unpackKind(packed) == SurfaceKind.VOID.ordinal()) {
            return BaselineGrid.NO_SURFACE;
        }
        return ((packed & HEIGHT_MASK) << (Integer.SIZE - HEIGHT_BITS)) >> (Integer.SIZE - HEIGHT_BITS);
    }

    /** The cell's {@link SurfaceKind} ordinal. */
    static int unpackKind(final short packed) {
        return (packed >>> HEIGHT_BITS) & (KINDS - 1);
    }

    /**
     * Packs the margin-inclusive {@link DerivedGrid} surface arrays. Both inputs must be {@link
     * #CELLS} long; void cells' heights are ignored, per {@link #pack}.
     */
    static short[] packPlane(final int[] surfaceY, final byte[] kind) {
        if (surfaceY.length != CELLS || kind.length != CELLS) {
            throw new IllegalArgumentException(
                "surface plane packing needs two " + CELLS + "-cell arrays"
            );
        }
        final short[] packed = new short[CELLS];
        for (int i = 0; i < CELLS; i++) {
            packed[i] = pack(surfaceY[i], kind[i]);
        }
        return packed;
    }

    /**
     * Inverse of {@link #packPlane}: fills both {@link #CELLS}-long outputs, mapping void cells'
     * height back to {@link BaselineGrid#NO_SURFACE}.
     */
    static void unpackPlane(final short[] packed, final int[] outSurfaceY, final byte[] outKind) {
        if (packed.length != CELLS || outSurfaceY.length != CELLS || outKind.length != CELLS) {
            throw new IllegalArgumentException(
                "surface plane unpacking needs three " + CELLS + "-cell arrays"
            );
        }
        for (int i = 0; i < CELLS; i++) {
            outSurfaceY[i] = unpackHeight(packed[i]);
            outKind[i] = (byte) unpackKind(packed[i]);
        }
    }
}

package cn.net.rms.confluxmap.core.color;

/**
 * The single decoration-overlay visibility policy shared by every rendering path: live surface
 * capture (both styles), cave/nether floor capture, server surface summaries, the native Anvil
 * scanner, and prediction compositing. Before this class existed each path grew its own
 * exclusion list against a different objective (Xaero parity, sync patch economy, spec
 * simplicity) and they silently drifted apart.
 *
 * <p>A decoration block is classified into one of four {@link Category categories}, and the
 * one visibility table below decides whether an overlay renders:
 *
 * <ul>
 *   <li>{@link Category#BULK_FOLIAGE} - the grass/fern family. Its tint is visually absorbed
 *       by the ground beneath it, so it never renders as an overlay anywhere, in any style.</li>
 *   <li>{@link Category#VEGETATION} - flowers, mushrooms, saplings, vines, and other small
 *       plants. Rendered on top-down surface layers; hidden on floor-scan (cave/nether)
 *       layers, where vegetation dots on a uniform stone floor read as noise.</li>
 *   <li>{@link Category#MARKER} - the torch/glass family. Rendered like any other decoration,
 *       except in the Xaero style, which mirrors Xaero's own scan by hiding them.</li>
 *   <li>{@link Category#OTHER} - everything else (rails, snow layers, carpets, ...): always
 *       rendered.</li>
 * </ul>
 *
 * <p>Classification has two representations that must stay in agreement. The name-based
 * {@link #categorize(String)} here serves the consumers that only have material ids (summaries,
 * the native scanner, prediction); it can only recognize bulk foliage and markers, which is all
 * those consumers need. The client additionally classifies by block instance/tag into
 * {@link Category#VEGETATION}, which material ids cannot express; the 1.17.1 registry sweep in
 * {@code McChunkSnapshotFactoryTest} pins the two representations together on vanilla blocks.
 */
public final class OverlayVisibility {

    /** Substrings identifying the bulk-foliage family in material ids. */
    public static final String[] BULK_FOLIAGE_SUBSTRINGS = {"grass", "fern"};

    /** Suffixes identifying the torch/glass marker family in material ids. */
    public static final String[] MARKER_SUFFIXES = {"torch", "glass", "glass_pane"};

    public enum Category {
        BULK_FOLIAGE,
        VEGETATION,
        MARKER,
        OTHER
    }

    private OverlayVisibility() {
    }

    /**
     * Name-based classification for material-id-only consumers. Returns {@link Category#OTHER}
     * for plants on purpose: surface summaries must keep reporting them (the surface shows
     * vegetation), and only the client composes floor layers, where it classifies by instance.
     */
    public static Category categorize(final String materialId) {
        if (materialId == null || materialId.isEmpty()) {
            return Category.OTHER;
        }
        for (final String substring : BULK_FOLIAGE_SUBSTRINGS) {
            if (materialId.contains(substring)) {
                return Category.BULK_FOLIAGE;
            }
        }
        for (final String suffix : MARKER_SUFFIXES) {
            if (materialId.endsWith(suffix)) {
                return Category.MARKER;
            }
        }
        return Category.OTHER;
    }

    /**
     * The one visibility table. {@code floorLayer} is true for the pivot-scan cave/nether
     * layers; {@code xaeroStyle} is true for the Xaero-styled overlay arrays.
     */
    public static boolean overlayVisible(
        final Category category,
        final boolean floorLayer,
        final boolean xaeroStyle
    ) {
        switch (category) {
            case BULK_FOLIAGE:
                return false;
            case VEGETATION:
                return !floorLayer;
            case MARKER:
                return !xaeroStyle;
            default:
                return true;
        }
    }
}

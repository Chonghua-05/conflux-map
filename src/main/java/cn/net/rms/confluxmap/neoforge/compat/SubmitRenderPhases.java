package cn.net.rms.confluxmap.neoforge.compat;

/**
 * Mirrors the fabric submit-phase constants over vanilla's submit-node API. Vanilla
 * routes custom geometry by the render type's blending into its solid/translucent
 * custom-geometry storage, so every constant lands in the collector the matching
 * fabric phase targets; the constants exist so the shared passes keep one call
 * shape across loader seams.
 */
public enum SubmitRenderPhases {
    AFTER_TERRAIN,
    LAST
}

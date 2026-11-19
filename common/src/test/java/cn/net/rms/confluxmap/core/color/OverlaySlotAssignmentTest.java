package cn.net.rms.confluxmap.core.color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Column-shape coverage for the shared slot interpretation. Tokens are plain strings standing
 * in for block tokens; the walk itself (world-backed opacity on the client, name tests on the
 * server) is the caller's job, so each test feeds one walk's recorded results into the seam
 * and asserts the slot assignment, per the invariant documented on
 * {@link OverlaySlotAssignment}: air is nothing and never fills a slot.
 */
class OverlaySlotAssignmentTest {

    private static final String GLASS = "glass";
    private static final String AIR = "air";
    private static final String WATER = "water";
    private static final String GRASS_BLOCK = "grass_block";
    private static final String STONE = "stone";
    private static final String TORCH = "torch";
    private static final String SHORT_GRASS = "short_grass";
    private static final String SNOW = "snow";
    private static final String CARPET = "white_carpet";
    private static final String FLOWER = "poppy";

    private static final Predicate<String> IS_AIR = AIR::equals;
    private static final Predicate<String> IS_PROMOTED_COVER =
        t -> SNOW.equals(t) || t.endsWith("_carpet");

    @Test
    void flushGlassOverWaterPutsTheGlassInTheTransparentSlot() {
        // Walk: glass@70 crossed, water@69 opaque - top and bottom overlay are the same block.
        final OverlaySlotAssignment<String> slots = OverlaySlotAssignment.forDescent(
            WATER, 69, GLASS, 70, GLASS, 70, IS_AIR, IS_PROMOTED_COVER
        );
        assertEquals(WATER, slots.surface());
        assertEquals(69, slots.surfaceY());
        assertEquals(GLASS, slots.transparentOverlay());
        assertEquals(70, slots.transparentOverlayY());
        assertNull(slots.foliageOverlay());
    }

    @Test
    void glassOverAnAirGapOverWaterFillsNoFoliageSlot() {
        // Walk: glass@70, then air@69, water@68 opaque - bottomOverlay is air. The old inline
        // client interpretation baked that air into the water-floor colors (phantom-air tint).
        final OverlaySlotAssignment<String> slots = OverlaySlotAssignment.forDescent(
            WATER, 68, GLASS, 70, AIR, 69, IS_AIR, IS_PROMOTED_COVER
        );
        assertEquals(WATER, slots.surface());
        assertEquals(GLASS, slots.transparentOverlay());
        assertEquals(70, slots.transparentOverlayY());
        assertNull(slots.foliageOverlay(), "air directly above the surface must fill no slot");
    }

    @Test
    void glassOverAnAirGapOverLandFillsNoFoliageSlot() {
        final OverlaySlotAssignment<String> slots = OverlaySlotAssignment.forDescent(
            GRASS_BLOCK, 68, GLASS, 70, AIR, 69, IS_AIR, IS_PROMOTED_COVER
        );
        assertEquals(GRASS_BLOCK, slots.surface());
        assertEquals(GLASS, slots.transparentOverlay());
        assertNull(slots.foliageOverlay(), "air directly above the surface must fill no slot");
    }

    @Test
    void torchOverAnAirGapOverDirtFillsNoFoliageSlot() {
        final OverlaySlotAssignment<String> slots = OverlaySlotAssignment.forDescent(
            STONE, 68, TORCH, 70, AIR, 69, IS_AIR, IS_PROMOTED_COVER
        );
        assertEquals(STONE, slots.surface());
        assertEquals(TORCH, slots.transparentOverlay());
        assertNull(slots.foliageOverlay(), "air directly above the surface must fill no slot");
    }

    @Test
    void airCrossedAboveARealBottomOverlayKeepsTheBottomOverlay() {
        // Walk: glass@70, air@69, poppy@68 crossed, dirt@67 opaque - the flower sits directly
        // on the surface, so it keeps the foliage slot even though air was crossed above it.
        final OverlaySlotAssignment<String> slots = OverlaySlotAssignment.forDescent(
            STONE, 67, GLASS, 70, FLOWER, 68, IS_AIR, IS_PROMOTED_COVER
        );
        assertEquals(STONE, slots.surface());
        assertEquals(GLASS, slots.transparentOverlay());
        assertEquals(FLOWER, slots.foliageOverlay());
        assertEquals(68, slots.foliageOverlayY());
    }

    @Test
    void decorationFloatingOverAnAirGapIsDroppedWithTheAir() {
        // Walk: glass@70, poppy@69, air@68 crossed, stone@67 opaque - nothing sits directly on
        // the surface, so the foliage slot stays empty rather than holding the phantom air.
        final OverlaySlotAssignment<String> slots = OverlaySlotAssignment.forDescent(
            STONE, 67, GLASS, 70, AIR, 68, IS_AIR, IS_PROMOTED_COVER
        );
        assertEquals(STONE, slots.surface());
        assertEquals(GLASS, slots.transparentOverlay());
        assertNull(slots.foliageOverlay());
    }

    @Test
    void phantomAirAtTheTopOfTheWalkFillsNoTransparentSlot() {
        // Only reachable with a stale heightmap on the client, but the seam must still uphold
        // the rule: air crossed first never becomes the see-through cover.
        final OverlaySlotAssignment<String> slots = OverlaySlotAssignment.forDescent(
            STONE, 67, AIR, 70, GLASS, 68, IS_AIR, IS_PROMOTED_COVER
        );
        assertEquals(STONE, slots.surface());
        assertNull(slots.transparentOverlay(), "air must never hold the transparent slot");
        assertEquals(GLASS, slots.foliageOverlay());
        assertEquals(68, slots.foliageOverlayY());
    }

    @Test
    void thinCoverDirectlyOnTheDescendedSurfaceIsPromotedOverIt() {
        // Walk: glass@70, snow layer@69 crossed, dirt@68 opaque - the snow layer is the
        // visible surface and the glass roof stays a transparent overlay above it.
        final OverlaySlotAssignment<String> slots = OverlaySlotAssignment.forDescent(
            STONE, 68, GLASS, 70, SNOW, 69, IS_AIR, IS_PROMOTED_COVER
        );
        assertEquals(SNOW, slots.surface());
        assertEquals(69, slots.surfaceY());
        assertEquals(GLASS, slots.transparentOverlay());
        assertEquals(70, slots.transparentOverlayY());
        assertNull(slots.foliageOverlay());
    }

    @Test
    void carpetDirectlyOnTheDescendedSurfaceIsPromotedOverIt() {
        final OverlaySlotAssignment<String> slots = OverlaySlotAssignment.forDescent(
            STONE, 68, GLASS, 70, CARPET, 69, IS_AIR, IS_PROMOTED_COVER
        );
        assertEquals(CARPET, slots.surface());
        assertEquals(69, slots.surfaceY());
        assertEquals(GLASS, slots.transparentOverlay());
    }

    @Test
    void loneDescendedCoverPromotesWithoutATransparentOverlay() {
        // Walk: snow layer@69 only, dirt@68 opaque - one crossed block is the surface itself.
        final OverlaySlotAssignment<String> slots = OverlaySlotAssignment.forDescent(
            STONE, 68, SNOW, 69, SNOW, 69, IS_AIR, IS_PROMOTED_COVER
        );
        assertEquals(SNOW, slots.surface());
        assertEquals(69, slots.surfaceY());
        assertNull(slots.transparentOverlay(), "a single crossed block has nothing above it");
        assertNull(slots.foliageOverlay());
    }

    @Test
    void glassAndFlowerDescendedTogetherSplitIntoTransparentAndFoliageSlots() {
        // Walk: glass@70, poppy@69 crossed, dirt@68 opaque.
        final OverlaySlotAssignment<String> slots = OverlaySlotAssignment.forDescent(
            STONE, 68, GLASS, 70, FLOWER, 69, IS_AIR, IS_PROMOTED_COVER
        );
        assertEquals(STONE, slots.surface());
        assertEquals(GLASS, slots.transparentOverlay());
        assertEquals(FLOWER, slots.foliageOverlay());
        assertEquals(69, slots.foliageOverlayY());
    }

    @Test
    void surfaceColumnWithDecorationAbovePutsItInTheFoliageSlot() {
        // No descent: the heightmap block itself is opaque and short_grass stands on it.
        final OverlaySlotAssignment<String> slots = OverlaySlotAssignment.forSurfaceColumn(
            GRASS_BLOCK, 70, SHORT_GRASS, IS_AIR, IS_PROMOTED_COVER
        );
        assertEquals(GRASS_BLOCK, slots.surface());
        assertEquals(70, slots.surfaceY());
        assertEquals(SHORT_GRASS, slots.foliageOverlay());
        assertEquals(71, slots.foliageOverlayY());
        assertNull(slots.transparentOverlay(), "a surface column has no see-through cover");
    }

    @Test
    void surfaceColumnWithBareAirAboveFillsNoSlot() {
        final OverlaySlotAssignment<String> slots = OverlaySlotAssignment.forSurfaceColumn(
            GRASS_BLOCK, 70, AIR, IS_AIR, IS_PROMOTED_COVER
        );
        assertEquals(GRASS_BLOCK, slots.surface());
        assertEquals(70, slots.surfaceY());
        assertNull(slots.transparentOverlay());
        assertNull(slots.foliageOverlay());
    }

    @Test
    void surfaceColumnWithASnowCoverPromotesItToTheSurface() {
        final OverlaySlotAssignment<String> slots = OverlaySlotAssignment.forSurfaceColumn(
            GRASS_BLOCK, 70, SNOW, IS_AIR, IS_PROMOTED_COVER
        );
        assertEquals(SNOW, slots.surface());
        assertEquals(71, slots.surfaceY());
        assertNull(slots.transparentOverlay());
        assertNull(slots.foliageOverlay());
    }

    @Test
    void surfaceColumnWithACarpetCoverPromotesItToTheSurface() {
        final OverlaySlotAssignment<String> slots = OverlaySlotAssignment.forSurfaceColumn(
            GRASS_BLOCK, 70, CARPET, IS_AIR, IS_PROMOTED_COVER
        );
        assertEquals(CARPET, slots.surface());
        assertEquals(71, slots.surfaceY());
        assertNull(slots.foliageOverlay());
    }

    @Test
    void nameBasedRulesCoverTheVanillaAirAndCoverIds() {
        assertTrue(OverlaySlotAssignment.isAirMaterial("minecraft:air"));
        assertTrue(OverlaySlotAssignment.isAirMaterial("minecraft:cave_air"));
        assertTrue(OverlaySlotAssignment.isAirMaterial("minecraft:void_air"));
        assertFalse(OverlaySlotAssignment.isAirMaterial("minecraft:grass_block"));
        assertFalse(OverlaySlotAssignment.isAirMaterial(null));

        assertTrue(OverlaySlotAssignment.isPromotedCoverMaterial("minecraft:snow"));
        assertTrue(OverlaySlotAssignment.isPromotedCoverMaterial("minecraft:powder_snow"));
        assertTrue(OverlaySlotAssignment.isPromotedCoverMaterial("minecraft:moss_carpet"));
        assertFalse(OverlaySlotAssignment.isPromotedCoverMaterial("minecraft:snow_block"));
        assertFalse(OverlaySlotAssignment.isPromotedCoverMaterial("minecraft:air"));
        assertFalse(OverlaySlotAssignment.isPromotedCoverMaterial(null));
    }
}

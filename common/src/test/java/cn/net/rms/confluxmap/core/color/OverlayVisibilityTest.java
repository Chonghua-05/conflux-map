package cn.net.rms.confluxmap.core.color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OverlayVisibilityTest {

    @Test
    void nameRuleClassifiesBulkFoliageBySubstring() {
        for (final String id : new String[] {
            "minecraft:grass", "minecraft:short_grass", "minecraft:tall_grass",
            "minecraft:fern", "minecraft:large_fern", "mymod:some_grass_thing"
        }) {
            assertEquals(OverlayVisibility.Category.BULK_FOLIAGE, OverlayVisibility.categorize(id), id);
        }
    }

    @Test
    void nameRuleClassifiesMarkersBySuffix() {
        for (final String id : new String[] {
            "minecraft:torch", "minecraft:wall_torch", "minecraft:soul_torch",
            "minecraft:redstone_torch"
        }) {
            assertEquals(OverlayVisibility.Category.MARKER, OverlayVisibility.categorize(id), id);
        }
    }

    @Test
    void nameRuleLeavesPlantsAndEverythingElseUnclassified() {
        // Vegetation is deliberately OTHER here: surface summaries must keep reporting it,
        // and only the client composes floor layers, where block instances classify plants.
        // Glass is OTHER since the real Xaero mod measurably draws it even in its own style.
        for (final String id : new String[] {
            "minecraft:poppy", "minecraft:brown_mushroom", "minecraft:oak_sapling",
            "minecraft:rail", "minecraft:snow", "minecraft:moss_carpet",
            "minecraft:glass", "minecraft:tinted_glass", "minecraft:white_stained_glass",
            "minecraft:glass_pane", "minecraft:white_stained_glass_pane", "", null
        }) {
            assertEquals(OverlayVisibility.Category.OTHER, OverlayVisibility.categorize(id), String.valueOf(id));
        }
    }

    @Test
    void bulkFoliageNeverRendersAnywhere() {
        for (final boolean floorLayer : new boolean[] {false, true}) {
            for (final boolean xaeroStyle : new boolean[] {false, true}) {
                assertFalse(OverlayVisibility.overlayVisible(
                    OverlayVisibility.Category.BULK_FOLIAGE, floorLayer, xaeroStyle
                ));
            }
        }
    }

    @Test
    void vegetationRendersOnSurfaceLayersOnly() {
        assertTrue(OverlayVisibility.overlayVisible(OverlayVisibility.Category.VEGETATION, false, false));
        assertTrue(OverlayVisibility.overlayVisible(OverlayVisibility.Category.VEGETATION, false, true));
        assertFalse(OverlayVisibility.overlayVisible(OverlayVisibility.Category.VEGETATION, true, false));
        assertFalse(OverlayVisibility.overlayVisible(OverlayVisibility.Category.VEGETATION, true, true));
    }

    @Test
    void markersHideOnlyInXaeroStyle() {
        assertTrue(OverlayVisibility.overlayVisible(OverlayVisibility.Category.MARKER, false, false));
        assertTrue(OverlayVisibility.overlayVisible(OverlayVisibility.Category.MARKER, true, false));
        assertFalse(OverlayVisibility.overlayVisible(OverlayVisibility.Category.MARKER, false, true));
        assertFalse(OverlayVisibility.overlayVisible(OverlayVisibility.Category.MARKER, true, true));
    }

    @Test
    void everythingElseAlwaysRenders() {
        for (final boolean floorLayer : new boolean[] {false, true}) {
            for (final boolean xaeroStyle : new boolean[] {false, true}) {
                assertTrue(OverlayVisibility.overlayVisible(
                    OverlayVisibility.Category.OTHER, floorLayer, xaeroStyle
                ));
            }
        }
    }
}

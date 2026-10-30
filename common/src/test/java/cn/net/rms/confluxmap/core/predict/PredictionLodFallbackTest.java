package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import org.junit.jupiter.api.Test;

/**
 * The parent-LOD underlay must clip a missing child out of the right quadrant of its ancestor, or
 * the fallback would draw the neighbouring terrain - visibly worse than the black square it
 * replaces.
 */
class PredictionLodFallbackTest {
    private static final WorldIdentity WORLD = WorldIdentity.singleplayer("lod-fallback-test");

    private static TileKey key(final int lod, final int tileX, final int tileZ) {
        return new TileKey(WORLD, DimensionId.OVERWORLD, "surface!pred", lod, tileX, tileZ);
    }

    @Test
    void childMapsToItsOwnQuadrantOfTheParent() {
        final PredictionLodFallback.Window northWest = PredictionLodFallback
            .window(key(0, 4, 6), 1)
            .orElseThrow();
        assertEquals(new PredictionLodFallback.Window(0f, 0f, 0.5f, 0.5f), northWest);

        final PredictionLodFallback.Window northEast = PredictionLodFallback
            .window(key(0, 5, 6), 1)
            .orElseThrow();
        assertEquals(new PredictionLodFallback.Window(0.5f, 0f, 1f, 0.5f), northEast);

        final PredictionLodFallback.Window southWest = PredictionLodFallback
            .window(key(0, 4, 7), 1)
            .orElseThrow();
        assertEquals(new PredictionLodFallback.Window(0f, 0.5f, 0.5f, 1f), southWest);

        final PredictionLodFallback.Window southEast = PredictionLodFallback
            .window(key(0, 5, 7), 1)
            .orElseThrow();
        assertEquals(new PredictionLodFallback.Window(0.5f, 0.5f, 1f, 1f), southEast);
    }

    @Test
    void ancestorIsTheCoarserTileThatContainsTheChild() {
        assertEquals(
            key(1, 2, 3),
            PredictionLodFallback.ancestor(key(0, 4, 6), 1).orElseThrow(),
            "the parent is the coarser tile holding this child"
        );
        assertEquals(
            key(2, 1, 1),
            PredictionLodFallback.ancestor(key(0, 5, 7), 2).orElseThrow(),
            "falling back two levels must follow the same floor division"
        );
    }

    @Test
    void negativeCoordinatesStayInTheNorthernWesternQuadrantPair() {
        // floorDiv/floorMod, not truncating division: tile (-1, -1) is the south-east quadrant of
        // parent (-1, -1), not the north-west quadrant of parent (0, 0).
        assertEquals(key(1, -1, -1), PredictionLodFallback.ancestor(key(0, -1, -1), 1).orElseThrow());
        assertEquals(
            new PredictionLodFallback.Window(0.5f, 0.5f, 1f, 1f),
            PredictionLodFallback.window(key(0, -1, -1), 1).orElseThrow()
        );
        assertEquals(key(1, -1, -1), PredictionLodFallback.ancestor(key(0, -2, -2), 1).orElseThrow());
        assertEquals(
            new PredictionLodFallback.Window(0f, 0f, 0.5f, 0.5f),
            PredictionLodFallback.window(key(0, -2, -2), 1).orElseThrow()
        );
    }

    @Test
    void topOfThePyramidHasNoParent() {
        assertTrue(PredictionLodFallback.ancestor(key(4, 0, 0), 1).isEmpty());
        assertTrue(PredictionLodFallback.window(key(4, 0, 0), 1).isEmpty());
        assertTrue(PredictionLodFallback.ancestor(key(0, 0, 0), 0).isEmpty());
    }

    @Test
    void everyChildOfAThreeLevelStepGetsItsOwnEighthOfTheTexture() {
        for (int tileX = 0; tileX < 8; tileX++) {
            for (int tileZ = 0; tileZ < 8; tileZ++) {
                final PredictionLodFallback.Window window = PredictionLodFallback
                    .window(key(0, tileX, tileZ), 3)
                    .orElseThrow();
                assertTrue(window.u0() >= 0f && window.u1() <= 1f);
                assertTrue(window.v0() >= 0f && window.v1() <= 1f);
                assertEquals(0.125f, window.width(), 1.0e-6f);
                assertEquals(0.125f, window.height(), 1.0e-6f);
                assertEquals(tileX * 0.125f, window.u0(), 1.0e-6f);
                assertEquals(tileZ * 0.125f, window.v0(), 1.0e-6f);
            }
        }
    }
}

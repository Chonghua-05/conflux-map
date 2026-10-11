package cn.net.rms.confluxmap.mc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds;
import java.util.Map;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class BiomeLabelsTest {
    @AfterEach
    void tearDown() {
        BiomeLabels.clearDatapackNames();
    }

    @Test
    void datapackBiomeResolvesThroughTheSyncedSetThenTheId() {
        BiomeLabels.acceptDatapackNames(Map.of(
            "biome.testpack.crystal_forest", "Crystal Forest"
        ));

        assertEquals(
            "Crystal Forest",
            BiomeLabels.displayName(Ids.of("testpack", "crystal_forest"))
        );
        assertEquals(
            "testpack:not_shipped",
            BiomeLabels.displayName(Ids.of("testpack", "not_shipped"))
        );

        BiomeLabels.clearDatapackNames();
        assertEquals(
            "testpack:crystal_forest",
            BiomeLabels.displayName(Ids.of("testpack", "crystal_forest"))
        );
    }

    @Test
    void predictedNameDegradesToTheCanonicalIdNeverARawKey() {
        final Identifier irrelevant = Ids.of("testpack", "x");
        assertEquals("testpack:x", BiomeLabels.displayName(irrelevant));

        final String predicted = BiomeLabels.predictedDisplayName(CubiomesBiomeIds.SOUL_SAND_VALLEY);
        assertFalse(predicted.startsWith("biome."), predicted);
        assertFalse(predicted.isEmpty(), predicted);
    }
}

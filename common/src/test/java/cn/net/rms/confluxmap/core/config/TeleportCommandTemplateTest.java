package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import org.junit.jupiter.api.Test;

class TeleportCommandTemplateTest {
    @Test
    void rendersCoordinatesAndRemoteTargetPlaceholders() {
        assertEquals(
            "execute in minecraft:the_nether run tp @s 12.5 90 -3.5 server-world",
            TeleportCommandTemplate.render(
                "/execute in {dimension} run tp @s {x} {y} {z} {world}",
                12.5,
                90.0,
                -3.5,
                DimensionId.NETHER,
                new WorldIdentity("server", "server-world")
            )
        );
    }

    @Test
    void rendersFarWorldAndTinyCoordinatesWithoutScientificNotation() {
        assertEquals(
            "execute in minecraft:overworld run tp @s 12345678.5 64 -29999984",
            TeleportCommandTemplate.render(
                ConfluxConfig.DEFAULT_TELEPORT_COMMAND,
                12345678.5,
                64.0,
                -29999984.0,
                DimensionId.OVERWORLD,
                new WorldIdentity("server", "server-world")
            )
        );
        assertEquals(
            "tp 0.0001 64 -0.5",
            TeleportCommandTemplate.render(
                "tp {x} {y} {z}",
                0.0001,
                64.0,
                -0.5,
                DimensionId.OVERWORLD,
                new WorldIdentity("server", "server-world")
            )
        );
    }

    @Test
    void rejectsTemplatesThatCannotPlaceAPlayerAtAllThreeCoordinates() {
        assertFalse(TeleportCommandTemplate.valid(null));
        assertFalse(TeleportCommandTemplate.valid("tp {x} {z}"));
        assertFalse(TeleportCommandTemplate.valid("tp {x} {y} {z}\nsay injected"));
        assertThrows(
            IllegalArgumentException.class,
            () -> TeleportCommandTemplate.render(
                "tp {x} {z}", 1, 2, 3, DimensionId.OVERWORLD,
                new WorldIdentity("server", "world")
            )
        );
    }

    @Test
    void shippedDefaultRendersACrossDimensionTeleport() {
        assertTrue(TeleportCommandTemplate.supportsDimensionSwitch(
            ConfluxConfig.DEFAULT_TELEPORT_COMMAND
        ));
        assertEquals(
            "execute in minecraft:the_nether run tp @s 12.5 90 -3.5",
            TeleportCommandTemplate.render(
                ConfluxConfig.DEFAULT_TELEPORT_COMMAND,
                12.5,
                90.0,
                -3.5,
                DimensionId.NETHER,
                new WorldIdentity("server", "server-world")
            )
        );
    }

    @Test
    void exposesCommandTreeNameAndRemoteSwitchCapabilities() {
        assertEquals(
            "execute",
            TeleportCommandTemplate.commandName(" /execute in {dimension} run tp {x} {y} {z}")
                .orElseThrow()
        );
        assertTrue(TeleportCommandTemplate.supportsDimensionSwitch(
            "execute in {dimension} run tp {x} {y} {z}"
        ));
        assertFalse(TeleportCommandTemplate.supportsWorldSwitch(
            "execute in {dimension} run tp {x} {y} {z}"
        ));
    }
}

package cn.net.rms.confluxmap.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaperPlayerProbeTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000801");

    @Test
    void resolvesTheSkinCapturedOnThePlayerThread() {
        final PaperPlayerProbe.Snapshot snapshot = snapshot(
            "https://textures.minecraft.net/texture/abc"
        );

        assertEquals(
            "https://textures.minecraft.net/texture/abc",
            snapshot.skin().toString()
        );
    }

    @Test
    void reportsNoSkinWhenThePlayerHadNone() {
        assertNull(snapshot(null).skin());
    }

    @Test
    void rejectsSkinOriginsThatAreNotValidUris() {
        assertNull(snapshot("not a uri").skin());
    }

    private static PaperPlayerProbe.Snapshot snapshot(final String skinUrl) {
        return new PaperPlayerProbe.Snapshot(
            ID,
            "Notch",
            "minecraft:overworld",
            12.5d,
            64.0d,
            -3.25d,
            false,
            false,
            true,
            skinUrl
        );
    }
}

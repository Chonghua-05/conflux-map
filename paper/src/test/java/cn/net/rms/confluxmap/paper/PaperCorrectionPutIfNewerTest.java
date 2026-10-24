package cn.net.rms.confluxmap.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.server.ServerConfig;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Asynchronous captures complete out of order, so the commit path must keep a stale snapshot
 * from overwriting a newer summary that landed first.
 */
class PaperCorrectionPutIfNewerTest {
    @TempDir
    Path temporary;

    @Test
    void staleCommitDoesNotOverwriteANewerSummary() throws Exception {
        final PaperWorldDirectory worlds = directory(temporary);
        final PaperWorldDirectory.Entry world = worlds.at(0);

        try (PaperCorrectionService service = service(worlds)) {
            service.putLive(world, 0, 0, chunk(100L, 10));

            service.putLiveIfNewer(world, 0, 0, chunk(50L, 20));

            assertEquals(
                10,
                service.liveSummary(world, 0, 0).columns()[0].surfaceY(),
                "a late commit of an older snapshot must be dropped, not merged over newer data"
            );
        }
    }

    @Test
    void newerCommitOverwritesAnOlderSummary() throws Exception {
        final PaperWorldDirectory worlds = directory(temporary);
        final PaperWorldDirectory.Entry world = worlds.at(0);

        try (PaperCorrectionService service = service(worlds)) {
            service.putLive(world, 0, 0, chunk(50L, 10));

            service.putLiveIfNewer(world, 0, 0, chunk(100L, 20));

            assertEquals(
                20,
                service.liveSummary(world, 0, 0).columns()[0].surfaceY(),
                "a newer capture must replace the older summary's data"
            );
        }
    }

    @Test
    void sameRevisionCommitStillLands() throws Exception {
        final PaperWorldDirectory worlds = directory(temporary);
        final PaperWorldDirectory.Entry world = worlds.at(0);

        try (PaperCorrectionService service = service(worlds)) {
            service.putLive(world, 0, 0, chunk(100L, 10));

            service.putLiveIfNewer(world, 0, 0, chunk(100L, 20));

            assertEquals(
                20,
                service.liveSummary(world, 0, 0).columns()[0].surfaceY(),
                "an equal-revision commit carries the same tick's data and must not be dropped"
            );
        }
    }

    @Test
    void commitIntoAnEmptySlotLands() throws Exception {
        final PaperWorldDirectory worlds = directory(temporary);
        final PaperWorldDirectory.Entry world = worlds.at(0);

        try (PaperCorrectionService service = service(worlds)) {
            assertNull(service.liveSummary(world, 0, 0));

            service.putLiveIfNewer(world, 0, 0, chunk(100L, 10));

            assertEquals(
                10,
                service.liveSummary(world, 0, 0).columns()[0].surfaceY(),
                "a first capture has nothing to be stale against"
            );
        }
    }

    private static SummaryCodec.Chunk chunk(final long revision, final int surfaceY) {
        final SummaryCodec.Column[] columns = new SummaryCodec.Column[SummaryCodec.COLUMNS];
        columns[0] = new SummaryCodec.Column(
            1, surfaceY, 0, 1, 0, 255, 15, "", "", ""
        );
        return new SummaryCodec.Chunk(true, revision, columns);
    }

    private static PaperCorrectionService service(final PaperWorldDirectory worlds) {
        final ServerConfig config = new ServerConfig();
        config.minRequestIntervalMs = 0;
        config.maxBytesPerSecondPerPlayer = 100_000_000;
        return new PaperCorrectionService(
            config,
            worlds,
            "1.21.1",
            1L,
            ignored -> null,
            new PaperMapColors(Map.of()),
            LoggerFactory.getLogger(PaperCorrectionPutIfNewerTest.class)
        );
    }

    private static PaperWorldDirectory directory(final Path root) throws Exception {
        Files.createDirectories(root.resolve("region"));
        final World world = (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[] {World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getKey" -> NamespacedKey.minecraft("overworld");
                case "getWorldFolder" -> root.toFile();
                case "getEnvironment" -> World.Environment.NORMAL;
                case "getWorldType" -> WorldType.NORMAL;
                case "getGenerator" -> null;
                case "toString" -> "PaperCorrectionPutIfNewerTestWorld";
                default -> defaultValue(method.getReturnType());
            }
        );
        final PaperWorldDirectory worlds = new PaperWorldDirectory();
        worlds.add(world);
        return worlds;
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }
}

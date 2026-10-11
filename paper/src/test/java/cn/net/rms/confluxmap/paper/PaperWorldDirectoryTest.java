package cn.net.rms.confluxmap.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperWorldDirectoryTest {
    @Test
    void resolvesTheLevelRootAboveAModernDimensionFolder(@TempDir final Path serverRoot) throws IOException {
        final Path level = serverRoot.resolve("world");
        final Path overworld = level.resolve("dimensions/minecraft/overworld");
        Files.createDirectories(overworld);
        Files.createFile(level.resolve("level.dat"));

        assertEquals(level.normalize(), PaperWorldDirectory.levelRoot(overworld).normalize());
    }

    @Test
    void resolvesTheLevelRootAboveALegacyDimensionFolder(@TempDir final Path serverRoot) throws IOException {
        final Path level = serverRoot.resolve("world_nether");
        final Path nether = level.resolve("DIM-1");
        Files.createDirectories(nether);
        Files.createFile(level.resolve("level.dat"));

        assertEquals(level.normalize(), PaperWorldDirectory.levelRoot(nether).normalize());
    }

    @Test
    void keepsAFolderThatAlreadyIsTheLevelRoot(@TempDir final Path serverRoot) throws IOException {
        final Path level = serverRoot.resolve("world");
        Files.createDirectories(level);
        Files.createFile(level.resolve("level.dat"));

        assertEquals(level.normalize(), PaperWorldDirectory.levelRoot(level).normalize());
    }

    @Test
    void fallsBackToTheFolderItselfWithoutAnyLevelDatAncestor(@TempDir final Path serverRoot) throws IOException {
        final Path orphan = serverRoot.resolve("world/dimensions/custom/nothing");
        Files.createDirectories(orphan);

        assertEquals(orphan.normalize(), PaperWorldDirectory.levelRoot(orphan).normalize());
    }
}

package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.MapFullSyncManifestS2C;
import cn.net.rms.confluxmap.core.net.Proto;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Enumerates a dimension's generated summary regions from its {@code .mca} files for a
 * server-initiated full sync. Each 32x32-chunk {@code r.X.Z.mca} file covers a 2x2 grid of
 * 16-chunk summary regions; only files matching that exact name shape are accepted.
 */
public final class RegionManifestScanner {
    /** Hard ceiling for one manifest: {@value #MAX_REGIONS} summary regions. */
    public static final int MAX_REGIONS =
        Proto.MAX_FULL_SYNC_PAGE_REGIONS * Proto.MAX_FULL_SYNC_PAGES;

    private RegionManifestScanner() {
    }

    /** Result of a directory scan; {@code truncated} marks a world larger than the cap. */
    public record ScanResult(List<MapFullSyncManifestS2C.RegionCoord> regions, boolean truncated) {
    }

    /**
     * Lists the dimension's generated regions. A missing directory simply has nothing
     * generated yet and yields an empty result; a failing directory listing propagates so
     * the caller can log it.
     */
    public static ScanResult scan(final Path regionDir) throws IOException {
        if (!Files.isDirectory(regionDir)) {
            return new ScanResult(List.of(), false);
        }
        final List<MapFullSyncManifestS2C.RegionCoord> regions = new ArrayList<>();
        boolean truncated = false;
        try (Stream<Path> stream = Files.list(regionDir)) {
            for (final Path file : (Iterable<Path>) stream::iterator) {
                final int[] mca = mcaCoords(file.getFileName().toString());
                if (mca == null) {
                    continue;
                }
                for (int dz = 0; dz < 2; dz++) {
                    for (int dx = 0; dx < 2; dx++) {
                        if (regions.size() >= MAX_REGIONS) {
                            truncated = true;
                            return new ScanResult(regions, truncated);
                        }
                        regions.add(new MapFullSyncManifestS2C.RegionCoord(mca[0] * 2 + dx, mca[1] * 2 + dz));
                    }
                }
            }
        }
        return new ScanResult(regions, truncated);
    }

    /** Orders regions by distance from the player's block position, nearest first. */
    public static void sortByDistance(
        final List<MapFullSyncManifestS2C.RegionCoord> regions,
        final double playerX,
        final double playerZ
    ) {
        final Comparator<MapFullSyncManifestS2C.RegionCoord> byDistance = Comparator.comparingLong(
            coord -> {
                final long dx = Math.round(coord.regionX() * 256.0 + 128.0 - playerX);
                final long dz = Math.round(coord.regionZ() * 256.0 + 128.0 - playerZ);
                return dx * dx + dz * dz;
            }
        );
        regions.sort(byDistance);
    }

    /** Splits the manifest into codec-bounded pages of at most {@link Proto#MAX_FULL_SYNC_PAGE_REGIONS}. */
    public static List<List<MapFullSyncManifestS2C.RegionCoord>> paginate(
        final List<MapFullSyncManifestS2C.RegionCoord> regions
    ) {
        final List<List<MapFullSyncManifestS2C.RegionCoord>> pages = new ArrayList<>();
        for (int i = 0; i < regions.size(); i += Proto.MAX_FULL_SYNC_PAGE_REGIONS) {
            pages.add(regions.subList(i, Math.min(i + Proto.MAX_FULL_SYNC_PAGE_REGIONS, regions.size())));
        }
        return pages;
    }

    /** Parses {@code r.X.Z.mca} into its file coordinates, or null for any other name. */
    static int[] mcaCoords(final String name) {
        if (!name.startsWith("r.") || !name.endsWith(".mca")) {
            return null;
        }
        final String[] parts = name.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (final NumberFormatException e) {
            return null;
        }
    }
}

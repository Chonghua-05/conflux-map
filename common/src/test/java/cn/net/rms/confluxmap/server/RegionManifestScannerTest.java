package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.MapFullSyncManifestS2C;
import cn.net.rms.confluxmap.core.net.Proto;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionManifestScannerTest {
    @TempDir
    Path dir;

    private void mca(final String name) throws IOException {
        Files.createFile(dir.resolve(name));
    }

    @Test
    void emptyOrMissingDirectoryYieldsNothing() throws IOException {
        final RegionManifestScanner.ScanResult missing = RegionManifestScanner.scan(dir.resolve("nope"));
        assertTrue(missing.regions().isEmpty());
        assertFalse(missing.truncated());

        final RegionManifestScanner.ScanResult empty = RegionManifestScanner.scan(dir);
        assertTrue(empty.regions().isEmpty());
    }

    @Test
    void eachMcaExpandsToItsFourSummaryRegions() throws IOException {
        mca("r.0.0.mca");
        final List<MapFullSyncManifestS2C.RegionCoord> regions = RegionManifestScanner.scan(dir).regions();
        assertEquals(4, regions.size());
        assertTrue(regions.contains(new MapFullSyncManifestS2C.RegionCoord(0, 0)));
        assertTrue(regions.contains(new MapFullSyncManifestS2C.RegionCoord(1, 0)));
        assertTrue(regions.contains(new MapFullSyncManifestS2C.RegionCoord(0, 1)));
        assertTrue(regions.contains(new MapFullSyncManifestS2C.RegionCoord(1, 1)));
    }

    @Test
    void negativeMcaCoordinatesStayConsistent() throws IOException {
        mca("r.-1.2.mca");
        final List<MapFullSyncManifestS2C.RegionCoord> regions = RegionManifestScanner.scan(dir).regions();
        assertEquals(4, regions.size());
        assertTrue(regions.contains(new MapFullSyncManifestS2C.RegionCoord(-2, 4)));
        assertTrue(regions.contains(new MapFullSyncManifestS2C.RegionCoord(-1, 4)));
        assertTrue(regions.contains(new MapFullSyncManifestS2C.RegionCoord(-2, 5)));
        assertTrue(regions.contains(new MapFullSyncManifestS2C.RegionCoord(-1, 5)));
    }

    @Test
    void unrelatedAndMalformedNamesAreIgnored() throws IOException {
        mca("r.a.b.mca");
        mca("r.1.2.mca.backup");
        mca("r.1.mca");
        mca("session.lock");
        mca("r.-3.-4.mca");
        assertEquals(4, RegionManifestScanner.scan(dir).regions().size());
    }

    @Test
    void oversizedWorldIsTruncatedAtTheCap() throws IOException {
        final int mcaFiles = RegionManifestScanner.MAX_REGIONS / 4 + 1;
        for (int i = 0; i < mcaFiles; i++) {
            mca("r." + i + ".0.mca");
        }
        final RegionManifestScanner.ScanResult result = RegionManifestScanner.scan(dir);
        assertTrue(result.truncated());
        assertEquals(RegionManifestScanner.MAX_REGIONS, result.regions().size());
    }

    @Test
    void distanceSortOrdersNearestFirstAndPaginationBoundsPages() {
        final java.util.List<MapFullSyncManifestS2C.RegionCoord> regions = new java.util.ArrayList<>();
        regions.add(new MapFullSyncManifestS2C.RegionCoord(50, 0));   // ~12800 blocks away
        regions.add(new MapFullSyncManifestS2C.RegionCoord(-1, 0));   // ~128 blocks away
        regions.add(new MapFullSyncManifestS2C.RegionCoord(0, 0));    // ~128 blocks away, nearer
        RegionManifestScanner.sortByDistance(regions, 8.0, 8.0);
        assertEquals(0, regions.get(0).regionX());
        assertEquals(-1, regions.get(1).regionX());
        assertEquals(50, regions.get(2).regionX());

        final java.util.List<MapFullSyncManifestS2C.RegionCoord> many = new java.util.ArrayList<>();
        for (int i = 0; i < Proto.MAX_FULL_SYNC_PAGE_REGIONS + 10; i++) {
            many.add(new MapFullSyncManifestS2C.RegionCoord(i, 0));
        }
        final List<List<MapFullSyncManifestS2C.RegionCoord>> pages = RegionManifestScanner.paginate(many);
        assertEquals(2, pages.size());
        assertEquals(Proto.MAX_FULL_SYNC_PAGE_REGIONS, pages.get(0).size());
        assertEquals(10, pages.get(1).size());
    }

    @Test
    void nameParserAcceptsOnlyExactMcaShape() {
        final int[] parsed = RegionManifestScanner.mcaCoords("r.-12.34.mca");
        assertEquals(-12, parsed[0]);
        assertEquals(34, parsed[1]);
        assertNull(RegionManifestScanner.mcaCoords("r.1.2.mca.bak"));
        assertNull(RegionManifestScanner.mcaCoords("entities.r.0.0.mca"));
        assertNull(RegionManifestScanner.mcaCoords("r.x.z.mca"));
    }
}

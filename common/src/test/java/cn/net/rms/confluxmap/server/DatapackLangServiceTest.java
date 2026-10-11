package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatapackLangServiceTest {
    @TempDir
    Path root;

    private DatapackLangService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    private Map<String, String> requestNames(final String locale) throws Exception {
        final CompletableFuture<Map<String, String>> future = new CompletableFuture<>();
        service.requestBiomeNames(locale, future::complete);
        return future.get(10, TimeUnit.SECONDS);
    }

    private DatapackLangService forDir(final Path dir) {
        service = new DatapackLangService(() -> dir, LogManager.getLogger());
        return service;
    }

    @Test
    void localeIsNormalizedForDiskUse() {
        assertEquals("en_us", DatapackLangService.normalizeLocale(null));
        assertEquals("en_us", DatapackLangService.normalizeLocale(""));
        assertEquals("zh_cn", DatapackLangService.normalizeLocale(" zh_CN "));
        assertEquals("en_us", DatapackLangService.normalizeLocale("../../world/level_dat"));
        assertEquals("pt_br", DatapackLangService.normalizeLocale("pt_br"));
    }

    @Test
    void missingDirectoryYieldsNoNames() throws Exception {
        forDir(root.resolve("absent"));
        assertTrue(requestNames("en_us").isEmpty());
    }

    @Test
    void folderPackMergesBiomeKeysWithLocaleOverridingEnUs() throws Exception {
        final Path pack = root.resolve("datapacks/pack");
        final Path lang = pack.resolve("assets/custom/lang");
        Files.createDirectories(lang);
        Files.writeString(
            lang.resolve("en_us.json"),
            "{\"biome.custom.crystal_forest\":\"Crystal Forest\","
                + "\"biome.custom.volcano\":\"Volcano\","
                + "\"biome.custom.numeric\":42,"
                + "\"item.custom.sword\":\"Sword\"}",
            StandardCharsets.UTF_8
        );
        writeLang(pack, "custom", "zh_cn.json", Map.of(
            "biome.custom.crystal_forest", "水晶森林"
        ));
        final Map<String, String> names = requestFromDir(root.resolve("datapacks"), "zh_cn");
        assertEquals("水晶森林", names.get("biome.custom.crystal_forest"));
        assertEquals("Volcano", names.get("biome.custom.volcano"));
        assertEquals(2, names.size());
    }

    @Test
    void zipPackMergesBiomeKeysFromBothLangFiles() throws Exception {
        final Map<String, String> enUs = new LinkedHashMap<>();
        enUs.put("biome.zipper.deep_lake", "Deep Lake");
        enUs.put("entity.zipper.fish", "Fish");
        final Path zip = root.resolve("datapacks/zipper.zip");
        Files.createDirectories(zip.getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip), StandardCharsets.UTF_8)) {
            putZipEntry(out, "pack.mcmeta", "{\"pack\":{\"pack_format\":15,\"description\":\"zip\"}}");
            putZipEntry(out, "data/zipper/worldgen/biome/deep_lake.json", "{}");
            putZipEntry(out, "assets/zipper/lang/en_us.json", toJson(enUs));
            putZipEntry(out, "assets/zipper/lang/zh_cn.json", "{\"biome.zipper.deep_lake\":\"深湖\"}");
            putZipEntry(out, "assets/zipper/textures/missing.png", "not-a-lang-file");
        }
        final Map<String, String> names = requestFromDir(root.resolve("datapacks"), "zh_cn");
        assertEquals("深湖", names.get("biome.zipper.deep_lake"));
        assertEquals(1, names.size());
    }

    @Test
    void laterPackInSortOrderWinsAndBrokenJsonIsSkipped() throws Exception {
        writeLang(root.resolve("datapacks/a_first"), "ns", "en_us.json", Map.of(
            "biome.ns.shared", "First"
        ));
        writeLang(root.resolve("datapacks/b_second"), "ns", "en_us.json", Map.of(
            "biome.ns.shared", "Second"
        ));
        Files.createDirectories(root.resolve("datapacks/c_broken/assets/ns/lang"));
        Files.writeString(
            root.resolve("datapacks/c_broken/assets/ns/lang/en_us.json"),
            "{not json at all", StandardCharsets.UTF_8
        );
        final Map<String, String> names = requestFromDir(root.resolve("datapacks"), "en_us");
        assertEquals("Second", names.get("biome.ns.shared"));
        assertEquals(1, names.size());
    }

    @Test
    void resultIsCachedUntilTheDirectoryChanges() throws Exception {
        final Path dir = root.resolve("datapacks");
        writeLang(dir.resolve("pack"), "ns", "en_us.json", Map.of("biome.ns.one", "One"));
        forDir(dir);
        final Map<String, String> first = requestNames("en_us");
        final Map<String, String> second = requestNames("en_us");
        assertSame(first, second);
        // A new pack entry changes the listing fingerprint and forces a rescan.
        writeLang(dir.resolve("later"), "ns", "en_us.json", Map.of("biome.ns.two", "Two"));
        final Map<String, String> third = requestNames("en_us");
        assertEquals(Map.of("biome.ns.one", "One", "biome.ns.two", "Two"), third);
    }

    @Test
    void entryCapTrimsTheReply() throws Exception {
        final Map<String, String> many = new LinkedHashMap<>();
        for (int i = 0; i < 1025; i++) {
            many.put("biome.big.b" + i, "B" + i);
        }
        writeLang(root.resolve("datapacks/big"), "big", "en_us.json", many);
        final Map<String, String> names = requestFromDir(root.resolve("datapacks"), "en_us");
        assertEquals(1024, names.size());
    }

    private Map<String, String> requestFromDir(final Path dir, final String locale) throws Exception {
        forDir(dir);
        return requestNames(locale);
    }

    private static void writeLang(
        final Path pack,
        final String namespace,
        final String file,
        final Map<String, String> entries
    ) throws IOException {
        final Path lang = pack.resolve("assets").resolve(namespace).resolve("lang");
        Files.createDirectories(lang);
        Files.writeString(lang.resolve(file), toJson(entries), StandardCharsets.UTF_8);
    }

    private static String toJson(final Map<String, String> entries) {
        final StringBuilder out = new StringBuilder("{");
        for (final Map.Entry<String, String> entry : entries.entrySet()) {
            if (out.length() > 1) {
                out.append(',');
            }
            out.append('"').append(entry.getKey()).append("\":\"").append(entry.getValue()).append('"');
        }
        return out.append('}').toString();
    }

    private static void putZipEntry(final ZipOutputStream out, final String name, final String content)
        throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}

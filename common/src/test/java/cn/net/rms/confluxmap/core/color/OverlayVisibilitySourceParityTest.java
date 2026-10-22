package cn.net.rms.confluxmap.core.color;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The native Anvil scanner carries its own copy of the overlay-decoration rule because C
 * cannot share the Java policy. This test reads {@code native/shim/anvilscan.c} and fails
 * when its bulk-foliage substrings drift from {@link OverlayVisibility#BULK_FOLIAGE_SUBSTRINGS}
 * - the same source-derivation approach CubiomesSourceParityTest uses to keep cubiomes tables
 * honest.
 */
class OverlayVisibilitySourceParityTest {

    private static final Pattern FUNCTION_BODY = Pattern.compile(
        "static int cfxIsOverlayDecoration\\(.*?\\{(.*?)\\n\\}",
        Pattern.DOTALL
    );
    private static final Pattern EXCLUSION_SUBSTRING = Pattern.compile(
        "strstr\\(name, \"([^\"]+)\"\\) == NULL"
    );

    @org.junit.jupiter.api.Test
    void nativeScannerBulkFoliageSubstringsMatchTheSharedPolicy() throws Exception {
        final String source = read(repoFile("native/shim/anvilscan.c"));
        final Matcher body = FUNCTION_BODY.matcher(source);
        assertTrue(body.find(), "cfxIsOverlayDecoration not found in anvilscan.c");

        final List<String> excluded = new ArrayList<>();
        final Matcher substring = EXCLUSION_SUBSTRING.matcher(body.group(1));
        while (substring.find()) {
            excluded.add(substring.group(1));
        }

        final List<String> expected = new ArrayList<>(List.of(OverlayVisibility.BULK_FOLIAGE_SUBSTRINGS));
        assertTrue(
            excluded.size() == expected.size() && excluded.containsAll(expected),
            "anvilscan.c excludes " + excluded + " but the shared policy hides " + expected
        );
    }

    private static String read(final Path path) throws Exception {
        final String content = Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(content.length() > 0, path + " is empty");
        return content;
    }

    private static Path repoFile(final String relative) {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            final Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("repository file not found: " + relative);
    }
}

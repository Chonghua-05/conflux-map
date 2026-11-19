package cn.net.rms.confluxmap.core.color;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The native Anvil scanner also carries its own copy of the column-descent interpretation
 * because C cannot share the Java seam. This test reads {@code native/shim/anvilscan.c} and
 * fails when its air and promoted-cover rules drift from {@link OverlaySlotAssignment} - the
 * same source-derivation approach {@link OverlayVisibilitySourceParityTest} uses to keep the
 * scanner's overlay policy honest. The drift it guards against is real: the client's descent
 * branch once filled its foliage slot with air (a phantom tint baked into map colors) while
 * this scanner already skipped air, and only the shared rule keeps the two from diverging
 * again in either direction.
 */
class OverlaySlotAssignmentSourceParityTest {

    private static final Pattern FUNCTION_BODY = Pattern.compile(
        "static int (cfx\\w+)\\(.*?\\{(.*?)\\n\\}",
        Pattern.DOTALL
    );
    private static final Pattern STRING_COMPARE = Pattern.compile(
        "strcmp\\(name, \"([^\"]+)\"\\)"
    );
    private static final Pattern SUFFIX_DECLARATION = Pattern.compile(
        "suffix\\[\\] = \"([^\"]+)\""
    );
    private static final Pattern AIR_SKIP_LOOP = Pattern.compile(
        "while\\s*\\([^{;]*?cfxIsAir\\("
    );

    @org.junit.jupiter.api.Test
    void nativeScannerAirNamesMatchTheSharedRule() throws Exception {
        final String body = functionBody(source(), "cfxIsAir");
        final List<String> names = new ArrayList<>();
        final Matcher compare = STRING_COMPARE.matcher(body);
        while (compare.find()) {
            names.add(compare.group(1));
        }
        assertSameSet(names, OverlaySlotAssignment.AIR_MATERIAL_IDS, "air");
    }

    @org.junit.jupiter.api.Test
    void nativeScannerPromotedCoverNamesMatchTheSharedRule() throws Exception {
        final List<String> covers = new ArrayList<>();
        final Matcher snow = STRING_COMPARE.matcher(functionBody(source(), "cfxIsSnow"));
        while (snow.find()) {
            covers.add(snow.group(1));
        }
        assertSameSet(covers, OverlaySlotAssignment.SNOW_COVER_MATERIAL_IDS, "snow cover");

        final Matcher carpet = SUFFIX_DECLARATION.matcher(functionBody(source(), "cfxIsCarpet"));
        assertTrue(carpet.find(), "cfxIsCarpet declares no suffix in anvilscan.c");
        final List<String> suffixes = new ArrayList<>(List.of(carpet.group(1)));
        assertSameSet(suffixes, OverlaySlotAssignment.PROMOTED_COVER_SUFFIXES, "carpet suffix");
    }

    @org.junit.jupiter.api.Test
    void nativeScannerSkipsAirBelowACoverInsteadOfRecordingIt() throws Exception {
        final String body = functionBody(source(), "cfxSummarizeSample");
        assertTrue(
            AIR_SKIP_LOOP.matcher(body).find(),
            "cfxSummarizeSample no longer descends through air: air would become its surface"
                + " or overlay and diverge from the OverlaySlotAssignment rule that air fills"
                + " no slot"
        );
        assertTrue(
            body.contains("cfxIsOverlayDecoration(cover)"),
            "cfxSummarizeSample no longer routes the no-descent overlay through"
                + " cfxIsOverlayDecoration, whose air exclusion this test pins"
        );
        final Matcher decorationAir = Pattern.compile("cfxEndsWith\\(name, \"air\"\\)")
            .matcher(functionBody(source(), "cfxIsOverlayDecoration"));
        assertTrue(
            decorationAir.find(),
            "cfxIsOverlayDecoration no longer excludes air-suffixed names: the scanner would"
                + " start recording air as overlay"
        );
    }

    private static String source() throws Exception {
        return OverlayVisibilitySourceParityTest.read(
            OverlayVisibilitySourceParityTest.repoFile("native/shim/anvilscan.c")
        );
    }

    private static String functionBody(final String source, final String function) {
        final Matcher body = FUNCTION_BODY.matcher(source);
        while (body.find()) {
            if (function.equals(body.group(1))) {
                return body.group(2);
            }
        }
        throw new AssertionError(function + " not found in anvilscan.c");
    }

    private static void assertSameSet(
        final List<String> actual,
        final String[] expected,
        final String what
    ) {
        final List<String> missing = new ArrayList<>(List.of(expected));
        missing.removeAll(actual);
        final List<String> extra = new ArrayList<>(actual);
        extra.removeAll(List.of(expected));
        assertTrue(
            missing.isEmpty() && extra.isEmpty(),
            "anvilscan.c " + what + " set is " + actual
                + " but the shared OverlaySlotAssignment rule is " + List.of(expected)
        );
    }
}

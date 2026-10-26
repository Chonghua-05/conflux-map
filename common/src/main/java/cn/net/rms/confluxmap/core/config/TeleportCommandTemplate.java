package cn.net.rms.confluxmap.core.config;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import java.util.Locale;
import java.util.Optional;

/** Validates and renders the client-configured fullscreen-map teleport command. */
public final class TeleportCommandTemplate {
    public static final String X = "{x}";
    public static final String Y = "{y}";
    public static final String Z = "{z}";
    public static final String DIMENSION = "{dimension}";
    public static final String WORLD = "{world}";

    private TeleportCommandTemplate() {
    }

    public static boolean valid(final String template) {
        if (template == null || template.isBlank() || containsLineBreak(template)) {
            return false;
        }
        return template.contains(X) && template.contains(Y) && template.contains(Z);
    }

    public static boolean supportsDimensionSwitch(final String template) {
        return valid(template) && template.contains(DIMENSION);
    }

    public static boolean supportsWorldSwitch(final String template) {
        return valid(template) && template.contains(WORLD);
    }

    /** First command-tree literal, without a leading slash. */
    public static Optional<String> commandName(final String template) {
        if (!valid(template)) {
            return Optional.empty();
        }
        final String normalized = stripLeadingSlash(template.trim());
        final int separator = firstWhitespace(normalized);
        final String name = separator < 0 ? normalized : normalized.substring(0, separator);
        return name.isBlank() ? Optional.empty() : Optional.of(name);
    }

    public static String render(
        final String template,
        final double x,
        final double y,
        final double z,
        final DimensionId dimension,
        final WorldIdentity world
    ) {
        if (!valid(template)) {
            throw new IllegalArgumentException("invalid teleport command template");
        }
        return stripLeadingSlash(template.trim())
            .replace(X, coordinate(x))
            .replace(Y, coordinate(y))
            .replace(Z, coordinate(z))
            .replace(DIMENSION, dimension.toString())
            .replace(WORLD, world.worldId());
    }

    /**
     * Fixed-point coordinate text. {@link Double#toString} switches to computerized scientific
     * notation ("1.0E7") for magnitudes at or above 1e7 and below 1e-3, both reachable inside
     * the world border, and vanilla command parsing accepts only digits, '.', and '-'.
     */
    private static String coordinate(final double value) {
        final String fixed = String.format(Locale.ROOT, "%.6f", value);
        int end = fixed.length();
        while (end > 0 && fixed.charAt(end - 1) == '0') {
            end--;
        }
        if (fixed.charAt(end - 1) == '.') {
            end--;
        }
        return fixed.substring(0, end);
    }

    private static String stripLeadingSlash(final String command) {
        return command.startsWith("/") ? command.substring(1).stripLeading() : command;
    }

    private static int firstWhitespace(final String text) {
        for (int index = 0; index < text.length(); index++) {
            if (Character.isWhitespace(text.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private static boolean containsLineBreak(final String text) {
        return text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
    }
}

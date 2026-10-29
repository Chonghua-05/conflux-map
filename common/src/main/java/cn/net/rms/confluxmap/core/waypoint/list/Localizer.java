package cn.net.rms.confluxmap.core.waypoint.list;

/**
 * Localizes template-composed strings. The screen layer supplies an
 * implementation backed by the game's translation table; core models use it
 * to compose row secondary text without depending on Minecraft.
 */
@FunctionalInterface
public interface Localizer {
    String format(String key, Object... args);
}

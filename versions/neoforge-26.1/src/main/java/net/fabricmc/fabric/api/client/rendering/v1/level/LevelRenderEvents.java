package net.fabricmc.fabric.api.client.rendering.v1.level;
import java.util.function.Consumer;
public final class LevelRenderEvents {
    public static final Event AFTER_TRANSLUCENT_TERRAIN = new Event();
    public static final Event END_MAIN = new Event();
    public static final class Event { public void register(Consumer<LevelRenderContext> listener) {} }
    private LevelRenderEvents() {}
}

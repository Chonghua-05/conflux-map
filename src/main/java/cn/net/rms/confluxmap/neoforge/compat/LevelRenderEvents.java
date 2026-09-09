package cn.net.rms.confluxmap.neoforge.compat;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Small event facade used by shared renderers; backed directly by NeoForge's render stages. */
public final class LevelRenderEvents {
    public static final Event AFTER_TRANSLUCENT_TERRAIN = new Event();
    public static final Event END_MAIN = new Event();
    private static boolean installed;

    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        NeoForge.EVENT_BUS.addListener(
            (RenderLevelStageEvent.AfterTranslucentBlocks event) ->
                AFTER_TRANSLUCENT_TERRAIN.invoke(new Context(event))
        );
        NeoForge.EVENT_BUS.addListener(
            (RenderLevelStageEvent.AfterLevel event) -> END_MAIN.invoke(new Context(event))
        );
    }

    public static final class Event {
        private final CopyOnWriteArrayList<Consumer<LevelRenderContext>> listeners =
            new CopyOnWriteArrayList<>();

        public void register(final Consumer<LevelRenderContext> listener) {
            listeners.add(listener);
        }

        private void invoke(final LevelRenderContext context) {
            listeners.forEach(listener -> listener.accept(context));
        }
    }

    private static final class Context implements LevelRenderContext {
        private final RenderLevelStageEvent event;

        private Context(final RenderLevelStageEvent event) {
            this.event = event;
        }

        @Override
        public com.mojang.blaze3d.vertex.PoseStack poseStack() {
            return event.getPoseStack();
        }

        @Override
        public net.minecraft.client.renderer.SubmitNodeCollector submitNodeCollector() {
            return null;
        }
    }

    private LevelRenderEvents() {
    }
}

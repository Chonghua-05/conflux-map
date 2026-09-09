package cn.net.rms.confluxmap.neoforge.compat;

import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.server.packs.resources.ResourceManager;

/** NeoForge resource-reload registration for the client resource pack manager. */
public final class ResourceManagerHelper {
    private static final ResourceManagerHelper CLIENT = new ResourceManagerHelper(PackType.CLIENT_RESOURCES);

    private final PackType type;

    private ResourceManagerHelper(final PackType type) {
        this.type = type;
    }

    public static ResourceManagerHelper get(final PackType type) {
        if (type != PackType.CLIENT_RESOURCES) {
            throw new IllegalArgumentException("NeoForge branch only supports client resource reloads");
        }
        return CLIENT;
    }

    public void registerReloadListener(final SimpleSynchronousResourceReloadListener listener) {
        Objects.requireNonNull(listener, "listener");
        final Minecraft client = Objects.requireNonNull(Minecraft.getInstance(), "Minecraft instance");
        if (client.getResourceManager() instanceof ReloadableResourceManager manager) {
            manager.registerReloadListener(new Adapter(listener));
            return;
        }
        throw new IllegalStateException("Client resource manager is not reloadable: " + type);
    }

    private static final class Adapter extends SimplePreparableReloadListener<Void> {
        private final SimpleSynchronousResourceReloadListener delegate;

        private Adapter(final SimpleSynchronousResourceReloadListener delegate) {
            this.delegate = delegate;
        }

        @Override
        protected Void prepare(final ResourceManager manager, final ProfilerFiller profiler) {
            return null;
        }

        @Override
        protected void apply(
            final Void ignored,
            final ResourceManager manager,
            final ProfilerFiller profiler
        ) {
            delegate.onResourceManagerReload(manager);
        }

        @Override
        public String getName() {
            return delegate.getId().toString();
        }
    }
}

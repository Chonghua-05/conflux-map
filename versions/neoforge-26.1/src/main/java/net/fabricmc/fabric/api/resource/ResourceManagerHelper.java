package net.fabricmc.fabric.api.resource;
import net.minecraft.server.packs.PackType;
public final class ResourceManagerHelper {
    private static final ResourceManagerHelper INSTANCE = new ResourceManagerHelper();
    public static ResourceManagerHelper get(PackType type) { return INSTANCE; }
    public void registerReloadListener(SimpleSynchronousResourceReloadListener listener) {}
    private ResourceManagerHelper() {}
}

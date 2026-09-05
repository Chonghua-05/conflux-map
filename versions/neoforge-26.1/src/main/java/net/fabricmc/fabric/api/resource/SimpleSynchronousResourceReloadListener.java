package net.fabricmc.fabric.api.resource;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
public interface SimpleSynchronousResourceReloadListener {
    Identifier getFabricId();
    void onResourceManagerReload(ResourceManager manager);
}

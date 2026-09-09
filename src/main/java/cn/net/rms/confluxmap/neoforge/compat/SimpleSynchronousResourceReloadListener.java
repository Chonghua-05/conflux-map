package cn.net.rms.confluxmap.neoforge.compat;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
public interface SimpleSynchronousResourceReloadListener {
    Identifier getId();
    void onResourceManagerReload(ResourceManager manager);
}

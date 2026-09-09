package cn.net.rms.confluxmap.mc.color;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.neoforge.compat.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.resources.Identifier;

/** Clears captured material samples and rebuilds prediction material profiles after a resource reload. */
public final class ColorReloadListener implements SimpleSynchronousResourceReloadListener {
    private static final Identifier ID = Ids.of(ConfluxMapMod.ID, "sprite_color_cache");

    private final Minecraft client;
    private final SpriteColorSampler sampler;
    private final Runnable afterReload;

    public ColorReloadListener(
        final Minecraft client,
        final SpriteColorSampler sampler,
        final Runnable afterReload
    ) {
        this.client = client;
        this.sampler = sampler;
        this.afterReload = afterReload;
    }

    @Override
    public Identifier getId() {
        return ID;
    }

    @Override
    public void onResourceManagerReload(final ResourceManager manager) {
        sampler.clearCache();
        client.execute(afterReload);
    }
}

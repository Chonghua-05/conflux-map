package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.neoforge.compat.SimpleSynchronousResourceReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.resources.Identifier;

/** Re-resolves custom portal icon textures after F3+T so a newly added pack texture takes effect. */
public final class PortalIconReloadListener implements SimpleSynchronousResourceReloadListener {
    private static final Identifier ID = Ids.of(ConfluxMapMod.ID, "portal_icons");

    @Override
    public Identifier getFabricId() {
        return ID;
    }

    @Override
    public void reload(final ResourceManager manager) {
        PortalMarkerRenderer.clearTextureCache();
    }
}
package cn.net.rms.confluxmap.mc;

import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

public final class McGameBridge implements GameBridge {
    private final Minecraft client;
    private final SessionGuard guard;

    public McGameBridge(final Minecraft client, final SessionGuard guard) {
        this.client = client;
        this.guard = guard;
    }

    @Override
    public SessionGuard.Session session() {
        return guard.current();
    }

    @Override
    public Optional<PlayerView> player(final float tickDelta) {
        final LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return Optional.empty();
        }
        return viewOf(player, tickDelta);
    }

    @Override
    public Optional<PlayerView> viewpoint(final float tickDelta) {
        final LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return Optional.empty();
        }
        // Some client-side mods temporarily move the camera into a separate entity (for
        // example while the player's soul is detached from their body).  Map overlays are
        // screen-space views, so anchoring them to the player entity in that state makes the
        // map, markers, and waypoints appear to slide away from the actual view.  Vanilla
        // normally returns the player here; the fallback keeps this bridge safe during camera
        // teardown and on versions where no camera entity is available yet.
        final Entity cameraEntity = client.getCameraEntity();
        final Entity viewEntity = cameraEntity != null ? cameraEntity : player;
        return viewOf(viewEntity, tickDelta);
    }

    @Override
    public boolean isCameraDetached() {
        final LocalPlayer player = client.player;
        final Entity cameraEntity = client.getCameraEntity();
        return player != null && cameraEntity != null && cameraEntity != player;
    }

    private Optional<PlayerView> viewOf(final Entity entity, final float tickDelta) {
        final Identifier dim = client.level.dimension().identifier();
        return Optional.of(new PlayerView(
            Mth.lerp(tickDelta, entity.xo, entity.getX()),
            Mth.lerp(tickDelta, entity.yo, entity.getY()),
            Mth.lerp(tickDelta, entity.zo, entity.getZ()),
            entity.getEyeY(),
            entity.getViewYRot(tickDelta),
            DimensionId.of(dim.getNamespace(), dim.getPath())
        ));
    }

    @Override
    public void runOnRenderThread(final Runnable task) {
        client.execute(task);
    }
}

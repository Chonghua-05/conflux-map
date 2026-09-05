package cn.net.rms.confluxmap.mc.world;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.tile.TileService;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
//#if MC>=12111
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.attribute.SpatialAttributeInterpolator;
//#endif

/**
 * Drives {@link DaylightModel} once per client tick from the live world's sky angle and
 * vanilla gamma option, using
 * vanilla's own sky-brightness cosine curve (the same one {@code BackgroundRenderer} uses for
 * fog/sky color): {@code f = clamp(2*cos(skyAngle * 2*pi) + 0.5, 0, 1)}.
 *
 * <p>{@link ConfluxConfig#dynamicLighting} off, no world loaded, or a dimension with no sky
 * light (Nether/End - fixed brightness, and neither ever displays the SURFACE layer this
 * feeds anyway) all pin the factor to 1.0, i.e. today's undarkened rendering. Whenever {@link
 * DaylightModel#update} reports the quantized bucket moved - including the one-time jump back
 * to 1.0 when the setting or dimension makes the factor pin again - the currently active
 * world's SURFACE tiles are relit via {@link TileService#markSurfaceRelit}, so toggling the
 * setting off promptly clears any existing night-darkening instead of leaving it stale.
 * Gamma bucket changes additionally clear resident textures and prediction mips so layers
 * whose base light tint was baked into stored columns recompose from their gamma-free data.
 */
public final class McDaylightTracker {
    private final Minecraft client;
    private final ConfluxConfig config;
    private final DaylightModel model;
    private final MapWorldService mapWorlds;
    private final TileService tiles;
    private final Runnable gammaChanged;

    public McDaylightTracker(
        final Minecraft client,
        final ConfluxConfig config,
        final DaylightModel model,
        final MapWorldService mapWorlds,
        final TileService tiles,
        final Runnable gammaChanged
    ) {
        this.client = client;
        this.config = config;
        this.model = model;
        this.mapWorlds = mapWorlds;
        this.tiles = tiles;
        this.gammaChanged = gammaChanged;
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(c -> tick());
    }

    private void tick() {
        final float nextGamma = MinecraftAccess.gamma(client);
        final boolean gammaBucketChanged = !DaylightModel.sameGammaBucket(
            model.gamma(), nextGamma
        );
        final boolean changed = model.update(computeFactor(), nextGamma);
        if (!changed) {
            return;
        }
        if (gammaBucketChanged) {
            gammaChanged.run();
        }
        final MapWorld world = mapWorlds.current();
        if (world != null) {
            tiles.markSurfaceRelit(world.session().token());
        }
    }

    private float computeFactor() {
        final ClientLevel world = client.level;
        if (!config.dynamicLighting || world == null || client.player == null || !world.dimensionType().hasSkyLight()) {
            return 1f;
        }
        //#if MC>=12111
        // 26.1 treats the sun-angle attribute as positional. Supplying the local
        // player position avoids the hard failure thrown by getDimensionValue for
        // positional attributes while preserving vanilla's environment overrides.
        return daylightFactor(world.environmentAttributes().getValue(
            EnvironmentAttributes.SUN_ANGLE,
            client.player.position(),
            new SpatialAttributeInterpolator()
        ));
        //#else
        //$$ return daylightFactor(world.getSkyAngleRadians(1.0f));
        //#endif
    }

    /**
     * Vanilla's sky-brightness cosine curve, taking the sun angle in whatever unit the running
     * version reports it: radians from {@code World#getSkyAngleRadians} before 1.21.11, degrees
     * from {@code EnvironmentAttributes#SUN_ANGLE} on 1.21.11 and later. Vanilla's own
     * {@code DaylightDetectorBlock} applies the same degree-to-radian conversion to that
     * attribute; feeding the raw degrees to {@code cos} instead runs ~57 brightness cycles per
     * Minecraft day, which reads as the map flickering between bright and dark.
     */
    static float daylightFactor(final float sunAngle) {
        //#if MC>=12111
        final float skyAngle = sunAngle * Mth.DEG_TO_RAD;
        //#else
        //$$ final float skyAngle = sunAngle;
        //#endif
        final float raw = Mth.cos(skyAngle) * 2.0f + 0.5f;
        return Mth.clamp(raw, 0f, 1f);
    }
}

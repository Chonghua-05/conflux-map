package cn.net.rms.confluxmap.neoforge;

import cn.net.rms.confluxmap.nativepredict.NativeLib;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointProto;
import cn.net.rms.confluxmap.neoforge.network.PlayNetworking;
import cn.net.rms.confluxmap.server.ConfluxMapCompanion;
import cn.net.rms.confluxmap.server.ServerConfigIo;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Common composition root for dedicated and integrated servers. */
@Mod(ConfluxMapNeoForge.MOD_ID)
public final class ConfluxMapNeoForge {
    public static final String MOD_ID = "confluxmap";
    public static final Logger LOGGER = LogManager.getLogger("ConfluxMap");

    public ConfluxMapNeoForge(final IEventBus modEventBus, final ModContainer modContainer) {
        PlayNetworking.declareChannel(Identifier.parse(Proto.CHANNEL_ID));
        PlayNetworking.declareChannel(Identifier.parse(SharedWaypointProto.CHANNEL_ID));
        modEventBus.addListener(PlayNetworking::registerPayloads);
        modEventBus.addListener(ConfluxMapNeoForge::initialize);
        new ConfluxMapCompanion(ServerConfigIo.atDefault(FMLPaths.CONFIGDIR.get())).initialize();
        LOGGER.info("Conflux Map {} NeoForge adapter loaded", modContainer.getModInfo().getVersion());
    }

    private static void initialize(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> NativeLib.init(FMLPaths.GAMEDIR.get().resolve(MOD_ID)));
    }
}

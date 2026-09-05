package cn.net.rms.confluxmap;

import net.neoforged.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class ConfluxMapMod {
    public static final String ID = "confluxmap";
    public static final Logger LOGGER = LogManager.getLogger("ConfluxMap");
    private ConfluxMapMod() {}
    public static String getName() { return ModList.get().getModContainerById(ID).orElseThrow().getModInfo().getDisplayName(); }
    public static String getVersion() { return ModList.get().getModContainerById(ID).orElseThrow().getModInfo().getVersion().toString(); }
}

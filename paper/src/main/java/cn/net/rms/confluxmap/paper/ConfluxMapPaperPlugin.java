package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.server.ServerConfigIo;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper entrypoint for the standalone Conflux Map server companion artifact. */
public final class ConfluxMapPaperPlugin extends JavaPlugin {
    private PaperCompanion companion;

    @Override
    public void onEnable() {
        final Path configFile = getServer().getWorldContainer().toPath()
            .resolve("config/confluxmap/server.json");
        companion = new PaperCompanion(this, new ServerConfigIo(configFile, LogManager.getLogger("ConfluxMap-Paper")));
        companion.enable();
        final PaperCommands commands = new PaperCommands(companion, getSLF4JLogger());
        java.util.Objects.requireNonNull(getCommand("confluxmap")).setExecutor(commands);
        java.util.Objects.requireNonNull(getCommand("confluxmap")).setTabCompleter(commands);
    }

    @Override
    public void onDisable() {
        if (companion != null) {
            companion.disable();
            companion = null;
        }
    }
}

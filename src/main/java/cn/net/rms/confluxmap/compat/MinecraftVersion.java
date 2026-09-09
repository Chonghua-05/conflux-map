package cn.net.rms.confluxmap.compat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Compile-time Minecraft release identity for the active version subproject. */
public final class MinecraftVersion {
    private static final String RESOURCE = "/confluxmap.version";

    private MinecraftVersion() {
    }

    public static String current() {
        final Properties properties = new Properties();
        try (InputStream stream = MinecraftVersion.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing " + RESOURCE);
            }
            properties.load(stream);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read " + RESOURCE, e);
        }
        final String version = properties.getProperty("minecraft_version");
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("Missing minecraft_version in " + RESOURCE);
        }
        return version;
    }
}

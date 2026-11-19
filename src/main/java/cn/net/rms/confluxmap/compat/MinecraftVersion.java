package cn.net.rms.confluxmap.compat;

/** Compile-time Minecraft release identity for the active version subproject. */
public final class MinecraftVersion {
    private MinecraftVersion() {
    }

    public static String current() {
        return "26.3";
    }
}
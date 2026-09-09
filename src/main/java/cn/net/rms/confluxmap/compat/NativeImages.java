package cn.net.rms.confluxmap.compat;

import cn.net.rms.confluxmap.core.util.Argb;
import com.mojang.blaze3d.platform.NativeImage;

/** Normalizes {@link NativeImage} pixel access to the core's ARGB representation. */
public final class NativeImages {
    private NativeImages() {
    }

    public static int getArgb(final NativeImage image, final int x, final int y) {
        return image.getPixel(x, y);
    }

    public static void setArgb(final NativeImage image, final int x, final int y, final int argb) {
        image.setPixel(x, y, argb);
    }
}

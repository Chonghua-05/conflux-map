package cn.net.rms.confluxmap.compat;

import cn.net.rms.confluxmap.core.util.Argb;
import com.mojang.blaze3d.platform.NativeImage;

/** Normalizes {@link NativeImage} pixel access to the core's ARGB representation. */
public final class NativeImages {
    private NativeImages() {
    }

    public static int getArgb(final NativeImage image, final int x, final int y) {
        //#if MC>=12103
        return image.getPixel(x, y);
        //#else
        //$$ return Argb.toAbgr(image.getColor(x, y));
        //#endif
    }

    public static void setArgb(final NativeImage image, final int x, final int y, final int argb) {
        //#if MC>=12103
        image.setPixel(x, y, argb);
        //#else
        //$$ image.setColor(x, y, Argb.toAbgr(argb));
        //#endif
    }
}

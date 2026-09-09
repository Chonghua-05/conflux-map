package cn.net.rms.confluxmap.compat;

import cn.net.rms.confluxmap.core.config.HudAmbient;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Reads the axis-aligned GUI transform currently on the matrix stack.
 *
 * <p>HUD elements are drawn with integer coordinates that say nothing about a transform another
 * mod installed around them, so measuring an element's real size means asking the matrix what
 * those coordinates map to. The GUI pose carries no rotation in practice, so translation and
 * per-axis scale describe it completely.
 */
public final class GuiTransforms {
    private GuiTransforms() {
    }
    public static HudAmbient ambient(final GuiGraphicsExtractor context) {
        final var pose = context.pose();
        return new HudAmbient(pose.m20(), pose.m21(), pose.m00(), pose.m11());
    }
}

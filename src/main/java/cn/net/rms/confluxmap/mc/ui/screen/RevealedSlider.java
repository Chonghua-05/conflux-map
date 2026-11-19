package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.mc.render.GuiShapes;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.network.chat.Component;

/**
 * Slider base whose handle follows the widget alpha on 1.17-1.19, where vanilla's
 * handle pass resets the shader color to opaque and would float the knob over rows
 * that have not queued into the stagger fade yet.
 */
abstract class RevealedSlider extends AbstractSliderButton {
    RevealedSlider(final int x, final int y, final int width, final int height, final double position) {
        super(x, y, width, height, Component.literal(""), position);
    }

}
package cn.net.rms.confluxmap.compat;

import cn.net.rms.confluxmap.mc.ui.widget.ConfluxTextButton;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** Small construction and layout seam for widget API changes across Minecraft versions. */
public final class Widgets {
    private Widgets() {
    }

    public static Button button(
        final int x,
        final int y,
        final int width,
        final int height,
        final Component message,
        final Button.OnPress onPress
    ) {
        return new ConfluxTextButton(x, y, width, height, message, onPress);
    }

    public static int x(final AbstractWidget widget) {
        return widget.getX();
    }

    public static int y(final AbstractWidget widget) {
        return widget.getY();
    }

    public static void setX(final AbstractWidget widget, final int x) {
        widget.setX(x);
    }

    public static void setY(final AbstractWidget widget, final int y) {
        widget.setY(y);
    }

    public static void tick(final EditBox field) {
    }

    public static void setText(final EditBox field, final String text) {
        field.setValue(text);
    }

    public static String text(final EditBox field) {
        return field.getValue();
    }

    public static void setChangedListener(
        final EditBox field,
        final Consumer<String> listener
    ) {
        field.setResponder(listener);
    }
}

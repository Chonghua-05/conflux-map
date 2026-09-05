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
        //#if MC>=11904
        return widget.getX();
        //#else
        //$$ return widget.x;
        //#endif
    }

    public static int y(final AbstractWidget widget) {
        //#if MC>=11904
        return widget.getY();
        //#else
        //$$ return widget.y;
        //#endif
    }

    public static void setX(final AbstractWidget widget, final int x) {
        //#if MC>=11904
        widget.setX(x);
        //#else
        //$$ widget.x = x;
        //#endif
    }

    public static void setY(final AbstractWidget widget, final int y) {
        //#if MC>=11904
        widget.setY(y);
        //#else
        //$$ widget.y = y;
        //#endif
    }

    public static void tick(final EditBox field) {
        //#if MC<11904
        //$$ field.tick();
        //#endif
    }

    public static void setText(final EditBox field, final String text) {
        //#if MC>=260100
        field.setValue(text);
        //#else
        //$$ field.setText(text);
        //#endif
    }

    public static String text(final EditBox field) {
        //#if MC>=260100
        return field.getValue();
        //#else
        //$$ return field.getText();
        //#endif
    }

    public static void setChangedListener(
        final EditBox field,
        final Consumer<String> listener
    ) {
        //#if MC>=260100
        field.setResponder(listener);
        //#else
        //$$ field.setChangedListener(listener);
        //#endif
    }
}

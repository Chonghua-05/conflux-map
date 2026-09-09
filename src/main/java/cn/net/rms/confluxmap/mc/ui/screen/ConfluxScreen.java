package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.compat.Texts;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Screen base that keeps the MatrixStack-to-DrawContext rewrite at one lifecycle seam. */
public abstract class ConfluxScreen extends Screen {
    private final Map<AbstractWidget, String> disabledTooltipKeys = new IdentityHashMap<>();
    private final HotkeyFocusDelay initialFocusDelay = new HotkeyFocusDelay();
    private Runnable enterAction;
    private BooleanSupplier enterActionEnabled = () -> false;
    private AbstractWidget deferredInitialFocus;
    /**
     * Screen.render owns the widget loop, but its implicit background must not cover
     * renderContents. Only 1.21.5 and older reach that path: 1.21.6 moved the background
     * call up into Screen.renderWithTooltip, which already runs before renderContents.
     */
    private boolean renderingVanillaWidgets;

    protected ConfluxScreen(final Component title) {
        super(title);
    }

    /** Leaves a new text field unfocused until the opening key's character event has drained. */
    protected final void deferInitialFocusUntilNextTick(final AbstractWidget widget) {
        deferredInitialFocus = widget;
        initialFocusDelay.defer();
        setFocused(null);
    }
    @Override
    protected void setInitialFocus() {
        if (initialFocusDelay.shouldFocus()) {
            super.setInitialFocus();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (deferredInitialFocus == null) {
            return;
        }
        initialFocusDelay.advanceTick();
        if (initialFocusDelay.shouldFocus()) {
            final AbstractWidget widget = deferredInitialFocus;
            deferredInitialFocus = null;
            setInitialFocus(widget);
        }
    }
    @Override
    public final void extractRenderState(
        final GuiGraphicsExtractor context,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        final GuiDraw draw = GuiDraw.of(context);
        renderContents(draw, mouseX, mouseY, tickDelta);
        // 26.1 renamed the retained-mode entry points but kept the ordering: the background is
        // extracted before the widget list is walked.
        renderingVanillaWidgets = true;
        try {
            super.extractRenderState(context, mouseX, mouseY, tickDelta);
        } finally {
            renderingVanillaWidgets = false;
        }
        renderAfterWidgets(draw, mouseX, mouseY, tickDelta);
        renderDisabledTooltip(draw, mouseX, mouseY);
    }

    @Override
    public final void extractBackground(
        final GuiGraphicsExtractor context,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        if (!renderingVanillaWidgets) {
            renderVanillaBackground(context, mouseX, mouseY, tickDelta);
        }
    }

    protected void renderVanillaBackground(
        final GuiGraphicsExtractor context,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        super.extractBackground(context, mouseX, mouseY, tickDelta);
    }

    protected abstract void renderContents(GuiDraw draw, int mouseX, int mouseY, float tickDelta);

    /** Makes Enter activate the screen's visible primary action when it is available. */
    protected final void setEnterAction(
        final BooleanSupplier enabled,
        final Runnable action
    ) {
        enterActionEnabled = enabled;
        enterAction = action;
    }

    /** Removes an Enter binding when a rebuilt screen no longer exposes that action. */
    protected final void clearEnterAction() {
        enterActionEnabled = () -> false;
        enterAction = null;
    }

    @Override
    public boolean keyPressed(final KeyEvent input) {
        final int keyCode = input.key();
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
            && enterAction != null && enterActionEnabled.getAsBoolean()) {
            enterAction.run();
            return true;
        }
        return super.keyPressed(input);
    }

    protected void renderAfterWidgets(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
    }

    /** Draws a stable monochrome scrollbar for a row-based viewport. */
    protected final void drawListScrollbar(
        final GuiDraw draw,
        final int x,
        final int top,
        final int height,
        final int totalRows,
        final int visibleRows,
        final int offset
    ) {
        if (totalRows <= visibleRows || visibleRows <= 0 || height <= 0) {
            return;
        }
        final int thumbHeight = Math.max(10, height * visibleRows / totalRows);
        final int maxOffset = totalRows - visibleRows;
        final int travel = Math.max(1, height - thumbHeight);
        final int thumbY = top + Math.round(travel * (offset / (float) maxOffset));
        draw.fill(x, top, x + 2, top + height, 0xFF3A3A3A);
        draw.fill(x, thumbY, x + 2, thumbY + thumbHeight, 0xFFFFFFFF);
    }

    /** Associates one inactive control with the translated reason it cannot be used. */
    protected final void setDisabledTooltip(final AbstractWidget widget, final String translationKey) {
        if (widget == null) {
            return;
        }
        if (translationKey == null) {
            disabledTooltipKeys.remove(widget);
            return;
        }
        disabledTooltipKeys.put(widget, translationKey);
    }

    private void renderDisabledTooltip(final GuiDraw draw, final int mouseX, final int mouseY) {
        disabledTooltipKeys.entrySet().removeIf(entry -> !children().contains(entry.getKey()));
        for (final Map.Entry<AbstractWidget, String> entry : disabledTooltipKeys.entrySet()) {
            final AbstractWidget widget = entry.getKey();
            if (widget.visible && !widget.active && widget.isHoveredOrFocused()) {
                draw.drawTooltip(
                    this,
                    this.font,
                    Texts.translatable(entry.getValue()),
                    mouseX,
                    mouseY
                );
                return;
            }
        }
    }
}

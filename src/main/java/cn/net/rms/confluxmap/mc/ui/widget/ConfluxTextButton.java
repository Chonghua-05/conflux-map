package cn.net.rms.confluxmap.mc.ui.widget;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.UiIcon;
import cn.net.rms.confluxmap.mc.ui.UiResourceTheme;
import cn.net.rms.confluxmap.mc.ui.UiTextureRegion;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.Identifier;

/** Shared text or icon button that keeps the Conflux style unless a text control is reskinned. */
public final class ConfluxTextButton extends Button {
    private static final int ICON_SIZE = 16;
    private static final int BACKGROUND = 0xE0181818;
    private static final int HOVER_BACKGROUND = 0xF02A2A2A;
    private static final int DISABLED_BACKGROUND = 0xD0121212;
    private static final int BORDER = 0xFF8A8A8A;
    private static final int HOVER_BORDER = 0xFFFFFFFF;
    private static final int DISABLED_BORDER = 0xFF4A4A4A;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int DISABLED_TEXT = 0xFF777777;
    private final Identifier icon;

    public ConfluxTextButton(
        final int x,
        final int y,
        final int width,
        final int height,
        final net.minecraft.network.chat.Component message,
        final OnPress onPress
    ) {
        this(x, y, width, height, message, null, onPress);
    }

    public ConfluxTextButton(
        final int x,
        final int y,
        final int width,
        final int height,
        final net.minecraft.network.chat.Component message,
        final Identifier icon,
        final OnPress onPress
    ) {
        super(x, y, width, height, message, onPress, Button.DEFAULT_NARRATION);
        this.icon = icon;
    }

    @Override
    protected void extractContents(
        final GuiGraphicsExtractor context,
        final int mouseX,
        final int mouseY,
        final float delta
    ) {
        if (useVanillaButtonStyle()) {
            extractDefaultSprite(context);
            drawForeground(GuiDraw.of(context));
            return;
        }
        drawContents(GuiDraw.of(context));
    }

    private void drawContents(final GuiDraw draw) {
        final int x = Widgets.x(this);
        final int y = Widgets.y(this);
        final int right = x + getWidth();
        final int bottom = y + getHeight();
        final boolean highlighted = active && (isHoveredOrFocused() || isFocused());
        final int background = !active
            ? DISABLED_BACKGROUND
            : highlighted ? HOVER_BACKGROUND : BACKGROUND;
        final int border = !active ? DISABLED_BORDER : highlighted ? HOVER_BORDER : BORDER;
        draw.fill(x, y, right, bottom, background);
        draw.fill(x, y, right, y + 1, border);
        draw.fill(x, bottom - 1, right, bottom, border);
        draw.fill(x, y, x + 1, bottom, border);
        draw.fill(right - 1, y, right, bottom, border);

        drawForeground(draw);
    }

    private void drawForeground(final GuiDraw draw) {
        if (icon == null) {
            drawText(draw);
        } else {
            drawIcon(draw);
        }
    }

    private void renderWithoutMessage(final Runnable render) {
        final net.minecraft.network.chat.Component message = getMessage();
        setMessage(Texts.literal(""));
        try {
            render.run();
        } finally {
            setMessage(message);
        }
    }

    private void drawIcon(final GuiDraw draw) {
        final ConfluxMapClient app = ConfluxMapClient.get();
        final UiResourceTheme theme = app == null ? null : app.uiResourceTheme();
        final UiIcon resolved = theme == null ? UiIcon.monochrome(icon) : theme.icon(icon);
        final UiTextureRegion texture = resolved.region();
        RenderUtil.bindTexture(Minecraft.getInstance(), texture.texture());
        RenderUtil.drawTintedQuad(
            draw.matrices(),
            Widgets.x(this) + (getWidth() - ICON_SIZE) / 2,
            Widgets.y(this) + (getHeight() - ICON_SIZE) / 2,
            ICON_SIZE,
            ICON_SIZE,
            texture.u0(),
            texture.v0(),
            texture.u1(),
            texture.v1(),
            active ? TEXT : DISABLED_TEXT
        );
    }

    private void drawText(final GuiDraw draw) {
        final int x = Widgets.x(this);
        final int y = Widgets.y(this);
        final Minecraft client = Minecraft.getInstance();
        final net.minecraft.network.chat.Component message = getMessage();
        final int availableWidth = Math.max(1, getWidth() - 8);
        final String fitted = client.font.plainSubstrByWidth(message.getString(), availableWidth);
        final int textWidth = client.font.width(fitted);
        final int fontHeight = client.font.lineHeight;
        draw.drawTextWithShadow(
            client.font,
            fitted,
            x + (getWidth() - textWidth) / 2f,
            y + (getHeight() - fontHeight) / 2f,
            active ? TEXT : DISABLED_TEXT
        );
    }

    private static boolean useVanillaButtonStyle() {
        final ConfluxMapClient app = ConfluxMapClient.get();
        return app != null && app.uiResourceTheme() != null
            && app.uiResourceTheme().useVanillaButtonStyle();
    }
}

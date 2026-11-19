package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Component;

public final class ServerRestrictionScreen extends ConfluxScreen {
    private final Component message;
    private List<FormattedCharSequence> lines = List.of();
    private int scrollOffset;

    public ServerRestrictionScreen(final String customMessage) {
        super(Texts.translatable("confluxmap.screen.restriction.title"));
        message = customMessage == null || customMessage.isBlank()
            ? Texts.translatable("confluxmap.screen.restriction.message")
            : Texts.literal(customMessage);
    }

    public static Screen resolve(final Screen requested, final CompanionSession session) {
        if (requested instanceof FullscreenMapScreen && !session.worldMapAllowed()) {
            return new ServerRestrictionScreen(session.restrictionMessage());
        }
        return requested;
    }

    @Override
    protected void init() {
        lines = this.font.split(message, Math.max(40, width - 32));
        scrollOffset = Math.min(scrollOffset, Math.max(0, lines.size() - visibleLines()));
        addRenderableWidget(Widgets.button(
            width / 2 - 50, height - 32, 100, 20,
            Texts.translatable("gui.back"), ignored -> onClose()
        ));
        setEnterAction(() -> true, this::onClose);
    }

    @Override
    public void onClose() {
        MinecraftAccess.setScreen(Minecraft.getInstance(), null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseScrolled(
        final double mouseX, final double mouseY, final double horizontalAmount, final double amount
    ) {
        scrollOffset = Math.max(0, Math.min(
            Math.max(0, lines.size() - visibleLines()), scrollOffset - (int) Math.signum(amount)
        ));
        return true;
    }

    private int visibleLines() {
        return Math.max(1, (height - 100) / (this.font.lineHeight + 2));
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        draw.extractBackground(this, mouseX, mouseY, tickDelta);
        draw.drawTextWithShadow(
            this.font, getTitle(), width / 2f - this.font.width(getTitle()) / 2f, 24, 0xFFFFFFFF
        );
        int lineY = 56;
        for (int index = scrollOffset; index < Math.min(lines.size(), scrollOffset + visibleLines()); index++) {
            final FormattedCharSequence line = lines.get(index);
            draw.drawTextWithShadow(
                this.font, line, width / 2f - this.font.width(line) / 2f, lineY, 0xFFFFFFFF
            );
            lineY += this.font.lineHeight + 2;
        }
        drawListScrollbar(
            draw, width - 12, 56, visibleLines() * (this.font.lineHeight + 2),
            lines.size(), visibleLines(), scrollOffset
        );
    }
}
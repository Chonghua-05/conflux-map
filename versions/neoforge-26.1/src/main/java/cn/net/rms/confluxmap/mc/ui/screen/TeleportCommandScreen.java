package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.config.TeleportCommandTemplate;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;

/** Edits the fullscreen map teleport command template. */
final class TeleportCommandScreen extends ConfluxScreen {
    private final Screen parent;
    private final ConfluxConfig config;
    private final ConfigIo configIo;
    private EditBox commandField;
    private Button doneButton;

    TeleportCommandScreen(
        final Screen parent,
        final ConfluxConfig config,
        final ConfigIo configIo
    ) {
        super(Texts.translatable("confluxmap.screen.teleport_command.title"));
        this.parent = parent;
        this.config = config;
        this.configIo = configIo;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        final int fieldWidth = Math.min(420, width - 24);
        commandField = new EditBox(
            this.font, width / 2 - fieldWidth / 2, 66, fieldWidth, 20,
            Texts.translatable("confluxmap.config.waypoints.teleport_command")
        );
        commandField.setMaxLength(512);
        commandField.setValue(config.teleportCommand);
        addRenderableWidget(commandField);
        setInitialFocus(commandField);
        doneButton = addRenderableWidget(Widgets.button(
            width / 2 - 104, 100, 100, 20,
            Texts.translatable("confluxmap.screen.waypoint.done"), ignored -> submit()
        ));
        addRenderableWidget(Widgets.button(
            width / 2 + 4, 100, 100, 20,
            Texts.translatable("confluxmap.screen.waypoint.cancel"), ignored -> onClose()
        ));
        setEnterAction(() -> doneButton != null && doneButton.active, this::submit);
        refreshDone();
    }

    @Override
    public void tick() {
        Widgets.tick(commandField);
        refreshDone();
    }

    private void refreshDone() {
        doneButton.active = commandField != null
            && TeleportCommandTemplate.valid(commandField.getValue());
    }

    private void submit() {
        final String template = commandField.getValue().trim();
        if (!TeleportCommandTemplate.valid(template)) {
            return;
        }
        config.teleportCommand = template;
        configIo.save(config);
        onClose();
    }

    @Override
    public void onClose() {
        MinecraftAccess.setScreen(Minecraft.getInstance(), parent);
    }

    @Override
    protected void renderContents(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        final String title = getTitle().getString();
        draw.drawTextWithShadow(
            this.font, title,
            width / 2f - this.font.width(title) / 2f, 22, 0xFFFFFFFF
        );
        final String help = Texts.translatable("confluxmap.screen.teleport_command.help").getString();
        draw.drawTextWithShadow(
            this.font, help,
            width / 2f - this.font.width(help) / 2f, 48, 0xFFBBBBBB
        );
        if (commandField != null && !TeleportCommandTemplate.valid(commandField.getValue())) {
            final String invalid = Texts.translatable(
                "confluxmap.screen.teleport_command.invalid"
            ).getString();
            draw.drawTextWithShadow(
                this.font, invalid,
                width / 2f - this.font.width(invalid) / 2f, 126, 0xFFFF7777
            );
        }
    }
}

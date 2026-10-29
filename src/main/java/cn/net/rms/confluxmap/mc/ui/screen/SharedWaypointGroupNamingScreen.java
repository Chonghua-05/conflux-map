package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.shared.SharedWaypointGroup;
import cn.net.rms.confluxmap.mc.net.shared.SharedWaypointClient;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * Create/rename form for one server-owned group. Submit closes on a successful send;
 * the authoritative outcome arrives through the shared-waypoint result channel.
 */
final class SharedWaypointGroupNamingScreen extends ConfluxScreen {
    private static final int FIELD_WIDTH = 220;
    private static final int FIELD_HEIGHT = 20;

    private final Screen parent;
    private final SharedWaypointGroup existingGroup;
    private final Consumer<String> onSuccess;
    private TextFieldWidget nameField;
    private ButtonWidget doneButton;
    private String errorKey;

    SharedWaypointGroupNamingScreen(
        final Screen parent,
        final SharedWaypointGroup existingGroup,
        final Consumer<String> onSuccess
    ) {
        super(Texts.translatable(
            existingGroup == null
                ? "confluxmap.screen.shared_group.create"
                : "confluxmap.screen.shared_group.rename"
        ));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.existingGroup = existingGroup;
        this.onSuccess = Objects.requireNonNull(onSuccess, "onSuccess");
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        final int centerX = width / 2;
        nameField = new TextFieldWidget(
            this.textRenderer,
            centerX - FIELD_WIDTH / 2,
            Math.max(58, height / 2 - 28),
            FIELD_WIDTH,
            FIELD_HEIGHT,
            Text.of("")
        );
        nameField.setMaxLength(64);
        nameField.setText(existingGroup == null ? "" : existingGroup.name());
        addDrawableChild(nameField);
        setInitialFocus(nameField);

        final int buttonY = Widgets.y(nameField) + 30;
        doneButton = addDrawableChild(Widgets.button(
            centerX - 104,
            buttonY,
            100,
            FIELD_HEIGHT,
            Texts.translatable("confluxmap.screen.waypoint.done"),
            button -> submit()
        ));
        addDrawableChild(Widgets.button(
            centerX + 4,
            buttonY,
            100,
            FIELD_HEIGHT,
            Texts.translatable("confluxmap.screen.waypoint.cancel"),
            button -> onClose()
        ));
        setEnterAction(() -> doneButton != null && doneButton.active, this::submit);
        refreshDoneButton();
    }

    @Override
    public void tick() {
        Widgets.tick(nameField);
        refreshDoneButton();
    }

    @Override
    public void onClose() {
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
    }

    private void refreshDoneButton() {
        if (doneButton != null && nameField != null) {
            doneButton.active = !nameField.getText().trim().isEmpty() && permissionErrorKey() == null;
        }
    }

    private String permissionErrorKey() {
        final SharedWaypointClient sharedWaypoints = ConfluxMapClient.get().sharedWaypoints();
        if (!sharedWaypoints.supportsGroupMutations()) {
            return "confluxmap.shared_waypoints.group_mutations_unsupported";
        }
        return existingGroup == null
            ? sharedWaypoints.createDisabledReasonKey()
            : sharedWaypoints.groupManageDisabledReasonKey(existingGroup);
    }

    private void submit() {
        errorKey = null;
        final String requestedName = nameField.getText().trim();
        if (requestedName.isEmpty()) {
            return;
        }
        errorKey = permissionErrorKey();
        if (errorKey != null) {
            return;
        }
        final SharedWaypointClient sharedWaypoints = ConfluxMapClient.get().sharedWaypoints();
        final boolean sent = existingGroup == null
            ? sharedWaypoints.createGroup(requestedName)
            : sharedWaypoints.renameGroup(existingGroup, requestedName);
        if (!sent) {
            errorKey = "confluxmap.screen.shared_group.error.unavailable";
            return;
        }
        onSuccess.accept(requestedName);
        onClose();
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        drawCentered(draw, getTitle().getString(), 24, 0xFFFFFFFF);
        drawCentered(
            draw,
            Texts.translatable("confluxmap.screen.shared_group.name").getString(),
            Widgets.y(nameField) - 12,
            0xFFCCCCCC
        );
        final String permissionError = permissionErrorKey();
        if (permissionError != null) {
            drawCentered(
                draw,
                Texts.translatable(permissionError).getString(),
                Widgets.y(nameField) + 56,
                0xFFFF7777
            );
        } else if (errorKey != null) {
            drawCentered(
                draw,
                Texts.translatable(errorKey).getString(),
                Widgets.y(nameField) + 56,
                0xFFFF7777
            );
        }
    }

    private void drawCentered(final GuiDraw draw, final String value, final int y, final int color) {
        final String text = this.textRenderer.trimToWidth(value, Math.max(40, width - 32));
        draw.drawTextWithShadow(this.textRenderer, text, width / 2f - this.textRenderer.getWidth(text) / 2f, y, color);
    }
}

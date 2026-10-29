package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.shared.SharedWaypointGroup;
import cn.net.rms.confluxmap.mc.net.shared.SharedWaypointClient;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.Objects;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * Confirmation boundary for deleting one group; members survive as ungrouped and one
 * broadcast delta clears every subscribed client's group references.
 */
final class SharedWaypointGroupDeleteConfirmScreen extends ConfluxScreen {
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int MUTED_TEXT_COLOR = 0xFFB8B8B8;
    private static final int WARNING_TEXT_COLOR = 0xFFFFAA55;
    private static final int ERROR_TEXT_COLOR = 0xFFFF7777;

    private final Screen parent;
    private final SharedWaypointGroup group;
    private final Runnable onSuccess;
    private int memberCount;
    private ButtonWidget confirmButton;
    private String errorKey;

    SharedWaypointGroupDeleteConfirmScreen(
        final Screen parent,
        final SharedWaypointGroup group,
        final int memberCount,
        final Runnable onSuccess
    ) {
        super(Texts.translatable("confluxmap.screen.shared_group.delete"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.group = Objects.requireNonNull(group, "group");
        if (memberCount < 0) {
            throw new IllegalArgumentException("memberCount must not be negative");
        }
        this.memberCount = memberCount;
        this.onSuccess = Objects.requireNonNull(onSuccess, "onSuccess");
    }

    @Override
    protected void init() {
        final int centerX = width / 2;
        confirmButton = addDrawableChild(Widgets.button(
            centerX - 104,
            height - 32,
            100,
            20,
            Texts.translatable("confluxmap.screen.shared_group.delete.confirm"),
            button -> confirmDelete()
        ));
        addDrawableChild(Widgets.button(
            centerX + 4,
            height - 32,
            100,
            20,
            Texts.translatable("confluxmap.screen.waypoint.cancel"),
            button -> onClose()
        ));
        refreshAvailability();
    }

    @Override
    public void tick() {
        super.tick();
        refreshAvailability();
    }

    @Override
    public void onClose() {
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void refreshAvailability() {
        final String availabilityError = availabilityErrorKey();
        if (confirmButton != null) {
            confirmButton.active = availabilityError == null;
        }
        errorKey = availabilityError;
    }

    private String availabilityErrorKey() {
        final SharedWaypointClient sharedWaypoints = ConfluxMapClient.get().sharedWaypoints();
        if (!sharedWaypoints.supportsGroupMutations()) {
            return "confluxmap.shared_waypoints.group_mutations_unsupported";
        }
        final SharedWaypointGroup current = sharedWaypoints.groups().stream()
            .filter(candidate -> candidate.id().equals(group.id()))
            .findFirst()
            .orElse(null);
        if (current == null) {
            return "confluxmap.screen.shared_group.error.not_found";
        }
        final String reasonKey = sharedWaypoints.groupManageDisabledReasonKey(current);
        if (reasonKey != null) {
            return reasonKey;
        }
        memberCount = (int) sharedWaypoints.list().stream()
            .filter(waypoint -> group.id().equals(waypoint.groupId()))
            .count();
        return null;
    }

    private void confirmDelete() {
        errorKey = availabilityErrorKey();
        if (errorKey != null) {
            return;
        }
        final SharedWaypointClient sharedWaypoints = ConfluxMapClient.get().sharedWaypoints();
        if (!sharedWaypoints.deleteGroup(group)) {
            errorKey = "confluxmap.screen.shared_group.error.unavailable";
            return;
        }
        onSuccess.run();
        onClose();
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        drawCentered(draw, getTitle().getString(), 24, TEXT_COLOR);
        drawCentered(draw, Texts.translatable(
            "confluxmap.screen.shared_group.delete.name", group.name()
        ).getString(), 54, TEXT_COLOR);
        drawCentered(draw, Texts.translatable(
            "confluxmap.screen.shared_group.delete.members", memberCount
        ).getString(), 74, MUTED_TEXT_COLOR);
        drawCentered(
            draw,
            Texts.translatable("confluxmap.screen.shared_group.delete.warning").getString(),
            94,
            WARNING_TEXT_COLOR
        );
        if (errorKey != null) {
            drawCentered(draw, Texts.translatable(errorKey).getString(), height - 50, ERROR_TEXT_COLOR);
        }
    }

    private void drawCentered(final GuiDraw draw, final String value, final int y, final int color) {
        final String text = this.textRenderer.trimToWidth(value, Math.max(40, width - 32));
        draw.drawTextWithShadow(this.textRenderer, text, width / 2f - this.textRenderer.getWidth(text) / 2f, y, color);
    }
}

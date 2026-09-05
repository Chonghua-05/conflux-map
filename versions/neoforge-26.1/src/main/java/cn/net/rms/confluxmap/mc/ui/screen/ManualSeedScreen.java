package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.MinecraftVersion;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.config.ManualSeedConfig;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.predict.SeedInput;
import cn.net.rms.confluxmap.mc.predict.ManualSeedService;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.FormattedText;

/** Focused seed-and-version form for the current client-only multiplayer world. */
public final class ManualSeedScreen extends ConfluxScreen {
    private static final int FIELD_HEIGHT = 20;
    private static final int MUTED_TEXT = 0xFFBBBBBB;
    private static final int ERROR_TEXT = 0xFFFF7777;

    private final Screen parent;
    private final ManualSeedService manualSeeds;
    private final WorldIdentity boundWorld;
    private final List<McVersions.Selection> versions = McVersions.selections();
    private EditBox seedField;
    private Button versionButton;
    private Button applyButton;
    private Button clearButton;
    private int versionIndex;
    private String errorKey;

    public ManualSeedScreen(final Screen parent) {
        super(Texts.translatable("confluxmap.screen.manual_seed.title"));
        this.parent = parent;
        final ConfluxMapClient app = ConfluxMapClient.get();
        this.manualSeeds = app.manualSeedService();
        this.boundWorld = app.sessionGuard().current().world();
        final String configuredVersion = manualSeeds.current()
            .map(ManualSeedConfig.Entry::worldgenVersion)
            .orElse(MinecraftVersion.current());
        this.versionIndex = McVersions.selectionIndex(configuredVersion);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        final int formWidth = Math.min(280, width - 24);
        final int left = width / 2 - formWidth / 2;
        seedField = new EditBox(
            this.font,
            left,
            76,
            formWidth,
            FIELD_HEIGHT,
            Texts.translatable("confluxmap.screen.manual_seed.seed")
        );
        seedField.setMaxLength(128);
        seedField.setValue(manualSeeds.current().map(ManualSeedConfig.Entry::seedInput).orElse(""));
        addRenderableWidget(seedField);
        setInitialFocus(seedField);

        final int arrowWidth = 24;
        addRenderableWidget(Widgets.button(
            left, 116, arrowWidth, FIELD_HEIGHT, Texts.literal("<"), ignored -> selectVersion(-1)
        ));
        versionButton = addRenderableWidget(Widgets.button(
            left + arrowWidth + 4,
            116,
            formWidth - arrowWidth * 2 - 8,
            FIELD_HEIGHT,
            versionLabel(),
            ignored -> selectVersion(1)
        ));
        addRenderableWidget(Widgets.button(
            left + formWidth - arrowWidth,
            116,
            arrowWidth,
            FIELD_HEIGHT,
            Texts.literal(">"),
            ignored -> selectVersion(1)
        ));

        final int buttonGap = 4;
        final int buttonWidth = (formWidth - buttonGap * 2) / 3;
        final int buttonY = height - 32;
        applyButton = addRenderableWidget(Widgets.button(
            left,
            buttonY,
            buttonWidth,
            FIELD_HEIGHT,
            Texts.translatable("confluxmap.screen.manual_seed.apply"),
            ignored -> apply()
        ));
        clearButton = addRenderableWidget(Widgets.button(
            left + buttonWidth + buttonGap,
            buttonY,
            buttonWidth,
            FIELD_HEIGHT,
            Texts.translatable("confluxmap.screen.manual_seed.clear"),
            ignored -> clear()
        ));
        addRenderableWidget(Widgets.button(
            left + (buttonWidth + buttonGap) * 2,
            buttonY,
            buttonWidth,
            FIELD_HEIGHT,
            Texts.translatable("confluxmap.screen.waypoint.cancel"),
            ignored -> onClose()
        ));
        setEnterAction(() -> applyButton != null && applyButton.active, this::apply);
        refreshButtons();
    }

    @Override
    public void tick() {
        Widgets.tick(seedField);
        refreshButtons();
    }

    private void selectVersion(final int delta) {
        versionIndex = Math.floorMod(versionIndex + delta, versions.size());
        versionButton.setMessage(versionLabel());
    }

    private net.minecraft.network.chat.Component versionLabel() {
        return Texts.translatable(
            "confluxmap.screen.manual_seed.version_value", versions.get(versionIndex).label()
        );
    }

    private void refreshButtons() {
        final boolean available = manualSeeds.available()
            && ConfluxMapClient.get().sessionGuard().current().world().equals(boundWorld);
        applyButton.active = available && SeedInput.parse(seedField.getValue()).isPresent();
        clearButton.active = available && manualSeeds.current().isPresent();
        if (!available) {
            errorKey = "confluxmap.screen.manual_seed.session_changed";
        }
    }

    private void apply() {
        if (!applyButton.active) {
            return;
        }
        final McVersions.Selection selected = versions.get(versionIndex);
        if (!manualSeeds.apply(boundWorld, seedField.getValue(), selected.worldgenVersion())) {
            errorKey = "confluxmap.screen.manual_seed.session_changed";
            refreshButtons();
            return;
        }
        onClose();
    }

    private void clear() {
        if (!clearButton.active) {
            return;
        }
        if (!manualSeeds.clear(boundWorld)) {
            errorKey = "confluxmap.screen.manual_seed.session_changed";
            refreshButtons();
            return;
        }
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
        drawCentered(draw, getTitle().getString(), 20, 0xFFFFFFFF);
        drawWrappedCentered(
            draw,
            Texts.translatable("confluxmap.screen.manual_seed.description").getString(),
            38,
            MUTED_TEXT
        );
        drawCentered(
            draw,
            Texts.translatable("confluxmap.screen.manual_seed.seed").getString(),
            63,
            MUTED_TEXT
        );
        drawCentered(
            draw,
            Texts.translatable("confluxmap.screen.manual_seed.version").getString(),
            103,
            MUTED_TEXT
        );
        drawWrappedCentered(
            draw,
            Texts.translatable("confluxmap.screen.manual_seed.sync_notice").getString(),
            148,
            MUTED_TEXT
        );
        if (errorKey != null) {
            drawWrappedCentered(draw, Texts.translatable(errorKey).getString(), 180, ERROR_TEXT);
        }
    }

    private void drawWrappedCentered(
        final GuiDraw draw,
        final String value,
        final int startY,
        final int color
    ) {
        int y = startY;
        for (final FormattedCharSequence line : this.font.split(
            FormattedText.of(value), Math.max(40, Math.min(280, width - 24))
        )) {
            draw.drawTextWithShadow(
                this.font,
                line,
                width / 2f - this.font.width(line) / 2f,
                y,
                color
            );
            y += this.font.lineHeight + 1;
        }
    }

    private void drawCentered(final GuiDraw draw, final String value, final int y, final int color) {
        draw.drawTextWithShadow(
            this.font,
            value,
            width / 2f - this.font.width(value) / 2f,
            y,
            color
        );
    }
}

package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.waypoint.WaypointStore;
import cn.net.rms.confluxmap.core.waypoint.migrate.ImportedWaypoint;
import cn.net.rms.confluxmap.core.waypoint.migrate.MigrationSource;
import cn.net.rms.confluxmap.core.waypoint.migrate.MigrationSourceScanner;
import cn.net.rms.confluxmap.core.waypoint.migrate.WaypointImporter;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.compat.Texts;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.multiplayer.ServerData;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

/**
 * One-click migration of Xaero's Minimap / VoxelMap waypoints for the current
 * world. Sources are discovered synchronously when the screen opens (the
 * files are small text files, mirroring the load-at-a-pause-point pattern of
 * {@link cn.net.rms.confluxmap.core.waypoint.WaypointService}); the user can
 * exclude a source before importing. Import applies once per screen visit.
 */
final class WaypointImportScreen extends ConfluxScreen {
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int MUTED_TEXT_COLOR = 0xFFB8B8B8;
    private static final int SUCCESS_TEXT_COLOR = 0xFF7FFF7F;
    private static final int ERROR_TEXT_COLOR = 0xFFFF7777;
    private static final int ROW_HEIGHT = 24;
    private static final int LIST_TOP = 58;

    private final Screen parent;
    private final WaypointStore boundStore;
    private final List<MigrationSource> sources;
    private final Set<Integer> excludedSources = new LinkedHashSet<>();

    private Button importButton;
    private WaypointImporter.Result result;
    private String errorKey;
    private int scrollOffset;

    WaypointImportScreen(final Screen parent, final WaypointStore boundStore) {
        super(Texts.translatable("confluxmap.screen.waypoint_import.title"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.boundStore = Objects.requireNonNull(boundStore, "boundStore");
        this.sources = scanSources();
    }

    private static List<MigrationSource> scanSources() {
        final MigrationSourceScanner.Context context = currentContext(Minecraft.getInstance());
        if (context == null) {
            return List.of();
        }
        final Path gameDir = FMLPaths.GAMEDIR.get();
        return MigrationSourceScanner.scan(gameDir, context, ConfluxMapMod.LOGGER);
    }

    /** Mirrors {@link cn.net.rms.confluxmap.mc.world.WorldSessionTracker}'s identity observation. */
    private static MigrationSourceScanner.Context currentContext(final Minecraft client) {
        if (client.isLocalServer() && client.getSingleplayerServer() != null) {
            final Path saveRoot = client.getSingleplayerServer().getWorldPath(LevelResource.ROOT).normalize();
            final Path saveName = saveRoot.getFileName();
            return saveName == null ? null : MigrationSourceScanner.Context.singleplayer(saveName.toString());
        }
        final ServerData server = client.getCurrentServer();
        return server == null ? null : MigrationSourceScanner.Context.multiplayer(server.ip);
    }

    @Override
    protected void init() {
        buildWidgets();
    }

    private void rebuild() {
        clearWidgets();
        buildWidgets();
    }

    private void buildWidgets() {
        clearEnterAction();
        final int centerX = width / 2;
        final int rowWidth = rowWidth();
        final int rowLeft = centerX - rowWidth / 2;
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, sources.size() - visibleRows())));
        final int end = Math.min(sources.size(), scrollOffset + visibleRows());
        for (int i = scrollOffset; i < end; i++) {
            final int index = i;
            final Button toggle = addRenderableWidget(Widgets.button(
                rowLeft,
                LIST_TOP + (i - scrollOffset) * ROW_HEIGHT,
                20,
                20,
                Component.nullToEmpty(excludedSources.contains(index) ? "" : "✓"),
                button -> toggleSource(index)
            ));
            toggle.active = result == null;
        }

        importButton = addRenderableWidget(Widgets.button(
            centerX - 104,
            height - 32,
            100,
            20,
            Texts.translatable("confluxmap.screen.waypoint_import.import", selectedWaypointCount()),
            button -> runImport()
        ));
        importButton.active = result == null && selectedWaypointCount() > 0 && availabilityErrorKey() == null;
        if (result == null) {
            setEnterAction(() -> importButton != null && importButton.active, this::runImport);
        }
        addRenderableWidget(Widgets.button(
            centerX + 4,
            height - 32,
            100,
            20,
            Texts.translatable(
                result == null ? "confluxmap.screen.waypoint.cancel" : "confluxmap.screen.waypoint.done"
            ),
            button -> onClose()
        ));
    }

    @Override
    public void tick() {
        super.tick();
        if (result != null) {
            return;
        }
        final String availabilityError = availabilityErrorKey();
        if (importButton != null) {
            importButton.active = availabilityError == null && selectedWaypointCount() > 0;
        }
        errorKey = availabilityError;
    }

    @Override
    public void onClose() {
        MinecraftAccess.setScreen(Minecraft.getInstance(), parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void toggleSource(final int index) {
        if (result != null) {
            return;
        }
        if (!excludedSources.remove(index)) {
            excludedSources.add(index);
        }
        rebuild();
    }

    private int selectedWaypointCount() {
        int count = 0;
        for (int i = 0; i < sources.size(); i++) {
            if (!excludedSources.contains(i)) {
                count += sources.get(i).waypoints().size();
            }
        }
        return count;
    }

    private int visibleRows() {
        return Math.max(1, (height - LIST_TOP - 42) / ROW_HEIGHT);
    }

    @Override
    public boolean mouseScrolled(
        final double mouseX,
        final double mouseY,
        final double horizontalAmount,
        final double amount
    ) {
        final int left = width / 2 - rowWidth() / 2;
        final boolean overList = mouseX >= left && mouseX <= left + rowWidth() + 6
            && mouseY >= LIST_TOP && mouseY <= LIST_TOP + visibleRows() * ROW_HEIGHT;
        if (amount != 0 && overList && sources.size() > visibleRows()) {
            final int max = sources.size() - visibleRows();
            final int next = Math.max(0, Math.min(max, scrollOffset - (int) Math.signum(amount)));
            if (next != scrollOffset) {
                scrollOffset = next;
                rebuild();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, amount);
    }

    private String availabilityErrorKey() {
        final WaypointStore currentStore = ConfluxMapClient.get().waypointService().current();
        if (currentStore == null || currentStore != boundStore) {
            return "confluxmap.screen.waypoint_import.error.unavailable";
        }
        if (!boundStore.persistenceWritable()) {
            return "confluxmap.screen.waypoint_set.error.read_only";
        }
        return null;
    }

    private void runImport() {
        if (result != null) {
            return;
        }
        errorKey = availabilityErrorKey();
        if (errorKey != null) {
            rebuild();
            return;
        }
        final List<ImportedWaypoint> batch = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            if (!excludedSources.contains(i)) {
                batch.addAll(sources.get(i).waypoints());
            }
        }
        result = WaypointImporter.importInto(boundStore, batch);
        rebuild();
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        drawCentered(draw, getTitle().getString(), 24, TEXT_COLOR);
        if (sources.isEmpty()) {
            drawCentered(
                draw,
                Texts.translatable("confluxmap.screen.waypoint_import.empty").getString(),
                LIST_TOP + 6,
                MUTED_TEXT_COLOR
            );
        } else {
            drawCentered(
                draw,
                Texts.translatable("confluxmap.screen.waypoint_import.hint").getString(),
                44,
                MUTED_TEXT_COLOR
            );
            final int rowLeft = width / 2 - rowWidth() / 2;
            final int end = Math.min(sources.size(), scrollOffset + visibleRows());
            for (int i = scrollOffset; i < end; i++) {
                final MigrationSource source = sources.get(i);
                final String label = Texts.translatable(
                    "confluxmap.screen.waypoint_import.source",
                    Texts.translatable(modKey(source.mod())).getString(),
                    source.displayName(),
                    source.waypoints().size()
                ).getString();
                final String fitted = this.font.plainSubstrByWidth(label, rowWidth() - 26);
                draw.drawTextWithShadow(
                    this.font, fitted, rowLeft + 26,
                    LIST_TOP + (i - scrollOffset) * ROW_HEIGHT + 6,
                    excludedSources.contains(i) ? MUTED_TEXT_COLOR : TEXT_COLOR
                );
            }
            drawListScrollbar(
                draw,
                rowLeft + rowWidth() + 3,
                LIST_TOP,
                visibleRows() * ROW_HEIGHT - 4,
                sources.size(),
                visibleRows(),
                scrollOffset
            );
        }
        if (result != null) {
            drawCentered(draw, Texts.translatable(
                "confluxmap.screen.waypoint_import.result", result.imported(), result.duplicates()
            ).getString(), height - 50, SUCCESS_TEXT_COLOR);
        } else if (errorKey != null) {
            drawCentered(draw, Texts.translatable(errorKey).getString(), height - 50, ERROR_TEXT_COLOR);
        }
    }

    private int rowWidth() {
        return Math.min(360, width - 32);
    }

    private static String modKey(final MigrationSource.Mod mod) {
        return switch (mod) {
            case XAERO -> "confluxmap.screen.waypoint_import.mod.xaero";
            case VOXELMAP -> "confluxmap.screen.waypoint_import.mod.voxelmap";
        };
    }

    private void drawCentered(final GuiDraw draw, final String value, final int y, final int color) {
        final String text = this.font.plainSubstrByWidth(value, Math.max(40, width - 32));
        draw.drawTextWithShadow(this.font, text, width / 2f - this.font.width(text) / 2f, y, color);
    }
}

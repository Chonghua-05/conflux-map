package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.xaeroimport.XaeroImportService;
import cn.net.rms.confluxmap.core.xaeroimport.XaeroImportService.DimensionPlan;
import cn.net.rms.confluxmap.core.xaeroimport.XaeroImportService.Progress;
import cn.net.rms.confluxmap.core.xaeroimport.XaeroImportService.ScanResult;
import cn.net.rms.confluxmap.core.xaeroimport.XaeroImportService.State;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Objects;
import net.neoforged.fml.ModList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Imports terrain map data from a Xaero's Level Map installation for the
 * current world. Discovery runs synchronously when the screen opens (a
 * directory listing, mirroring {@link WaypointImportScreen}); the import
 * itself runs on the import worker with progress polled every tick.
 */
final class XaeroImportScreen extends ConfluxScreen {
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int MUTED_TEXT_COLOR = 0xFFB8B8B8;
    private static final int SUCCESS_TEXT_COLOR = 0xFF7FFF7F;
    private static final int ERROR_TEXT_COLOR = 0xFFFF7777;
    private static final int ROW_HEIGHT = 24;
    private static final int LIST_TOP = 58;

    private final Screen parent;
    private final XaeroImportService service;
    private final ScanResult scan;
    private final String errorKey;

    private Button cancelButton;

    XaeroImportScreen(final Screen parent) {
        super(Texts.translatable("confluxmap.screen.map_import.title"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.service = ConfluxMapClient.get().xaeroImportService();
        this.scan = scan();
        this.errorKey = scan == null ? "confluxmap.screen.map_import.error.not_found" : null;
    }

    private ScanResult scan() {
        final Minecraft client = Minecraft.getInstance();
        final Path gameDir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get();
        if (client.isLocalServer() && client.getSingleplayerServer() != null) {
            final Path saveRoot = client.getSingleplayerServer().getWorldPath(LevelResource.ROOT).normalize();
            final Path saveName = saveRoot.getFileName();
            return saveName == null
                ? null
                : service.scan(gameDir, saveName.toString(), true);
        }
        final ServerData server = client.getCurrentServer();
        if (server != null) {
            return service.scan(gameDir, server.ip, false);
        }
        // Quick-play joins connect without building a server-list entry; the live
        // connection still knows the ip it dialed. getHostString can return a
        // reverse-DNS hostname, so prefer the literal ip the socket holds.
        if (client.getConnection() != null && client.getConnection().getConnection() != null
            && client.getConnection().getConnection().getRemoteAddress() instanceof final InetSocketAddress socket) {
            final String host = socket.getAddress() != null
                ? socket.getAddress().getHostAddress()
                : socket.getHostString();
            return service.scan(gameDir, host + ":" + socket.getPort(), false);
        }
        return null;
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
        final Progress progress = service.progress();
        final boolean running = progress.state() == State.RUNNING;
        final int centerX = width / 2;
        if (scan != null) {
            final int rowLeft = centerX - rowWidth() / 2;
            final int end = Math.min(scan.dimensions().size(), visibleRows());
            for (int i = 0; i < end; i++) {
                final DimensionPlan plan = scan.dimensions().get(i);
                final Button importButton = addRenderableWidget(Widgets.button(
                    rowLeft + rowWidth() - 80,
                    LIST_TOP + i * ROW_HEIGHT,
                    80,
                    20,
                    Texts.translatable("confluxmap.screen.map_import.import_row"),
                    button -> service.start(plan)
                ));
                importButton.active = !running;
            }
        }
        if (running) {
            cancelButton = addRenderableWidget(Widgets.button(
                centerX - 60,
                height - 32,
                120,
                20,
                Texts.translatable("confluxmap.screen.waypoint.cancel"),
                button -> service.cancel()
            ));
        } else {
            cancelButton = null;
            addRenderableWidget(Widgets.button(
                centerX - 60,
                height - 32,
                120,
                20,
                Texts.translatable("confluxmap.screen.waypoint.done"),
                button -> onClose()
            ));
        }
    }

    @Override
    public void tick() {
        super.tick();
        final Progress progress = service.progress();
        final boolean running = progress.state() == State.RUNNING;
        if (running != (cancelButton != null)) {
            rebuild();
        }
    }

    @Override
    public void onClose() {
        requestClose(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int visibleRows() {
        return Math.max(0, (height - LIST_TOP - 46) / ROW_HEIGHT);
    }

    private int rowWidth() {
        return Math.min(360, width - 32);
    }

    private String dimensionLabel(final DimensionPlan plan) {
        return Texts.translatable(
            "confluxmap.screen.map_import.dimension",
            plan.dimension().namespace(),
            plan.dimension().path()
        ).getString();
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        draw.extractBackground(this, mouseX, mouseY, tickDelta);
        drawCentered(draw, getTitle().getString(), 24, TEXT_COLOR);
        if (errorKey != null) {
            drawCentered(
                draw,
                Texts.translatable(errorKey).getString(),
                LIST_TOP + 6,
                MUTED_TEXT_COLOR
            );
        } else {
            drawCentered(
                draw,
                Texts.translatable(
                    "confluxmap.screen.map_import.hint", scan.container()
                ).getString(),
                44,
                MUTED_TEXT_COLOR
            );
            final int rowLeft = width / 2 - rowWidth() / 2;
            final int end = Math.min(scan.dimensions().size(), visibleRows());
            for (int i = 0; i < end; i++) {
                final DimensionPlan plan = scan.dimensions().get(i);
                final String label = this.font.plainSubstrByWidth(
                    dimensionLabel(plan) + "  ·  "
                        + Texts.translatable("confluxmap.screen.map_import.regions", plan.regionCount()).getString(),
                    rowWidth() - 92
                );
                draw.drawTextWithShadow(
                    this.font, label, rowLeft + 8,
                    LIST_TOP + i * ROW_HEIGHT + 6, TEXT_COLOR
                );
            }
        }
        final Progress progress = service.progress();
        final String statusText = statusText(progress);
        if (statusText != null) {
            drawCentered(
                draw,
                statusText,
                height - 50,
                progress.state() == State.FAILED ? ERROR_TEXT_COLOR : SUCCESS_TEXT_COLOR
            );
        }
    }

    private String statusText(final Progress progress) {
        return switch (progress.state()) {
            case IDLE -> null;
            case RUNNING -> Texts.translatable(
                "confluxmap.screen.map_import.progress",
                progress.dimension().toString(),
                progress.processedRegions(),
                progress.totalRegions(),
                progress.importedChunks()
            ).getString();
            case DONE -> Texts.translatable(
                "confluxmap.screen.map_import.result",
                progress.importedChunks()
            ).getString();
            case CANCELLED -> Texts.translatable(
                "confluxmap.screen.map_import.cancelled",
                progress.importedChunks()
            ).getString();
            case FAILED -> Texts.translatable(
                "confluxmap.screen.map_import.failed",
                progress.message() == null ? "" : progress.message()
            ).getString();
        };
    }

    private void drawCentered(final GuiDraw draw, final String value, final int y, final int color) {
        final String text = this.font.plainSubstrByWidth(value, Math.max(40, width - 32));
        draw.drawTextWithShadow(
            this.font, text, width / 2f - this.font.width(text) / 2f, y, color
        );
    }
}
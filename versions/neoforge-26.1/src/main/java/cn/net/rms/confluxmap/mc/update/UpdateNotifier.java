package cn.net.rms.confluxmap.mc.update;

import cn.net.rms.confluxmap.core.update.UpdateCheckService;
import cn.net.rms.confluxmap.compat.Texts;
import java.util.Optional;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/**
 * Chat-side surface of the update check: once per game launch, as soon as a
 * completed check knows a newer release and the player is in a world, posts one
 * chat line with a clickable download link. The fullscreen-map badge reads the
 * same {@link UpdateCheckService} state, so the two surfaces never disagree.
 */
public final class UpdateNotifier {
    private final Minecraft client;
    private final UpdateCheckService updates;
    private boolean chatShown;

    public UpdateNotifier(final Minecraft client, final UpdateCheckService updates) {
        this.client = client;
        this.updates = updates;
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(c -> tick());
    }

    private void tick() {
        if (chatShown || client.player == null) {
            return;
        }
        final Optional<UpdateCheckService.UpdateInfo> info = updates.available();
        if (info.isEmpty()) {
            return;
        }
        chatShown = true;
        //#if MC>=260100
        client.player.sendSystemMessage(buildMessage(info.get()));
        //#else
        //$$ client.player.sendMessage(buildMessage(info.get()), false);
        //#endif
    }

    private static Component buildMessage(final UpdateCheckService.UpdateInfo info) {
        final MutableComponent link = Texts.translatable("confluxmap.update.chat.link")
            .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
            .withStyle(style -> style
                .withClickEvent(Texts.openUrl(info.releaseUrl()))
                .withHoverEvent(Texts.showText(Texts.literal(info.releaseUrl()))));
        return Texts.translatable("confluxmap.update.chat", info.latestVersion(), info.currentVersion())
            .withStyle(ChatFormatting.YELLOW)
            .append(Texts.literal(" "))
            .append(link);
    }
}

package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.core.survey.SurveyReminderClickPayload;
import cn.net.rms.confluxmap.core.waypoint.chat.WaypointChatClickPayload;
import cn.net.rms.confluxmap.core.waypoint.chat.WaypointChatCodec;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.mc.ui.screen.WaypointEditScreen;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class ScreenMixin {
    // 26.1 split the static handler in two: client-local actions (clipboard, url, file,
    // suggest) stay in defaultHandleClickEvent while command/dialog/custom moved to the Game
    // variant. The reserved payload arrives as COPY_TO_CLIPBOARD, so this is the one to take.
    // The descriptor is spelled in official names because an unobfuscated build has no refMap
    // to translate it, and annotation string constants are not rewritten by the build.
    @Inject(
        method = "defaultHandleClickEvent(Lnet/minecraft/network/chat/ClickEvent;"
            + "Lnet/minecraft/client/Minecraft;"
            + "Lnet/minecraft/client/gui/screens/Screen;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void confluxmap$handleReservedClick(
        final ClickEvent clickEvent,
        final Minecraft client,
        final Screen screen,
        final CallbackInfo ci
    ) {
        if (confluxmap$handleReservedClick(clickEvent, client, screen)) {
            ci.cancel();
        }
    }

    /** Handles Conflux's reserved clipboard payload and reports whether vanilla must be skipped. */
    private static boolean confluxmap$handleReservedClick(
        final ClickEvent clickEvent,
        final Minecraft client,
        final Screen parent
    ) {
        final String clickValue = clickEvent == null ? null : Texts.clickValue(clickEvent);
        if (SurveyReminderClickPayload.isDismiss(clickValue)) {
            if (clickEvent.action() == ClickEvent.Action.COPY_TO_CLIPBOARD) {
                final ConfluxMapClient confluxMap = ConfluxMapClient.get();
                if (confluxMap != null) {
                    confluxMap.dismissSurveyReminder();
                }
            }
            return true;
        }
        if (clickValue == null || !WaypointChatClickPayload.hasPrivatePrefix(clickValue)) {
            return false;
        }

        // Reserved payloads never reach vanilla's clipboard or command click handling.
        if (clickEvent.action() != ClickEvent.Action.COPY_TO_CLIPBOARD) {
            return true;
        }

        final Optional<WaypointChatClickPayload.Decoded> decoded =
            WaypointChatClickPayload.decode(clickValue);
        if (!decoded.isPresent()) {
            return true;
        }
        final Optional<WaypointChatCodec.Candidate> candidate = WaypointChatCodec.parse(
            decoded.get().message(), decoded.get().receivedDimension()
        );
        if (!candidate.isPresent()) {
            return true;
        }

        if (client.level == null) {
            return true;
        }
        final WaypointChatCodec.Candidate waypoint = candidate.get();
        MinecraftAccess.setScreen(client, WaypointEditScreen.forCreate(
            parent,
            waypoint.dimensionId(),
            waypoint.name(),
            waypoint.x(),
            waypoint.y(),
            waypoint.z()
        ));
        return true;
    }
}

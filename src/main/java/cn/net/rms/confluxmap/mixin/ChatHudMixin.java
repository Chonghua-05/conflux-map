package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.Dimensions;
import cn.net.rms.confluxmap.mc.chat.WaypointChatMessageRewriter;
import cn.net.rms.confluxmap.mc.world.ClientWorldIdentityHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public abstract class ChatHudMixin {
    /** Rewriting runs for every chat message, so the first fault disables it for the session. */
    @Unique
    private static boolean confluxmap$rewriteFaulted;

    @Inject(
        method = "addMessage(Lnet/minecraft/network/chat/Component;"
            + "Lnet/minecraft/network/chat/MessageSignature;"
            + "Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;"
            + "Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void confluxmap$hideVelocityProbeResponse(
        final Component message,
        final MessageSignature signature,
        final GuiMessageSource source,
        final GuiMessageTag tag,
        final CallbackInfo callback
    ) {
        if (ClientWorldIdentityHandler.chatMessage(message)) {
            callback.cancel();
        }
    }

    @ModifyVariable(
        // Official names are required in annotation strings for the unobfuscated build.
        method = "addMessage(Lnet/minecraft/network/chat/Component;"
            + "Lnet/minecraft/network/chat/MessageSignature;"
            + "Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;"
            + "Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private Component confluxmap$rewriteWaypointMessage(final Component original) {
        final Minecraft client = Minecraft.getInstance();
        if (client.level == null || confluxmap$rewriteFaulted) {
            return original;
        }

        try {
            return WaypointChatMessageRewriter.rewrite(
                original,
                Dimensions.of(client.level)
            );
        } catch (final Throwable fault) {
            confluxmap$rewriteFaulted = true;
            ConfluxMapMod.LOGGER.error(
                "waypoint chat rewrite disabled after a fault; messages pass through unrewritten",
                fault
            );
            return original;
        }
    }
}
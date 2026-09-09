package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.mc.chat.WaypointChatMessageRewriter;
import cn.net.rms.confluxmap.mc.world.ClientWorldIdentityHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public abstract class ChatHudMixin {
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
        if (client.level == null) {
            return original;
        }

        final Identifier dimensionIdentifier = client.level.dimension().identifier();
        return WaypointChatMessageRewriter.rewrite(
            original,
            DimensionId.of(dimensionIdentifier.getNamespace(), dimensionIdentifier.getPath())
        );
    }
}

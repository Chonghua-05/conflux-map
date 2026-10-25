package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.Dimensions;
import cn.net.rms.confluxmap.mc.chat.WaypointChatMessageRewriter;
import cn.net.rms.confluxmap.mc.world.ClientWorldIdentityHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
//#if MC>=260100
//$$ import net.minecraft.client.multiplayer.chat.GuiMessageSource;
//$$ import net.minecraft.client.multiplayer.chat.GuiMessageTag;
//$$ import net.minecraft.network.chat.MessageSignature;
//#elseif MC>=12100
//$$ import net.minecraft.client.gui.hud.MessageIndicator;
//$$ import net.minecraft.network.message.MessageSignatureData;
//#endif
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatHud.class)
public abstract class ChatHudMixin {
    /** Rewriting runs for every chat message, so the first fault disables it for the session. */
    @Unique
    private static boolean confluxmap$rewriteFaulted;

    @Inject(
        //#if MC>=260100
        //$$ method = "addMessage(Lnet/minecraft/network/chat/Component;"
        //$$     + "Lnet/minecraft/network/chat/MessageSignature;"
        //$$     + "Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;"
        //$$     + "Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
        //#elseif MC>=12100
        //$$ method = "addMessage(Lnet/minecraft/text/Text;"
        //$$     + "Lnet/minecraft/network/message/MessageSignatureData;"
        //$$     + "Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
        //#else
        method = "addMessage(Lnet/minecraft/text/Text;)V",
        //#endif
        at = @At("HEAD"),
        cancellable = true
    )
    private void confluxmap$hideVelocityProbeResponse(
        final Text message,
        //#if MC>=260100
        //$$ final MessageSignature signature,
        //$$ final GuiMessageSource source,
        //$$ final GuiMessageTag tag,
        //#elseif MC>=12100
        //$$ final MessageSignatureData signature,
        //$$ final MessageIndicator indicator,
        //#endif
        final CallbackInfo callback
    ) {
        if (ClientWorldIdentityHandler.chatMessage(message)) {
            callback.cancel();
        }
    }

    @ModifyVariable(
        //#if MC>=260100
        //$$ // Official names are required in annotation strings for the unobfuscated build.
        //$$ method = "addMessage(Lnet/minecraft/network/chat/Component;"
        //$$     + "Lnet/minecraft/network/chat/MessageSignature;"
        //$$     + "Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;"
        //$$     + "Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
        //#elseif MC>=12100
        //$$ method = "addMessage(Lnet/minecraft/text/Text;"
        //$$     + "Lnet/minecraft/network/message/MessageSignatureData;"
        //$$     + "Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
        //#else
        method = "addMessage(Lnet/minecraft/text/Text;)V",
        //#endif
        at = @At("HEAD"),
        argsOnly = true
    )
    private Text confluxmap$rewriteWaypointMessage(final Text original) {
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || confluxmap$rewriteFaulted) {
            return original;
        }

        try {
            return WaypointChatMessageRewriter.rewrite(
                original,
                Dimensions.of(client.world)
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

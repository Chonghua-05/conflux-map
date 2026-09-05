package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.mc.snapshot.ChunkCaptureHandler;
import cn.net.rms.confluxmap.mc.world.ClientWorldIdentityHandler;
import net.minecraft.world.level.block.Block;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
//#if MC>=12100
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
//#else
//$$ import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
//#endif
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "handleLogin", at = @At("TAIL"))
    private void confluxmap$onGameJoin(final ClientboundLoginPacket packet, final CallbackInfo ci) {
        //#if MC>=12100
        ClientWorldIdentityHandler.gameJoin(packet.commonPlayerSpawnInfo().seed());
        //#elseif MC>=11800
        //$$ ClientWorldIdentityHandler.gameJoin(packet.sha256Seed());
        //#else
        //$$ ClientWorldIdentityHandler.gameJoin(packet.getSha256Seed());
        //#endif
    }

    @Inject(method = "handleRespawn", at = @At("TAIL"))
    private void confluxmap$onPlayerRespawn(final ClientboundRespawnPacket packet, final CallbackInfo ci) {
        //#if MC>=12100
        ClientWorldIdentityHandler.respawn(packet.commonPlayerSpawnInfo().seed());
        //#else
        //$$ ClientWorldIdentityHandler.respawn(packet.getSha256Seed());
        //#endif
    }

    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void confluxmap$onBlockUpdate(final ClientboundBlockUpdatePacket packet, final CallbackInfo ci) {
        ChunkCaptureHandler.blockDirty(
            packet.getPos().getX(), packet.getPos().getY(), packet.getPos().getZ(),
            Block.getId(packet.getBlockState())
        );
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
    private void confluxmap$onChunkDeltaUpdate(final ClientboundSectionBlocksUpdatePacket packet, final CallbackInfo ci) {
        packet.runUpdates((pos, state) -> ChunkCaptureHandler.blockDirty(
            pos.getX(), pos.getY(), pos.getZ(), Block.getId(state)
        ));
    }

    //#if MC>=12103
    @Inject(method = "applyLightData", at = @At("TAIL"))
    private void confluxmap$afterLightDataApplied(
        final int chunkX,
        final int chunkZ,
        final ClientboundLightUpdatePacketData lightData,
        final boolean trustEdges,
        final CallbackInfo ci
    ) {
        ChunkCaptureHandler.chunkDirty(chunkX, chunkZ);
    }
    //#elseif MC>=12100
    //$$ @Inject(method = "readLightData", at = @At("TAIL"))
    //$$ private void confluxmap$afterLightDataApplied(
    //$$     final int chunkX,
    //$$     final int chunkZ,
    //$$     final LightData lightData,
    //$$     final CallbackInfo ci
    //$$ ) {
    //$$     ChunkCaptureHandler.chunkDirty(chunkX, chunkZ);
    //$$ }
    //#else
    //$$ @Inject(method = "onLightUpdate", at = @At("TAIL"))
    //$$ private void confluxmap$afterLightDataApplied(final LightUpdateS2CPacket packet, final CallbackInfo ci) {
    //$$     ChunkCaptureHandler.chunkDirty(packet.getChunkX(), packet.getChunkZ());
    //$$ }
    //#endif
}

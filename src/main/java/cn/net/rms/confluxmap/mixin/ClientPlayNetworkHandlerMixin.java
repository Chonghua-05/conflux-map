package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.mc.portal.PortalScanHandler;
import cn.net.rms.confluxmap.mc.snapshot.ChunkCaptureHandler;
import cn.net.rms.confluxmap.mc.world.ClientWorldIdentityHandler;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onGameJoin", at = @At("TAIL"))
    private void confluxmap$onGameJoin(final ClientboundLoginPacket packet, final CallbackInfo ci) {
        ClientWorldIdentityHandler.gameJoin(packet.commonPlayerSpawnInfo().seed());
    }

    @Inject(method = "onPlayerRespawn", at = @At("TAIL"))
    private void confluxmap$onPlayerRespawn(final ClientboundRespawnPacket packet, final CallbackInfo ci) {
        ClientWorldIdentityHandler.respawn(packet.commonPlayerSpawnInfo().seed());
    }

    @Inject(method = "onBlockUpdate", at = @At("TAIL"))
    private void confluxmap$onBlockUpdate(final ClientboundBlockUpdatePacket packet, final CallbackInfo ci) {
        final BlockState state = packet.getBlockState();
        ChunkCaptureHandler.blockDirty(
            packet.getPos().getX(), packet.getPos().getY(), packet.getPos().getZ(),
            Block.getId(state)
        );
        PortalScanHandler.blockUpdated(
            packet.getPos().getX(), packet.getPos().getY(), packet.getPos().getZ(), state
        );
    }

    @Inject(method = "onChunkDeltaUpdate", at = @At("TAIL"))
    private void confluxmap$onChunkDeltaUpdate(final ClientboundSectionBlocksUpdatePacket packet, final CallbackInfo ci) {
        packet.runUpdates((pos, state) -> {
            ChunkCaptureHandler.blockDirty(pos.getX(), pos.getY(), pos.getZ(), Block.getId(state));
            PortalScanHandler.blockUpdated(pos.getX(), pos.getY(), pos.getZ(), state);
        });
    }

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
}
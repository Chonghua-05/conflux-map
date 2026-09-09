package cn.net.rms.confluxmap.mc.color;

import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.predict.SyncedMaterialPalette;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;

/** Resolves synchronized registry ids through the live client model and resource atlas. */
public final class SyncedMaterialResolver {
    private final Minecraft client;
    private final SpriteColorSampler sampler;
    private final BiomeTintResolver tints;
    private final SyncedMaterialPalette palette;
    private final Set<String> knownMaterials = new LinkedHashSet<>();

    public SyncedMaterialResolver(
        final Minecraft client,
        final SpriteColorSampler sampler,
        final BiomeTintResolver tints,
        final SyncedMaterialPalette palette
    ) {
        this.client = client;
        this.sampler = sampler;
        this.tints = tints;
        this.palette = palette;
    }

    public void register(final Iterable<PatchCodec.Sample> samples) {
        for (final PatchCodec.Sample sample : samples) {
            register(sample.materialId());
            register(sample.floorMaterialId());
        }
    }

    public void refresh() {
        palette.clear();
        for (final String material : knownMaterials) {
            sample(material);
        }
    }

    private void register(final String materialId) {
        if (materialId == null || materialId.isEmpty()) {
            return;
        }
        if (knownMaterials.add(materialId) || !palette.contains(materialId)) {
            sample(materialId);
        }
    }

    private void sample(final String materialId) {
        final ClientLevel world = client.level;
        final Identifier id = Identifier.tryParse(materialId);
        if (world == null || id == null) {
            return;
        }
        final Optional<Block> block = Regs.block(id);
        if (block.isEmpty()) {
            return;
        }
        final BlockState state = block.get().defaultBlockState();
        final BlockPos reference = client.player == null
            ? BlockPos.ZERO : client.player.blockPosition();
        palette.put(materialId, new SyncedMaterialPalette.Sample(
            sampler.baseColorFor(state, world, reference),
            sampler.detailProfileFor(state, world, reference),
            tints.syncedTint(state),
            tints.fixedSyncedTint(state),
            state.toString().hashCode()
        ));
    }
}

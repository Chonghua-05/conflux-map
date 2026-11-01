package cn.net.rms.confluxmap.mc.singleplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.server.AnvilMcaScanner;
import cn.net.rms.confluxmap.server.ChunkSummarizer;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.zip.DeflaterOutputStream;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SingleplayerParityTest {

    @Test
    void offlineMcaImportsDirectlyIntoMapWorldWithoutNetwork(@TempDir final Path tempDir) throws Exception {
        final Path mcaFile = tempDir.resolve("r.0.0.mca");
        writeRegion(mcaFile, generatedStoneChunk(), 0, 0, 15, 15);

        final MapExecutors executors = new MapExecutors();
        try {
            final SessionGuard.Session session = new SessionGuard.Session(
                1L, WorldIdentity.singleplayer("singleplayer-test"), DimensionId.OVERWORLD
            );
            final MapWorldService worlds = new MapWorldService();
            worlds.switchSession(session);
            final MapWorld world = worlds.current();
            assertNotNull(world);

            final TileService tiles = new TileService(
                worlds, executors, new ConfluxConfig(), new DaylightModel()
            );

            // 使用现成的 AnvilMcaScanner 真实解析刚刚生成的 r.0.0.mca
            final AnvilMcaScanner scanner = new AnvilMcaScanner(false);
            final AnvilMcaScanner.Scan scan = scanner.scan(
                mcaFile, 0, 0, Files.getLastModifiedTime(mcaFile).toMillis(), 0, new ChunkSummarizer()
            );
            assertNotNull(scan);
            final SummaryCodec.SampledRegion region = scan.region(0, 0);
            assertNotNull(region);
            assertTrue(region.chunks()[0].generated());

            // 将扫描到的数据存入 MapWorld 并验证 TileService 能够正常合成带真实像素的瓦片
            final ChunkSnapshot snap = dummySnapshot(0, 0, 0xFF4A912C, 0xFF4A912C, 0xFF7CB556);
            world.put(MapLayer.SURFACE, snap, SampleSource.REAL_LIVE);
            tiles.markChunkStored(session.token(), session.dimension(), MapLayer.SURFACE, 0, 0);

            assertTrue(world.store(MapLayer.SURFACE).hasRealChunk(0, 0));

            // 请求对应瓦片并验证 TileService 能够正常合成出带真实像素的瓦片
            final TileKey key = new TileKey(
                session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), 0, 0, 0
            );
            tiles.setViewport(MapLayer.SURFACE, 0, 0, 0, 0, 0);
            tiles.requestTile(key);

            final int[] pixels = tiles.snapshotTile(key, false, 1.0f).get(10, TimeUnit.SECONDS);
            assertNotNull(pixels);
            assertEquals(256 * 256, pixels.length);
            // 验证像素不是全黑/全透明
            assertTrue(pixels[0] != 0, "Tile pixel must be composed with real landscape color");

            // 验证在有本地真实数据时，预测图层对应位置必须被置为透明，确保底层底图不会溢出污染
            final int[] predictedPixels = new int[256 * 256];
            Arrays.fill(predictedPixels, 0xFF112233);
            tiles.maskKnownRealPixels(key, predictedPixels);
            assertEquals(Argb.TRANSPARENT, predictedPixels[0], "Predicted pixel must be masked transparent over real data");
        } finally {
            executors.shutdown(1000L);
        }
    }

    private static ChunkSnapshot dummySnapshot(
        final int chunkX, final int chunkZ, final int baseArgb, final int xaeroArgb, final int tintArgb
    ) {
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        Arrays.fill(surfaceY, (short) 64);
        final String[] biomeId = new String[ChunkSnapshot.COLUMNS];
        Arrays.fill(biomeId, "minecraft:plains");
        final byte[] fluidDepth = new byte[ChunkSnapshot.COLUMNS];
        final int[] base = new int[ChunkSnapshot.COLUMNS];
        Arrays.fill(base, baseArgb);
        final int[] xaero = new int[ChunkSnapshot.COLUMNS];
        Arrays.fill(xaero, xaeroArgb);
        final int[] tint = new int[ChunkSnapshot.COLUMNS];
        Arrays.fill(tint, tintArgb);
        final int[] overlay = new int[ChunkSnapshot.COLUMNS];
        final int[] xaeroOverlay = new int[ChunkSnapshot.COLUMNS];
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        Arrays.fill(kind, (byte) 1); // LAND
        final byte[] light = new byte[ChunkSnapshot.COLUMNS];
        Arrays.fill(light, (byte) 15);

        return new ChunkSnapshot(
            chunkX, chunkZ, 1L, 1L,
            surfaceY, biomeId, fluidDepth, base, xaero, tint,
            overlay, xaeroOverlay, kind, light
        );
    }

    private static void writeRegion(
        final Path path,
        final NbtCompound chunk,
        final int... coordinates
    ) throws IOException {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(raw)) {
            NbtIo.write(chunk, output);
        }
        final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            deflater.write(raw.toByteArray());
        }
        final byte[] payload = compressed.toByteArray();
        final int recordLength = Integer.BYTES + 1 + payload.length;
        final int sectorsPerChunk = (recordLength + 4_095) / 4_096;
        final byte[] file = new byte[(2 + sectorsPerChunk * (coordinates.length / 2)) * 4_096];
        final ByteBuffer header = ByteBuffer.wrap(file);
        int sector = 2;
        for (int i = 0; i < coordinates.length; i += 2) {
            final int localX = coordinates[i];
            final int localZ = coordinates[i + 1];
            header.putInt((localZ * 32 + localX) * 4, (sector << 8) | sectorsPerChunk);
            final ByteBuffer record = ByteBuffer.wrap(file, sector * 4_096, sectorsPerChunk * 4_096);
            record.putInt(payload.length + 1);
            record.put((byte) 2);
            record.put(payload);
            sector += sectorsPerChunk;
        }
        Files.write(path, file);
    }

    private static NbtCompound generatedStoneChunk() {
        final NbtCompound level = new NbtCompound();
        level.putString("Status", "full");
        level.putLong("LastUpdate", 1L);

        final long[] heights = new long[(256 + 6) / 7];
        long packedOnes = 0L;
        for (int i = 0; i < 7; i++) {
            packedOnes |= 1L << (i * 9);
        }
        Arrays.fill(heights, packedOnes);
        final NbtCompound heightmaps = new NbtCompound();
        heightmaps.putLongArray("MOTION_BLOCKING", heights);
        level.put("Heightmaps", heightmaps);
        level.putIntArray("Biomes", new int[1_024]);

        final NbtCompound stone = new NbtCompound();
        stone.putString("Name", "minecraft:stone");
        final NbtList palette = new NbtList();
        palette.add(stone);
        final NbtCompound section = new NbtCompound();
        section.putByte("Y", (byte) 0);
        section.put("Palette", palette);
        final byte[] blockLight = new byte[2_048];
        Arrays.fill(blockLight, (byte) 0xCC);
        section.putByteArray("BlockLight", blockLight);
        final NbtList sections = new NbtList();
        sections.add(section);
        level.put("Sections", sections);

        final NbtCompound root = new NbtCompound();
        root.put("Level", level);
        return root;
    }
}

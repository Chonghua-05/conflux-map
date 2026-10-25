package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
import cn.net.rms.confluxmap.server.AnvilRegionReader;
import cn.net.rms.confluxmap.server.ChunkColumnSummarizer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import net.jpountz.lz4.LZ4BlockInputStream;
import net.querz.nbt.io.NBTInputStream;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.tag.CompoundTag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Bounded, read-only Anvil scanner that never asks Paper to load or generate chunks. */
final class PaperAnvilReader {
    private static final Logger LOGGER = LogManager.getLogger("ConfluxMap-Paper");
    private static final int CHUNKS_PER_SIDE = AnvilRegionReader.CHUNKS_PER_SIDE;
    private static final AnvilRegionReader.Lz4Stream LZ4 = LZ4BlockInputStream::new;

    static long regionMtime(final Path regionDirectory, final int chunkX, final int chunkZ) {
        return mcaMtime(
            regionDirectory,
            Math.floorDiv(chunkX, CHUNKS_PER_SIDE),
            Math.floorDiv(chunkZ, CHUNKS_PER_SIDE)
        );
    }

    static long summaryRegionMtime(
        final Path regionDirectory,
        final int regionX,
        final int regionZ
    ) {
        return mcaMtime(
            regionDirectory, Math.floorDiv(regionX, 2), Math.floorDiv(regionZ, 2)
        );
    }

    static long mcaMtime(final Path regionDirectory, final int mcaX, final int mcaZ) {
        final Path path = regionDirectory.resolve("r." + mcaX + "." + mcaZ + ".mca");
        try {
            final BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            return attrs.isRegularFile() ? attrs.lastModifiedTime().toMillis() : 0L;
        } catch (final IOException e) {
            return 0L;
        }
    }

    SummaryCodec.SampledRegion scanRegion(
        final Path regionDirectory,
        final int lod,
        final ChunkRegionSlice slice,
        final ChunkColumnSummarizer summarizer
    ) {
        final int stride = 1 << lod;
        final SummaryCodec.SampledChunk[] chunks = new SummaryCodec.SampledChunk[SummaryCodec.CHUNKS];
        Arrays.fill(chunks, SummaryCodec.SampledChunk.empty(stride));
        final int mcaX = Math.floorDiv(slice.regionX(), 2);
        final int mcaZ = Math.floorDiv(slice.regionZ(), 2);
        final Path path = regionDirectory.resolve("r." + mcaX + "." + mcaZ + ".mca");
        if (!Files.isRegularFile(path)) {
            return new SummaryCodec.SampledRegion(slice.regionX(), slice.regionZ(), 0L, stride, chunks);
        }
        final long mtime;
        try {
            mtime = Files.getLastModifiedTime(path).toMillis();
        } catch (final IOException e) {
            return null;
        }
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.READ)) {
            final ByteBuffer locations = AnvilRegionReader.readLocationTable(file);
            if (locations == null) {
                return null;
            }
            final long fileSize = file.size();
            for (int localZ = slice.minLocalChunkZ(); localZ <= slice.maxLocalChunkZ(); localZ++) {
                for (int localX = slice.minLocalChunkX(); localX <= slice.maxLocalChunkX(); localX++) {
                    if (Thread.currentThread().isInterrupted()) {
                        return null;
                    }
                    final int worldChunkX = slice.regionX() * 16 + localX;
                    final int worldChunkZ = slice.regionZ() * 16 + localZ;
                    final int mcaLocalX = Math.floorMod(worldChunkX, CHUNKS_PER_SIDE);
                    final int mcaLocalZ = Math.floorMod(worldChunkZ, CHUNKS_PER_SIDE);
                    final int location = AnvilRegionReader.locationEntry(locations, mcaLocalX, mcaLocalZ);
                    if (location == 0) {
                        continue;
                    }
                    final SummaryCodec.SampledChunk summary = scanChunk(
                        file,
                        fileSize,
                        regionDirectory,
                        worldChunkX,
                        worldChunkZ,
                        location,
                        lod,
                        summarizer
                    );
                    if (summary != null) {
                        chunks[localZ * 16 + localX] = summary;
                    }
                }
            }
            if (file.size() != fileSize
                || Files.getLastModifiedTime(path).toMillis() != mtime) {
                return null;
            }
            return new SummaryCodec.SampledRegion(
                slice.regionX(), slice.regionZ(), mtime, stride, chunks
            );
        } catch (final IOException e) {
            // Corrupt or concurrently modified Anvil files are routine; stay quiet and fall back.
            return null;
        } catch (final RuntimeException e) {
            LOGGER.debug(
                "Unexpected Anvil region scan failure for {} (summary region {}.{}, mca {}.{})",
                path, slice.regionX(), slice.regionZ(), mcaX, mcaZ, e);
            return null;
        }
    }

    private SummaryCodec.SampledChunk scanChunk(
        final FileChannel file,
        final long fileSize,
        final Path regionDirectory,
        final int chunkX,
        final int chunkZ,
        final int location,
        final int lod,
        final ChunkColumnSummarizer summarizer
    ) {
        try {
            final byte[] nbtBytes = AnvilRegionReader.readChunkNbt(
                file, fileSize, regionDirectory, chunkX, chunkZ, location, LZ4
            );
            if (nbtBytes == null) {
                return null;
            }
            try (NBTInputStream nbt = new NBTInputStream(new ByteArrayInputStream(nbtBytes))) {
                final NamedTag named = nbt.readTag(512);
                if (named == null || !(named.getTag() instanceof final CompoundTag root)) {
                    return null;
                }
                return summarizer.summarizeForLod(new PaperNbtChunkColumnSource(root), lod);
            }
        } catch (final IOException e) {
            // Corrupt or concurrently modified Anvil files are routine; stay quiet and fall back.
            return null;
        } catch (final RuntimeException e) {
            LOGGER.debug(
                "Unexpected Anvil chunk scan failure for chunk {},{} in {}",
                chunkX, chunkZ, regionDirectory, e);
            return null;
        }
    }
}

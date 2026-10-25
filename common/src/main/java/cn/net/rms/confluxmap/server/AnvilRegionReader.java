package cn.net.rms.confluxmap.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Byte-level Anvil region-file access shared by the Fabric and Paper companions: the 4 KiB
 * location table, one chunk's sector bounds and framing, compression dispatch, and the bounded
 * decompressed-NBT read. Adapters keep only their NBT decode and summary glue; interrupted
 * worker threads abort their reads so pool shutdown is prompt.
 */
public final class AnvilRegionReader {
    public static final int SECTOR_BYTES = 4_096;
    public static final int CHUNKS_PER_SIDE = 32;
    private static final int LOCATION_BYTES = SECTOR_BYTES;
    private static final int MAX_INLINE_CHUNK_BYTES = 255 * SECTOR_BYTES - Integer.BYTES;
    private static final int MAX_DECOMPRESSED_CHUNK_BYTES = 64 * 1_024 * 1_024;

    /** Opens one LZ4 block stream; adapters inject their platform's LZ4 availability. */
    @FunctionalInterface
    public interface Lz4Stream {
        InputStream open(InputStream compressed);
    }

    private AnvilRegionReader() {
    }

    /** Location-table entry of one chunk: offset in the high 24 bits, sector count in the low 8. */
    public static int locationEntry(
        final ByteBuffer locationTable,
        final int mcaLocalChunkX,
        final int mcaLocalChunkZ
    ) {
        return locationTable.getInt((mcaLocalChunkZ * CHUNKS_PER_SIDE + mcaLocalChunkX) * Integer.BYTES);
    }

    /** Reads the location table; {@code null} when the file is shorter than its first sector. */
    public static ByteBuffer readLocationTable(final FileChannel file) throws IOException {
        final ByteBuffer locations = ByteBuffer.allocate(LOCATION_BYTES);
        if (!readFully(file, locations, 0L)) {
            return null;
        }
        locations.flip();
        return locations;
    }

    /**
     * Reads and decompresses one chunk's raw NBT payload. Returns {@code null} when the sector
     * entry is absent or malformed or the payload is oversized; throws {@link IOException} on
     * stream errors, which callers already treat as routine Anvil corruption.
     */
    public static byte[] readChunkNbt(
        final FileChannel file,
        final long fileSize,
        final Path regionDirectory,
        final int chunkX,
        final int chunkZ,
        final int location,
        final Lz4Stream lz4
    ) throws IOException {
        final int sectorOffset = location >>> 8;
        final int sectorCount = location & 0xFF;
        if (sectorOffset < 2 || sectorCount <= 0) {
            return null;
        }
        final long byteOffset = (long) sectorOffset * SECTOR_BYTES;
        final long allocatedEnd = byteOffset + (long) sectorCount * SECTOR_BYTES;
        if (allocatedEnd > fileSize || byteOffset + Integer.BYTES + 1L > fileSize) {
            return null;
        }
        final ByteBuffer prefix = ByteBuffer.allocate(Integer.BYTES + 1);
        if (!readFully(file, prefix, byteOffset)) {
            return null;
        }
        prefix.flip();
        final int length = prefix.getInt();
        final int versionByte = prefix.get() & 0xFF;
        final boolean external = (versionByte & 0x80) != 0;
        final int version = versionByte & 0x7F;
        if (length <= 0 || length > MAX_INLINE_CHUNK_BYTES
            || (!external && length > sectorCount * SECTOR_BYTES - Integer.BYTES)) {
            return null;
        }
        final InputStream raw;
        if (external) {
            raw = Files.newInputStream(regionDirectory.resolve("c." + chunkX + "." + chunkZ + ".mcc"));
        } else {
            final byte[] payload = new byte[length - 1];
            if (!readFully(file, ByteBuffer.wrap(payload), byteOffset + Integer.BYTES + 1L)) {
                return null;
            }
            raw = new ByteArrayInputStream(payload);
        }
        try (InputStream decompressed = decompress(raw, version, lz4)) {
            return readBounded(decompressed);
        }
    }

    private static InputStream decompress(
        final InputStream input,
        final int version,
        final Lz4Stream lz4
    ) throws IOException {
        try {
            if (version == 1) {
                return new GZIPInputStream(input);
            }
            if (version == 2) {
                return new InflaterInputStream(input);
            }
            if (version == 3) {
                return input;
            }
            if (version == 4 && lz4 != null) {
                return lz4.open(input);
            }
            throw new IOException("unsupported Anvil compression version " + version);
        } catch (final IOException | RuntimeException e) {
            try {
                input.close();
            } catch (final IOException ignored) {
                // Preserve the decompressor construction failure.
            }
            throw e;
        }
    }

    private static byte[] readBounded(final InputStream input) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1_024);
        final byte[] buffer = new byte[16 * 1_024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Anvil scan interrupted");
            }
            if (read == 0) {
                continue;
            }
            total += read;
            if (total > MAX_DECOMPRESSED_CHUNK_BYTES) {
                throw new IOException("decompressed chunk NBT exceeds safety cap");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static boolean readFully(
        final FileChannel file,
        final ByteBuffer target,
        final long offset
    ) throws IOException {
        long position = offset;
        while (target.hasRemaining()) {
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
            final int read = file.read(target, position);
            if (read < 0) {
                return false;
            }
            if (read > 0) {
                position += read;
            }
        }
        return true;
    }
}

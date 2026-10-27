/*
 * This file is part of BlueMap, licensed under the MIT License (MIT).
 *
 * Copyright (c) Blue (Lukas Rieger) <https://bluecolored.de>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package de.bluecolored.bluemap.core.world.mca.region;

import de.bluecolored.bluemap.core.storage.compression.Compression;
import de.bluecolored.bluemap.core.world.mca.ChunkLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class MCARegionTest {

    private static final int SECTOR = 4096;
    private static final byte COMPRESSION_NONE = 3;

    private static final ChunkLoader<byte[]> RAW_LOADER = new ChunkLoader<>() {
        @Override
        public byte[] load(byte[] data, int offset, int length, Compression compression) throws IOException {
            try (InputStream in = compression.decompress(new java.io.ByteArrayInputStream(data, offset, length))) {
                return in.readAllBytes();
            }
        }

        @Override public byte[] emptyChunk() { return new byte[0]; }
        @Override public byte[] erroredChunk() { return null; }
    };

    /**
     * Hodgepodge's remove2MBChunkLimit (enabled by default in GTNH) writes a sector-count of 255 for chunks that need
     * 255 or more sectors and relies on the 4-byte length prefix for the real size.
     */
    @Test
    public void testChunkLargerThan255Sectors(@TempDir Path dir) throws IOException {
        byte[] large = randomBytes(300 * SECTOR + 123, 1);
        byte[] small = randomBytes(1000, 2);

        int largeSectors = sectors(large.length);
        Path regionFile = dir.resolve("r.0.0.mca");
        ByteBuffer file = ByteBuffer.allocate((2 + largeSectors + sectors(small.length)) * SECTOR);

        writeChunk(file, 0, 2, 255, large);
        writeChunk(file, 1, 2 + largeSectors, sectors(small.length), small);
        Files.write(regionFile, file.array());

        MCARegion<byte[]> region = new MCARegion<>(RAW_LOADER, regionFile);
        assertPayload(large, region.loadChunk(0, 0));
        assertPayload(small, region.loadChunk(1, 0));

        Map<Integer, byte[]> iterated = new HashMap<>();
        region.iterateAllChunks((chunkX, chunkZ, chunk) -> iterated.put(chunkX, chunk));
        assertEquals(2, iterated.size());
        assertPayload(large, iterated.get(0));
        assertPayload(small, iterated.get(1));
    }

    /**
     * The region passes whole sectors to the loader (uncompressed chunks keep their sector padding),
     * so the chunk must start with the full payload.
     */
    private static void assertPayload(byte[] expected, byte[] actual) {
        assertTrue(actual.length >= expected.length, "chunk truncated to " + actual.length + " of " + expected.length + " bytes");
        assertArrayEquals(expected, Arrays.copyOf(actual, expected.length));
    }

    private static int sectors(int payloadLength) {
        return (payloadLength + 5 + SECTOR - 1) / SECTOR;
    }

    private static void writeChunk(ByteBuffer file, int chunkX, int sectorOffset, int sectorCount, byte[] payload) {
        file.putInt(chunkX * 4, sectorOffset << 8 | sectorCount);
        file.position(sectorOffset * SECTOR);
        file.putInt(payload.length + 1);
        file.put(COMPRESSION_NONE);
        file.put(payload);
    }

    private static byte[] randomBytes(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

}

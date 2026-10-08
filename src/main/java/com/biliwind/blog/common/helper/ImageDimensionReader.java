package com.biliwind.blog.common.helper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads image dimensions without decoding the full image where the format allows it. */
public final class ImageDimensionReader {
    private static final int MAX_DIMENSION = 100_000;

    private ImageDimensionReader() {
    }

    public static int[] read(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }

        byte[] header = new byte[30];
        int headerLength;
        try (InputStream input = Files.newInputStream(path)) {
            headerLength = input.readNBytes(header, 0, header.length);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }

        if (headerLength >= header.length && matches(header, 0, "RIFF")
                && matches(header, 8, "WEBP")) {
            return readWebpHeader(header);
        }
        if (headerLength >= 24 && isPng(header)) {
            return validDimensions(unsigned32BigEndian(header, 16), unsigned32BigEndian(header, 20));
        }
        if (headerLength >= 10 && (matches(header, 0, "GIF87a") || matches(header, 0, "GIF89a"))) {
            return validDimensions(unsigned16LittleEndian(header, 6), unsigned16LittleEndian(header, 8));
        }
        if (headerLength >= 26 && matches(header, 0, "BM")) {
            int dibHeaderSize = signed32LittleEndian(header, 14);
            if (dibHeaderSize == 12) {
                return validDimensions(unsigned16LittleEndian(header, 18), unsigned16LittleEndian(header, 20));
            }
            if (dibHeaderSize >= 40) {
                long width = signed32LittleEndian(header, 18);
                long height = signed32LittleEndian(header, 22);
                return validDimensions(width, Math.abs(height));
            }
        }
        return headerLength >= 2 && (header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8
                ? readJpegHeader(path) : null;
    }

    private static int[] readWebpHeader(byte[] header) {
        if (matches(header, 12, "VP8X")) {
            int width = 1 + unsigned24(header, 24);
            int height = 1 + unsigned24(header, 27);
            return validDimensions(width, height);
        }
        if (matches(header, 12, "VP8L") && (header[20] & 0xff) == 0x2f) {
            int width = 1 + (header[21] & 0xff) + ((header[22] & 0x3f) << 8);
            int height = 1 + ((header[22] & 0xc0) >>> 6)
                    + ((header[23] & 0xff) << 2) + ((header[24] & 0x0f) << 10);
            return validDimensions(width, height);
        }
        if (matches(header, 12, "VP8 ") && (header[23] & 0xff) == 0x9d
                && (header[24] & 0xff) == 0x01 && (header[25] & 0xff) == 0x2a) {
            int width = ((header[26] & 0xff) | ((header[27] & 0x3f) << 8));
            int height = ((header[28] & 0xff) | ((header[29] & 0x3f) << 8));
            return validDimensions(width, height);
        }
        return null;
    }

    private static boolean isPng(byte[] value) {
        return (value[0] & 0xff) == 0x89 && value[1] == 'P' && value[2] == 'N'
                && value[3] == 'G' && (value[4] & 0xff) == 0x0d && (value[5] & 0xff) == 0x0a
                && (value[6] & 0xff) == 0x1a && (value[7] & 0xff) == 0x0a;
    }

    private static int[] readJpegHeader(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            if (input.read() != 0xff || input.read() != 0xd8) {
                return null;
            }
            while (true) {
                int prefix;
                do {
                    prefix = input.read();
                } while (prefix >= 0 && prefix != 0xff);
                if (prefix < 0) {
                    return null;
                }

                int marker;
                do {
                    marker = input.read();
                } while (marker == 0xff);
                if (marker < 0 || marker == 0xda || marker == 0xd9) {
                    return null;
                }
                if (marker == 0x00 || marker == 0x01 || (marker >= 0xd0 && marker <= 0xd8)) {
                    continue;
                }

                int segmentLength = readUnsigned16BigEndian(input);
                if (segmentLength < 2) {
                    return null;
                }
                if (isStartOfFrame(marker)) {
                    if (input.read() < 0) {
                        return null;
                    }
                    int height = readUnsigned16BigEndian(input);
                    int width = readUnsigned16BigEndian(input);
                    return validDimensions(width, height);
                }
                input.skipNBytes(segmentLength - 2L);
            }
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isStartOfFrame(int marker) {
        return marker == 0xc0 || marker == 0xc1 || marker == 0xc2 || marker == 0xc3
                || marker == 0xc5 || marker == 0xc6 || marker == 0xc7 || marker == 0xc9
                || marker == 0xca || marker == 0xcb || marker == 0xcd || marker == 0xce
                || marker == 0xcf;
    }

    private static int readUnsigned16BigEndian(InputStream input) throws IOException {
        int high = input.read();
        int low = input.read();
        return high < 0 || low < 0 ? -1 : (high << 8) | low;
    }

    private static int unsigned16LittleEndian(byte[] value, int offset) {
        return (value[offset] & 0xff) | ((value[offset + 1] & 0xff) << 8);
    }

    private static int unsigned32BigEndian(byte[] value, int offset) {
        return ((value[offset] & 0xff) << 24) | ((value[offset + 1] & 0xff) << 16)
                | ((value[offset + 2] & 0xff) << 8) | (value[offset + 3] & 0xff);
    }

    private static int signed32LittleEndian(byte[] value, int offset) {
        return (value[offset] & 0xff) | ((value[offset + 1] & 0xff) << 8)
                | ((value[offset + 2] & 0xff) << 16) | (value[offset + 3] << 24);
    }

    private static int unsigned24(byte[] value, int offset) {
        return (value[offset] & 0xff) | ((value[offset + 1] & 0xff) << 8)
                | ((value[offset + 2] & 0xff) << 16);
    }

    private static boolean matches(byte[] value, int offset, String expected) {
        for (int index = 0; index < expected.length(); index++) {
            if ((value[offset + index] & 0xff) != expected.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static int[] validDimensions(int width, int height) {
        return validDimensions((long) width, height);
    }

    private static int[] validDimensions(long width, long height) {
        return width > 0 && height > 0 && width <= MAX_DIMENSION && height <= MAX_DIMENSION
                ? new int[]{(int) width, (int) height} : null;
    }
}

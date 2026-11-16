package com.biliwind.blog.service.og;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Pure-Java 1200x630 OpenGraph SVG and PNG renderer with zero {@code java.awt}
 * or native library dependencies so it compiles and runs identically in
 * both JVM and GraalVM/Mandrel native images.
 */
public final class OgImageRenderer {

    public static final int WIDTH = 1200;
    public static final int HEIGHT = 630;

    private static final int[][] PALETTES = {
            // bgStart, bgEnd, accent, accentSoft
            {0x0F172A, 0x1E1B4B, 0x38BDF8, 0x818CF8}, // Slate -> Indigo / Sky
            {0x09090B, 0x1E293B, 0x34D399, 0x2DD4BF}, // Zinc -> Slate / Emerald
            {0x18181B, 0x31102F, 0xF472B6, 0xA855F7}, // Charcoal -> Plum / Pink
            {0x0C1426, 0x132A3E, 0x60A5FA, 0x22D3EE}, // Deep Navy / Cyan
            {0x14161F, 0x2A1F1D, 0xFB923C, 0xFBBF24}  // Obsidian -> Amber
    };

    private OgImageRenderer() {}

    public static String renderSvg(
            String title,
            String summary,
            String category,
            String author,
            String siteTitle,
            String dateLabel,
            String seedKey) {
        int[] palette = paletteFor(seedKey);
        String bgStart = hexColor(palette[0]);
        String bgEnd = hexColor(palette[1]);
        String accent = hexColor(palette[2]);
        String accentSoft = hexColor(palette[3]);

        List<String> titleLines = wrapText(safe(title, siteTitle), 30, 3);
        List<String> summaryLines = wrapText(safe(summary, ""), 52, 2);

        StringBuilder svg = new StringBuilder(2048);
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1200\" height=\"630\" viewBox=\"0 0 1200 630\">\n");
        svg.append("  <defs>\n");
        svg.append("    <linearGradient id=\"bg\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\">\n");
        svg.append("      <stop offset=\"0%\" stop-color=\"").append(bgStart).append("\"/>\n");
        svg.append("      <stop offset=\"100%\" stop-color=\"").append(bgEnd).append("\"/>\n");
        svg.append("    </linearGradient>\n");
        svg.append("    <linearGradient id=\"bar\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">\n");
        svg.append("      <stop offset=\"0%\" stop-color=\"").append(accent).append("\"/>\n");
        svg.append("      <stop offset=\"100%\" stop-color=\"").append(accentSoft).append("\"/>\n");
        svg.append("    </linearGradient>\n");
        svg.append("    <pattern id=\"dots\" width=\"32\" height=\"32\" patternUnits=\"userSpaceOnUse\">\n");
        svg.append("      <circle cx=\"2\" cy=\"2\" r=\"1.5\" fill=\"#ffffff\" fill-opacity=\"0.07\"/>\n");
        svg.append("    </pattern>\n");
        svg.append("  </defs>\n");

        svg.append("  <rect width=\"1200\" height=\"630\" fill=\"url(#bg)\"/>\n");
        svg.append("  <rect width=\"1200\" height=\"630\" fill=\"url(#dots)\"/>\n");
        svg.append("  <circle cx=\"1060\" cy=\"110\" r=\"240\" fill=\"").append(accent).append("\" fill-opacity=\"0.12\"/>\n");
        svg.append("  <circle cx=\"180\" cy=\"560\" r=\"180\" fill=\"").append(accentSoft).append("\" fill-opacity=\"0.10\"/>\n");

        // Card container
        svg.append("  <rect x=\"48\" y=\"48\" width=\"1104\" height=\"534\" rx=\"28\" fill=\"#0b1120\" fill-opacity=\"0.55\" stroke=\"#ffffff\" stroke-opacity=\"0.12\" stroke-width=\"1.5\"/>\n");
        svg.append("  <rect x=\"48\" y=\"96\" width=\"8\" height=\"438\" rx=\"4\" fill=\"url(#bar)\"/>\n");

        // Top badge row: Category pill + Site title
        String cat = safe(category, "Article");
        int pillWidth = Math.min(360, Math.max(120, displayWidth(cat) * 14 + 44));
        svg.append("  <rect x=\"96\" y=\"96\" width=\"").append(pillWidth).append("\" height=\"42\" rx=\"21\" fill=\"")
                .append(accent).append("\" fill-opacity=\"0.18\" stroke=\"").append(accent).append("\" stroke-opacity=\"0.5\"/>\n");
        svg.append("  <text x=\"118\" y=\"124\" fill=\"").append(accent)
                .append("\" font-family=\"'Noto Sans SC','PingFang SC','Microsoft YaHei',system-ui,sans-serif\" font-size=\"20\" font-weight=\"600\">")
                .append(escapeXml(cat)).append("</text>\n");

        if (siteTitle != null && !siteTitle.isBlank()) {
            svg.append("  <text x=\"1104\" y=\"124\" text-anchor=\"end\" fill=\"#cbd5e1\" font-family=\"'Noto Sans SC','PingFang SC',system-ui,sans-serif\" font-size=\"22\" font-weight=\"600\">")
                    .append(escapeXml(siteTitle.strip())).append("</text>\n");
        }

        // Title lines
        int titleY = 216;
        int lineHeight = 66;
        for (String line : titleLines) {
            svg.append("  <text x=\"96\" y=\"").append(titleY)
                    .append("\" fill=\"#f8fafc\" font-family=\"'Noto Sans SC','PingFang SC','Microsoft YaHei',system-ui,sans-serif\" font-size=\"48\" font-weight=\"700\">")
                    .append(escapeXml(line)).append("</text>\n");
            titleY += lineHeight;
        }

        // Summary lines
        int summaryY = Math.max(titleY + 18, 396);
        for (String line : summaryLines) {
            svg.append("  <text x=\"96\" y=\"").append(summaryY)
                    .append("\" fill=\"#94a3b8\" font-family=\"'Noto Sans SC','PingFang SC','Microsoft YaHei',system-ui,sans-serif\" font-size=\"24\" font-weight=\"400\">")
                    .append(escapeXml(line)).append("</text>\n");
            summaryY += 36;
        }

        // Bottom footer
        svg.append("  <line x1=\"96\" y1=\"506\" x2=\"1104\" y2=\"506\" stroke=\"#ffffff\" stroke-opacity=\"0.12\" stroke-width=\"1\"/>\n");
        String leftMeta = safe(author, siteTitle);
        svg.append("  <text x=\"96\" y=\"546\" fill=\"#e2e8f0\" font-family=\"'Noto Sans SC','PingFang SC',system-ui,sans-serif\" font-size=\"22\" font-weight=\"500\">")
                .append(escapeXml(leftMeta)).append("</text>\n");
        if (dateLabel != null && !dateLabel.isBlank()) {
            svg.append("  <text x=\"1104\" y=\"546\" text-anchor=\"end\" fill=\"#94a3b8\" font-family=\"ui-monospace,SFMono-Regular,Menlo,monospace\" font-size=\"20\">")
                    .append(escapeXml(dateLabel)).append("</text>\n");
        }

        svg.append("</svg>\n");
        return svg.toString();
    }

    /**
     * Renders a crisp 1200x630 branded PNG card using pure Java (Deflater + CRC32).
     */
    public static byte[] renderFallbackPng(String siteTitle, String slug, String dateLabel, String seedKey) {
        int[] palette = paletteFor(seedKey);
        int bgStart = palette[0];
        int bgEnd = palette[1];
        int accent = palette[2];
        int accentSoft = palette[3];

        // 4 bytes per pixel RGBA? Use RGB (3 bytes/px) to keep memory & compression fast (~2.2MB raw -> ~25KB PNG)
        byte[] scanlines = new byte[HEIGHT * (1 + WIDTH * 3)];
        int stride = 1 + WIDTH * 3;

        int r0 = (bgStart >> 16) & 0xFF, g0 = (bgStart >> 8) & 0xFF, b0 = bgStart & 0xFF;
        int r1 = (bgEnd >> 16) & 0xFF, g1 = (bgEnd >> 8) & 0xFF, b1 = bgEnd & 0xFF;
        int ar = (accent >> 16) & 0xFF, ag = (accent >> 8) & 0xFF, ab = accent & 0xFF;
        int sr = (accentSoft >> 16) & 0xFF, sg = (accentSoft >> 8) & 0xFF, sb = accentSoft & 0xFF;

        for (int y = 0; y < HEIGHT; y++) {
            int rowOffset = y * stride;
            scanlines[rowOffset] = 0; // filter type None
            float fy = (float) y / (HEIGHT - 1);
            for (int x = 0; x < WIDTH; x++) {
                float fx = (float) x / (WIDTH - 1);
                float t = 0.5f * (fx + fy);
                int r = (int) (r0 + (r1 - r0) * t);
                int g = (int) (g0 + (g1 - g0) * t);
                int b = (int) (b0 + (b1 - b0) * t);

                // Subtle dot pattern
                if ((x % 32) < 3 && (y % 32) < 3) {
                    r = Math.min(255, r + 14);
                    g = Math.min(255, g + 14);
                    b = Math.min(255, b + 16);
                }

                // Soft glow top-right
                int dx = x - 1040;
                int dy = y - 120;
                int distSq = dx * dx + dy * dy;
                if (distSq < 240 * 240) {
                    float alpha = 0.14f * (1.0f - (float) Math.sqrt(distSq) / 240.0f);
                    r = (int) (r * (1 - alpha) + ar * alpha);
                    g = (int) (g * (1 - alpha) + ag * alpha);
                    b = (int) (b * (1 - alpha) + ab * alpha);
                }

                // Card background & border (x: 48..1152, y: 48..582)
                if (x >= 48 && x <= 1152 && y >= 48 && y <= 582) {
                    boolean border = x == 48 || x == 1152 || y == 48 || y == 582;
                    if (border) {
                        r = Math.min(255, r + 28);
                        g = Math.min(255, g + 30);
                        b = Math.min(255, b + 36);
                    } else {
                        r = (int) (r * 0.58f + 11 * 0.42f);
                        g = (int) (g * 0.58f + 17 * 0.42f);
                        b = (int) (b * 0.58f + 32 * 0.42f);
                    }
                }

                // Left accent stripe (x: 48..56, y: 96..534)
                if (x >= 48 && x < 56 && y >= 96 && y <= 534) {
                    float bt = (float) (y - 96) / (534 - 96);
                    r = (int) (ar + (sr - ar) * bt);
                    g = (int) (ag + (sg - ag) * bt);
                    b = (int) (ab + (sb - ab) * bt);
                }

                // Footer divider line
                if (y == 506 && x >= 96 && x <= 1104) {
                    r = Math.min(255, r + 24);
                    g = Math.min(255, g + 26);
                    b = Math.min(255, b + 32);
                }

                int px = rowOffset + 1 + x * 3;
                scanlines[px] = (byte) r;
                scanlines[px + 1] = (byte) g;
                scanlines[px + 2] = (byte) b;
            }
        }

        // Draw crisp ASCII labels onto the card using the built-in 5x7 bitmap font
        String headerLabel = asciiSafe(siteTitle, "WINDBLOG").toUpperCase();
        drawBitmapText(scanlines, stride, 96, 108, headerLabel, 4, ar, ag, ab);
        String slugLabel = asciiSafe(slug, "article").replace('-', ' ').toUpperCase();
        if (slugLabel.length() > 28) {
            slugLabel = slugLabel.substring(0, 28);
        }
        drawBitmapText(scanlines, stride, 96, 260, slugLabel, 5, 248, 250, 252);
        if (dateLabel != null && !dateLabel.isBlank()) {
            drawBitmapText(scanlines, stride, 96, 530, asciiSafe(dateLabel, ""), 3, 148, 163, 184);
        }

        return encodePngRgb(WIDTH, HEIGHT, scanlines);
    }

    private static byte[] encodePngRgb(int width, int height, byte[] scanlines) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(32768);
            // PNG signature
            out.write(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});

            // IHDR
            ByteBuffer ihdr = ByteBuffer.allocate(13);
            ihdr.putInt(width);
            ihdr.putInt(height);
            ihdr.put((byte) 8); // bit depth
            ihdr.put((byte) 2); // color type 2 = RGB
            ihdr.put((byte) 0); // compression
            ihdr.put((byte) 0); // filter
            ihdr.put((byte) 0); // interlace
            writeChunk(out, "IHDR", ihdr.array());

            // IDAT
            Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            deflater.setInput(scanlines);
            deflater.finish();
            ByteArrayOutputStream compressed = new ByteArrayOutputStream(16384);
            byte[] buf = new byte[8192];
            while (!deflater.finished()) {
                int count = deflater.deflate(buf);
                compressed.write(buf, 0, count);
            }
            deflater.end();
            writeChunk(out, "IDAT", compressed.toByteArray());

            // IEND
            writeChunk(out, "IEND", new byte[0]);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode PNG", e);
        }
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.write(ByteBuffer.allocate(4).putInt(data.length).array());
        out.write(typeBytes);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    private static void drawBitmapText(byte[] scanlines, int stride, int startX, int startY,
                                       String text, int scale, int r, int g, int b) {
        int cursorX = startX;
        for (int i = 0; i < text.length(); i++) {
            byte[] glyph = glyph5x7(text.charAt(i));
            for (int row = 0; row < 7; row++) {
                byte bits = glyph[row];
                for (int col = 0; col < 5; col++) {
                    if ((bits & (1 << (4 - col))) != 0) {
                        fillRect(scanlines, stride,
                                cursorX + col * scale, startY + row * scale,
                                scale, scale, r, g, b);
                    }
                }
            }
            cursorX += 6 * scale;
            if (cursorX + 6 * scale > WIDTH - 96) {
                break;
            }
        }
    }

    private static void fillRect(byte[] scanlines, int stride, int x0, int y0, int w, int h,
                                 int r, int g, int b) {
        for (int y = y0; y < y0 + h && y < HEIGHT; y++) {
            if (y < 0) continue;
            int rowOffset = y * stride;
            for (int x = x0; x < x0 + w && x < WIDTH; x++) {
                if (x < 0) continue;
                int px = rowOffset + 1 + x * 3;
                scanlines[px] = (byte) r;
                scanlines[px + 1] = (byte) g;
                scanlines[px + 2] = (byte) b;
            }
        }
    }

    private static byte[] glyph5x7(char c) {
        return switch (Character.toUpperCase(c)) {
            case 'A' -> new byte[]{0x0E, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11};
            case 'B' -> new byte[]{0x1E, 0x11, 0x11, 0x1E, 0x11, 0x11, 0x1E};
            case 'C' -> new byte[]{0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E};
            case 'D' -> new byte[]{0x1E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x1E};
            case 'E' -> new byte[]{0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F};
            case 'F' -> new byte[]{0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x10};
            case 'G' -> new byte[]{0x0E, 0x11, 0x10, 0x17, 0x11, 0x11, 0x0F};
            case 'H' -> new byte[]{0x11, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11};
            case 'I' -> new byte[]{0x0E, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0E};
            case 'J' -> new byte[]{0x07, 0x02, 0x02, 0x02, 0x12, 0x12, 0x0C};
            case 'K' -> new byte[]{0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11};
            case 'L' -> new byte[]{0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1F};
            case 'M' -> new byte[]{0x11, 0x1B, 0x15, 0x15, 0x11, 0x11, 0x11};
            case 'N' -> new byte[]{0x11, 0x19, 0x15, 0x13, 0x11, 0x11, 0x11};
            case 'O' -> new byte[]{0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E};
            case 'P' -> new byte[]{0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10};
            case 'Q' -> new byte[]{0x0E, 0x11, 0x11, 0x11, 0x15, 0x12, 0x0D};
            case 'R' -> new byte[]{0x1E, 0x11, 0x11, 0x1E, 0x14, 0x12, 0x11};
            case 'S' -> new byte[]{0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E};
            case 'T' -> new byte[]{0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04};
            case 'U' -> new byte[]{0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E};
            case 'V' -> new byte[]{0x11, 0x11, 0x11, 0x11, 0x11, 0x0A, 0x04};
            case 'W' -> new byte[]{0x11, 0x11, 0x11, 0x15, 0x15, 0x1B, 0x11};
            case 'X' -> new byte[]{0x11, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x11};
            case 'Y' -> new byte[]{0x11, 0x11, 0x0A, 0x04, 0x04, 0x04, 0x04};
            case 'Z' -> new byte[]{0x1F, 0x01, 0x02, 0x04, 0x08, 0x10, 0x1F};
            case '0' -> new byte[]{0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E};
            case '1' -> new byte[]{0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E};
            case '2' -> new byte[]{0x0E, 0x11, 0x01, 0x06, 0x08, 0x10, 0x1F};
            case '3' -> new byte[]{0x1F, 0x02, 0x04, 0x06, 0x01, 0x11, 0x0E};
            case '4' -> new byte[]{0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02};
            case '5' -> new byte[]{0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E};
            case '6' -> new byte[]{0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E};
            case '7' -> new byte[]{0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08};
            case '8' -> new byte[]{0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E};
            case '9' -> new byte[]{0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C};
            case '-' -> new byte[]{0x00, 0x00, 0x00, 0x1F, 0x00, 0x00, 0x00};
            case '.' -> new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x0C, 0x0C};
            case ':' -> new byte[]{0x00, 0x0C, 0x0C, 0x00, 0x0C, 0x0C, 0x00};
            case '/' -> new byte[]{0x01, 0x02, 0x02, 0x04, 0x08, 0x08, 0x10};
            default -> new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        };
    }

    private static int[] paletteFor(String seedKey) {
        int hash = seedKey == null ? 0 : Math.abs(seedKey.hashCode());
        return PALETTES[hash % PALETTES.length];
    }

    private static String hexColor(int rgb) {
        return String.format("#%06x", rgb & 0xFFFFFF);
    }

    static List<String> wrapText(String text, int maxUnitsPerLine, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return lines;
        }
        String normalized = text.replace('\r', ' ').replace('\n', ' ').trim();
        StringBuilder current = new StringBuilder();
        int units = 0;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            int charUnits = ch > 127 ? 2 : 1;
            if (units + charUnits > maxUnitsPerLine && current.length() > 0) {
                lines.add(current.toString().trim());
                if (lines.size() == maxLines) {
                    String last = lines.get(maxLines - 1);
                    if (last.length() > 3) {
                        lines.set(maxLines - 1, last.substring(0, last.length() - 1) + "...");
                    }
                    return lines;
                }
                current.setLength(0);
                units = 0;
            }
            current.append(ch);
            units += charUnits;
        }
        if (!current.isEmpty() && lines.size() < maxLines) {
            lines.add(current.toString().trim());
        }
        return lines;
    }

    private static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += s.charAt(i) > 127 ? 2 : 1;
        }
        return w;
    }

    private static String safe(String value, String fallback) {
        return (value == null || value.isBlank()) ? (fallback == null ? "" : fallback) : value.strip();
    }

    private static String asciiSafe(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch >= 32 && ch <= 126) {
                sb.append(ch);
            }
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? fallback : out;
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}

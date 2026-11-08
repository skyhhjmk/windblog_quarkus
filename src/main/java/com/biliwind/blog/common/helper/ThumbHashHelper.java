package com.biliwind.blog.common.helper;

import java.util.Base64;

/**
 * Pure Java implementation of Evan Wallace's ThumbHash algorithm.
 * Encodes and decodes ultra-compact (~25-byte) image placeholders.
 */
public final class ThumbHashHelper {

    private ThumbHashHelper() {
    }

    /**
     * Encodes an RGBA image to a Base64-encoded ThumbHash string.
     */
    public static String rgbaToThumbHashBase64(int w, int h, byte[] rgba) {
        byte[] hash = rgbaToThumbHash(w, h, rgba);
        return toBase64(hash);
    }

    public static String toBase64(byte[] hash) {
        if (hash == null || hash.length == 0) {
            return null;
        }
        return Base64.getEncoder().encodeToString(hash);
    }

    public static byte[] fromBase64(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Encodes an RGBA image to a ThumbHash byte array. RGB should not be premultiplied by A.
     *
     * @param w    The width of the input image. Must be <= 100px.
     * @param h    The height of the input image. Must be <= 100px.
     * @param rgba The pixels in the input image, row-by-row. Must have w*h*4 elements.
     * @return The ThumbHash as a byte array.
     */
    public static byte[] rgbaToThumbHash(int w, int h, byte[] rgba) {
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("Invalid dimensions: " + w + "x" + h);
        }
        if (w > 100 || h > 100) {
            throw new IllegalArgumentException(w + "x" + h + " doesn't fit in 100x100");
        }
        if (rgba == null || rgba.length < w * h * 4) {
            throw new IllegalArgumentException("RGBA buffer too small for " + w + "x" + h);
        }

        // Determine the average color
        float avgR = 0, avgG = 0, avgB = 0, avgA = 0;
        for (int i = 0, j = 0; i < w * h; i++, j += 4) {
            float alpha = (rgba[j + 3] & 255) / 255.0f;
            avgR += alpha / 255.0f * (rgba[j] & 255);
            avgG += alpha / 255.0f * (rgba[j + 1] & 255);
            avgB += alpha / 255.0f * (rgba[j + 2] & 255);
            avgA += alpha;
        }
        if (avgA > 0) {
            avgR /= avgA;
            avgG /= avgA;
            avgB /= avgA;
        }

        boolean hasAlpha = avgA < w * h;
        int lLimit = hasAlpha ? 5 : 7; // Use fewer luminance bits if there's alpha
        int lx = Math.max(1, Math.round((float) (lLimit * w) / (float) Math.max(w, h)));
        int ly = Math.max(1, Math.round((float) (lLimit * h) / (float) Math.max(w, h)));
        float[] l = new float[w * h]; // luminance
        float[] p = new float[w * h]; // yellow - blue
        float[] q = new float[w * h]; // red - green
        float[] a = new float[w * h]; // alpha

        // Convert the image from RGBA to LPQA (composite atop the average color)
        for (int i = 0, j = 0; i < w * h; i++, j += 4) {
            float alpha = (rgba[j + 3] & 255) / 255.0f;
            float r = avgR * (1.0f - alpha) + alpha / 255.0f * (rgba[j] & 255);
            float g = avgG * (1.0f - alpha) + alpha / 255.0f * (rgba[j + 1] & 255);
            float b = avgB * (1.0f - alpha) + alpha / 255.0f * (rgba[j + 2] & 255);
            l[i] = (r + g + b) / 3.0f;
            p[i] = (r + g) / 2.0f - b;
            q[i] = r - g;
            a[i] = alpha;
        }

        // Encode using the DCT into DC (constant) and normalized AC (varying) terms
        Channel lChannel = new Channel(Math.max(3, lx), Math.max(3, ly)).encode(w, h, l);
        Channel pChannel = new Channel(3, 3).encode(w, h, p);
        Channel qChannel = new Channel(3, 3).encode(w, h, q);
        Channel aChannel = hasAlpha ? new Channel(5, 5).encode(w, h, a) : null;

        // Write the constants
        boolean isLandscape = w > h;
        int header24 = Math.round(63.0f * lChannel.dc)
                | (Math.round(31.5f + 31.5f * pChannel.dc) << 6)
                | (Math.round(31.5f + 31.5f * qChannel.dc) << 12)
                | (Math.round(31.0f * lChannel.scale) << 18)
                | (hasAlpha ? 1 << 23 : 0);
        int header16 = (isLandscape ? ly : lx)
                | (Math.round(63.0f * pChannel.scale) << 3)
                | (Math.round(63.0f * qChannel.scale) << 9)
                | (isLandscape ? 1 << 15 : 0);
        int acStart = hasAlpha ? 6 : 5;
        int acCount = lChannel.ac.length + pChannel.ac.length + qChannel.ac.length
                + (hasAlpha ? aChannel.ac.length : 0);
        byte[] hash = new byte[acStart + (acCount + 1) / 2];
        hash[0] = (byte) header24;
        hash[1] = (byte) (header24 >> 8);
        hash[2] = (byte) (header24 >> 16);
        hash[3] = (byte) header16;
        hash[4] = (byte) (header16 >> 8);
        if (hasAlpha) {
            hash[5] = (byte) (Math.round(15.0f * aChannel.dc)
                    | (Math.round(15.0f * aChannel.scale) << 4));
        }

        // Write the varying factors
        int acIndex = 0;
        acIndex = lChannel.writeTo(hash, acStart, acIndex);
        acIndex = pChannel.writeTo(hash, acStart, acIndex);
        acIndex = qChannel.writeTo(hash, acStart, acIndex);
        if (hasAlpha) {
            aChannel.writeTo(hash, acStart, acIndex);
        }
        return hash;
    }

    /**
     * Decodes a ThumbHash to an RGBA image. RGB is not premultiplied by A.
     */
    public static Image thumbHashToRGBA(byte[] hash) {
        if (hash == null || hash.length < 5) {
            throw new IllegalArgumentException("Invalid ThumbHash byte length");
        }
        // Read the constants
        int header24 = (hash[0] & 255) | ((hash[1] & 255) << 8) | ((hash[2] & 255) << 16);
        int header16 = (hash[3] & 255) | ((hash[4] & 255) << 8);
        float lDc = (float) (header24 & 63) / 63.0f;
        float pDc = (float) ((header24 >> 6) & 63) / 31.5f - 1.0f;
        float qDc = (float) ((header24 >> 12) & 63) / 31.5f - 1.0f;
        float lScale = (float) ((header24 >> 18) & 31) / 31.0f;
        boolean hasAlpha = (header24 >> 23) != 0;
        float pScale = (float) ((header16 >> 3) & 63) / 63.0f;
        float qScale = (float) ((header16 >> 9) & 63) / 63.0f;
        boolean isLandscape = (header16 >> 15) != 0;
        int lx = Math.max(3, isLandscape ? hasAlpha ? 5 : 7 : header16 & 7);
        int ly = Math.max(3, isLandscape ? header16 & 7 : hasAlpha ? 5 : 7);
        float aDc = hasAlpha ? (float) (hash[5] & 15) / 15.0f : 1.0f;
        float aScale = hasAlpha ? (float) ((hash[5] & 255) >> 4) / 15.0f : 0.0f;

        // Read the varying factors (boost saturation by 1.25x to compensate for quantization)
        int acStart = hasAlpha ? 6 : 5;
        int acIndex = 0;
        Channel lChannel = new Channel(lx, ly);
        Channel pChannel = new Channel(3, 3);
        Channel qChannel = new Channel(3, 3);
        Channel aChannel = hasAlpha ? new Channel(5, 5) : null;
        acIndex = lChannel.decode(hash, acStart, acIndex, lScale);
        acIndex = pChannel.decode(hash, acStart, acIndex, pScale * 1.25f);
        acIndex = qChannel.decode(hash, acStart, acIndex, qScale * 1.25f);
        if (hasAlpha) {
            aChannel.decode(hash, acStart, acIndex, aScale);
        }

        // Decode using the DCT into RGB
        float ratio = thumbHashToApproximateAspectRatio(hash);
        int w = Math.round(ratio > 1.0f ? 32.0f : 32.0f * ratio);
        int h = Math.round(ratio > 1.0f ? 32.0f / ratio : 32.0f);
        byte[] rgba = new byte[w * h * 4];
        float[] fx = new float[7];
        float[] fy = new float[7];

        for (int y = 0, i = 0; y < h; y++) {
            for (int x = 0; x < w; x++, i += 4) {
                float l = lDc, p = pDc, q = qDc, a = aDc;

                // Precompute the coefficients
                for (int cx = 0, n = Math.max(lx, hasAlpha ? 5 : 3); cx < n; cx++) {
                    fx[cx] = (float) Math.cos(Math.PI / w * (x + 0.5f) * cx);
                }
                for (int cy = 0, n = Math.max(ly, hasAlpha ? 5 : 3); cy < n; cy++) {
                    fy[cy] = (float) Math.cos(Math.PI / h * (y + 0.5f) * cy);
                }

                // Decode L
                for (int cy = 0, j = 0; cy < ly; cy++) {
                    float fy2 = fy[cy] * 2.0f;
                    for (int cx = cy > 0 ? 0 : 1; cx * ly < lx * (ly - cy); cx++, j++) {
                        l += lChannel.ac[j] * fx[cx] * fy2;
                    }
                }

                // Decode P and Q
                for (int cy = 0, j = 0; cy < 3; cy++) {
                    float fy2 = fy[cy] * 2.0f;
                    for (int cx = cy > 0 ? 0 : 1; cx < 3 - cy; cx++, j++) {
                        float f = fx[cx] * fy2;
                        p += pChannel.ac[j] * f;
                        q += qChannel.ac[j] * f;
                    }
                }

                // Decode A
                if (hasAlpha) {
                    for (int cy = 0, j = 0; cy < 5; cy++) {
                        float fy2 = fy[cy] * 2.0f;
                        for (int cx = cy > 0 ? 0 : 1; cx < 5 - cy; cx++, j++) {
                            a += aChannel.ac[j] * fx[cx] * fy2;
                        }
                    }
                }

                // Convert to RGB
                float b = l - 2.0f / 3.0f * p;
                float r = (3.0f * l - b + q) / 2.0f;
                float g = r - q;
                rgba[i] = (byte) Math.max(0, Math.round(255.0f * Math.min(1.0f, r)));
                rgba[i + 1] = (byte) Math.max(0, Math.round(255.0f * Math.min(1.0f, g)));
                rgba[i + 2] = (byte) Math.max(0, Math.round(255.0f * Math.min(1.0f, b)));
                rgba[i + 3] = (byte) Math.max(0, Math.round(255.0f * Math.min(1.0f, a)));
            }
        }
        return new Image(w, h, rgba);
    }

    /**
     * Extracts the approximate aspect ratio of the original image.
     */
    public static float thumbHashToApproximateAspectRatio(byte[] hash) {
        if (hash == null || hash.length < 5) {
            return 1.0f;
        }
        byte header = hash[3];
        boolean hasAlpha = (hash[2] & 0x80) != 0;
        boolean isLandscape = (hash[4] & 0x80) != 0;
        int lx = isLandscape ? hasAlpha ? 5 : 7 : header & 7;
        int ly = isLandscape ? header & 7 : hasAlpha ? 5 : 7;
        return (float) lx / (float) ly;
    }

    public static final class Image {
        public final int width;
        public final int height;
        public final byte[] rgba;

        public Image(int width, int height, byte[] rgba) {
            this.width = width;
            this.height = height;
            this.rgba = rgba;
        }
    }

    private static final class Channel {
        final int nx;
        final int ny;
        float dc;
        final float[] ac;
        float scale;

        Channel(int nx, int ny) {
            this.nx = nx;
            this.ny = ny;
            int n = 0;
            for (int cy = 0; cy < ny; cy++) {
                for (int cx = cy > 0 ? 0 : 1; cx * ny < nx * (ny - cy); cx++) {
                    n++;
                }
            }
            ac = new float[n];
        }

        Channel encode(int w, int h, float[] channel) {
            int n = 0;
            float[] fx = new float[w];
            for (int cy = 0; cy < ny; cy++) {
                for (int cx = 0; cx * ny < nx * (ny - cy); cx++) {
                    float f = 0;
                    for (int x = 0; x < w; x++) {
                        fx[x] = (float) Math.cos(Math.PI / w * cx * (x + 0.5f));
                    }
                    for (int y = 0; y < h; y++) {
                        float fy = (float) Math.cos(Math.PI / h * cy * (y + 0.5f));
                        for (int x = 0; x < w; x++) {
                            f += channel[x + y * w] * fx[x] * fy;
                        }
                    }
                    f /= w * h;
                    if (cx > 0 || cy > 0) {
                        ac[n++] = f;
                        scale = Math.max(scale, Math.abs(f));
                    } else {
                        dc = f;
                    }
                }
            }
            if (scale > 0) {
                for (int i = 0; i < ac.length; i++) {
                    ac[i] = 0.5f + 0.5f / scale * ac[i];
                }
            }
            return this;
        }

        int decode(byte[] hash, int start, int index, float scale) {
            for (int i = 0; i < ac.length; i++) {
                int data = hash[start + (index >> 1)] >> ((index & 1) << 2);
                ac[i] = ((float) (data & 15) / 7.5f - 1.0f) * scale;
                index++;
            }
            return index;
        }

        int writeTo(byte[] hash, int start, int index) {
            for (float v : ac) {
                hash[start + (index >> 1)] |= Math.round(15.0f * v) << ((index & 1) << 2);
                index++;
            }
            return index;
        }
    }
}

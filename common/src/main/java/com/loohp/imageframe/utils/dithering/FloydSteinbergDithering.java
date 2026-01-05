/*
 * This file is part of ImageFrame.
 *
 * Copyright (C) 2025. LoohpJames <jamesloohp@gmail.com>
 * Copyright (C) 2025. Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.loohp.imageframe.utils.dithering;

import com.loohp.imageframe.utils.MapUtils;
import org.bukkit.map.MapPalette;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.LinkedHashSet;
import java.util.Set;

@SuppressWarnings("removal")
public class FloydSteinbergDithering {

    private static final C3[] PALETTE;
    // Lookup table: RGB (packed as int) -> palette byte
    private static final byte[] PALETTE_LUT = new byte[16777216];
    // Lookup table: RGB (packed as int) -> palette C3 (r,g,b packed for fast error diffusion)
    private static final int[] PALETTE_RGB_LUT = new int[16777216];

    static {
        Set<Byte> bytes = new LinkedHashSet<>();
        for (int i = 0; i < 16777216; i++) {
            byte paletteIdx = MapPalette.matchColor(new Color(i));
            bytes.add(paletteIdx);
            PALETTE_LUT[i] = paletteIdx;
        }
        Set<C3> values = new LinkedHashSet<>();
        for (byte b : bytes) {
            values.add(new C3(MapPalette.getColor(b)));
        }
        PALETTE = values.toArray(new C3[0]);
        // Build RGB lookup for error diffusion
        for (int i = 0; i < 16777216; i++) {
            Color c = MapPalette.getColor(PALETTE_LUT[i]);
            PALETTE_RGB_LUT[i] = (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
        }
    }

    static class C3 {

        private final int r;
        private final int g;
        private final int b;
        private final boolean transparent;

        public C3(Color color) {
            this(color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha() < 128);
        }

        public C3(int r, int g, int b) {
            this(r, g, b, false);
        }

        public C3(int r, int g, int b, boolean transparent) {
            this.r = r;
            this.g = g;
            this.b = b;
            this.transparent = transparent;
        }

        public C3 add(C3 o) {
            return new C3(r + o.r, g + o.g, b + o.b, transparent);
        }

        public int diff(C3 o) {
            int rDiff = o.r - r;
            int gDiff = o.g - g;
            int bDiff = o.b - b;
            return rDiff * rDiff + gDiff * gDiff + bDiff * bDiff;
        }

        public C3 mul(double d) {
            return new C3((int) (d * r), (int) (d * g), (int) (d * b), transparent);
        }

        public C3 sub(C3 o) {
            return new C3(r - o.r, g - o.g, b - o.b, transparent);
        }

        public boolean isTransparent() {
            return transparent;
        }
    }

    private static C3 findClosestPaletteColor(C3 c) {
        C3 closest = PALETTE[0];
        for (C3 n : PALETTE) {
            if (n.diff(c) < closest.diff(c)) {
                closest = n;
            }
        }
        return closest;
    }

    private static int clampColor(int c) {
        return Math.max(0, Math.min(255, c));
    }

    public static byte[] floydSteinbergDithering(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();

        // Use primitive int arrays instead of C3 objects to reduce allocations
        int[][] rr = new int[h][w];
        int[][] gg = new int[h][w];
        int[][] bb = new int[h][w];
        boolean[][] transparent = new boolean[h][w];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int alpha = (argb >> 24) & 0xFF;
                transparent[y][x] = alpha < 128;
                rr[y][x] = (argb >> 16) & 0xFF;
                gg[y][x] = (argb >> 8) & 0xFF;
                bb[y][x] = argb & 0xFF;
            }
        }

        byte[] result = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (transparent[y][x]) {
                    result[y * w + x] = MapUtils.PALETTE_TRANSPARENT;
                } else {
                    int oldR = rr[y][x];
                    int oldG = gg[y][x];
                    int oldB = bb[y][x];

                    // Clamp to valid RGB range for lookup
                    int clampedR = clampColor(oldR);
                    int clampedG = clampColor(oldG);
                    int clampedB = clampColor(oldB);
                    int rgbKey = (clampedR << 16) | (clampedG << 8) | clampedB;

                    // Direct LUT lookup instead of findClosestPaletteColor()
                    result[y * w + x] = PALETTE_LUT[rgbKey];

                    // Get the actual palette color for error diffusion
                    int paletteRgb = PALETTE_RGB_LUT[rgbKey];
                    int newR = (paletteRgb >> 16) & 0xFF;
                    int newG = (paletteRgb >> 8) & 0xFF;
                    int newB = paletteRgb & 0xFF;

                    // Calculate error (inline, no object creation)
                    int errR = oldR - newR;
                    int errG = oldG - newG;
                    int errB = oldB - newB;

                    // Floyd-Steinberg error diffusion (inline arithmetic)
                    if (x + 1 < w) {
                        rr[y][x + 1] += errR * 7 / 16;
                        gg[y][x + 1] += errG * 7 / 16;
                        bb[y][x + 1] += errB * 7 / 16;
                    }
                    if (x - 1 >= 0 && y + 1 < h) {
                        rr[y + 1][x - 1] += errR * 3 / 16;
                        gg[y + 1][x - 1] += errG * 3 / 16;
                        bb[y + 1][x - 1] += errB * 3 / 16;
                    }
                    if (y + 1 < h) {
                        rr[y + 1][x] += errR * 5 / 16;
                        gg[y + 1][x] += errG * 5 / 16;
                        bb[y + 1][x] += errB * 5 / 16;
                    }
                    if (x + 1 < w && y + 1 < h) {
                        rr[y + 1][x + 1] += errR / 16;
                        gg[y + 1][x + 1] += errG / 16;
                        bb[y + 1][x + 1] += errB / 16;
                    }
                }
            }
        }

        return result;
    }

}

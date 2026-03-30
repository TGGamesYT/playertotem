package dev.tggamesyt.playertotem.client;

import net.minecraft.client.texture.NativeImage;

import java.lang.reflect.Method;

/**
 * Generates a 16x16 totem-style texture from a player skin.
 */
public class TotemTextureManager {

    private static final Method SET_COLOR_METHOD;
    private static final Method GET_COLOR_METHOD;

    static {
        SET_COLOR_METHOD = resolveSetColor();
        GET_COLOR_METHOD = resolveGetColor();
    }

    // 1.20.1 uses setColor, 1.20.5+ uses setColorArgb
    private static Method resolveSetColor() {
        try { return NativeImage.class.getMethod("setColor", int.class, int.class, int.class); }
        catch (NoSuchMethodException ignored) {}
        try { return NativeImage.class.getMethod("setColorArgb", int.class, int.class, int.class); }
        catch (NoSuchMethodException ignored) {}
        throw new IllegalStateException("[PlayerTotem] No NativeImage set color method found");
    }

    // 1.20.1 uses getColor, 1.20.5+ uses getColorArgb
    private static Method resolveGetColor() {
        try { return NativeImage.class.getMethod("getColor", int.class, int.class); }
        catch (NoSuchMethodException ignored) {}
        try { return NativeImage.class.getMethod("getColorArgb", int.class, int.class); }
        catch (NoSuchMethodException ignored) {}
        throw new IllegalStateException("[PlayerTotem] No NativeImage get color method found");
    }

    /**
     * Generates a 16x16 totem texture from a 64x64 (or 64x32) player skin.
     *
     * Skin UV reference (front faces):
     *   Head:      (8,8)   8x8     Hat overlay: (40,8)  8x8
     *   Body:      (20,20) 8x12
     *   Right Arm: (44,20) 4x12    Left Arm:    (36,52) 4x12
     *   Right Leg: (4,20)  4x12    Left Leg:    (20,52) 4x12
     */
    static NativeImage generateTotem(NativeImage skin) {
        NativeImage out = new NativeImage(16, 16, true);
        clear(out);

        // Detect if skin has separate left arm/leg textures
        // Modern 64x64 skins may still have empty left limb areas if converted from old format
        boolean hasLeftLimbs = hasNonTransparentPixels(skin, 20, 52, 24, 64);

        // === HEAD (full 8x8 face) → center of totem ===
        copy(skin, out, 8, 8, 8, 8, 4, 1);

        // === BODY (sample 4 rows from 12-row body) → below head ===
        copy(skin, out, 20, 21, 8, 1, 4, 9);
        copy(skin, out, 20, 23, 8, 1, 4, 10);
        copy(skin, out, 20, 29, 8, 1, 4, 11);
        copy(skin, out, 20, 31, 8, 1, 4, 12);

        // === RIGHT LEG (viewed from front = left side of totem) ===
        copy(skin, out, 5, 20, 3, 2, 5, 13);
        copy(skin, out, 6, 31, 2, 1, 6, 15);

        // === LEFT LEG (viewed from front = right side of totem) ===
        if (hasLeftLimbs) {
            copy(skin, out, 20, 52, 3, 2, 8, 13);
            copy(skin, out, 20, 63, 2, 1, 8, 15);
        } else {
            // Mirror from right leg
            copy(skin, out, 7, 20, 3, 2, 8, 13);
            copy(skin, out, 7, 31, 2, 1, 8, 15);
        }

        // === RIGHT ARM (viewed from front = left side of totem) ===
        copy(skin, out, 44, 20, 1, 1, 3, 8);
        copy(skin, out, 45, 20, 1, 1, 3, 9);
        copy(skin, out, 46, 20, 1, 1, 3, 10);
        copy(skin, out, 44, 21, 1, 1, 2, 8);
        copy(skin, out, 45, 21, 1, 1, 2, 9);
        copy(skin, out, 46, 21, 1, 1, 2, 10);
        copy(skin, out, 44, 31, 1, 1, 1, 8);
        copy(skin, out, 45, 31, 1, 1, 1, 9);

        // === LEFT ARM (viewed from front = right side of totem) ===
        if (hasLeftLimbs) {
            copy(skin, out, 39, 52, 1, 1, 12, 8);
            copy(skin, out, 38, 52, 1, 1, 12, 9);
            copy(skin, out, 37, 52, 1, 1, 12, 10);
            copy(skin, out, 39, 53, 1, 1, 13, 8);
            copy(skin, out, 38, 53, 1, 1, 13, 9);
            copy(skin, out, 37, 53, 1, 1, 13, 10);
            copy(skin, out, 37, 63, 1, 1, 14, 8);
            copy(skin, out, 38, 63, 1, 1, 14, 9);
        } else {
            // Mirror from right arm
            copy(skin, out, 44, 20, 1, 1, 12, 8);
            copy(skin, out, 45, 20, 1, 1, 12, 9);
            copy(skin, out, 46, 20, 1, 1, 12, 10);
            copy(skin, out, 44, 21, 1, 1, 13, 8);
            copy(skin, out, 45, 21, 1, 1, 13, 9);
            copy(skin, out, 46, 21, 1, 1, 13, 10);
            copy(skin, out, 46, 31, 1, 1, 14, 8);
            copy(skin, out, 45, 31, 1, 1, 14, 9);
        }

        // === HEAD OVERLAY (hat layer, skip transparent pixels) ===
        copyOverlay(skin, out, 40, 8, 8, 8, 4, 1);
        // Clear corner pixels that stick out beyond the head shape
        clear(out, 4, 1);
        clear(out, 11, 1);

        return out;
    }

    /**
     * Checks if a region of the skin has any non-transparent pixels.
     * Used to detect whether the skin has separate left limb textures.
     */
    private static boolean hasNonTransparentPixels(NativeImage img, int x1, int y1, int x2, int y2) {
        if (img.getHeight() <= 32) return false;
        for (int x = x1; x < x2; x++) {
            for (int y = y1; y < y2; y++) {
                int color = getColor(img, x, y);
                if (((color >> 24) & 0xFF) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void copy(NativeImage src, NativeImage dst, int sx, int sy, int w, int h, int dx, int dy) {
        for (int x = 0; x < w; x++)
            for (int y = 0; y < h; y++)
                setColor(dst, dx + x, dy + y, getColor(src, sx + x, sy + y));
    }

    private static void copyOverlay(NativeImage src, NativeImage dst, int sx, int sy, int w, int h, int dx, int dy) {
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int color = getColor(src, sx + x, sy + y);
                if (((color >> 24) & 0xFF) != 0) {
                    setColor(dst, dx + x, dy + y, color);
                }
            }
        }
    }

    private static void clear(NativeImage img) {
        for (int x = 0; x < img.getWidth(); x++)
            for (int y = 0; y < img.getHeight(); y++)
                setColor(img, x, y, 0x00000000);
    }

    private static void clear(NativeImage img, int x, int y) {
        setColor(img, x, y, 0x00000000);
    }

    private static void setColor(NativeImage img, int x, int y, int color) {
        try {
            SET_COLOR_METHOD.invoke(img, x, y, color);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set pixel color at (" + x + "," + y + ")", e);
        }
    }

    private static int getColor(NativeImage img, int x, int y) {
        try {
            return (int) GET_COLOR_METHOD.invoke(img, x, y);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get pixel color at (" + x + "," + y + ")", e);
        }
    }
}

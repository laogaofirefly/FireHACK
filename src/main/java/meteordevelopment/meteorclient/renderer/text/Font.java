/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.renderer.text;

import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.TextureFormat;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.renderer.MeshBuilder;
import meteordevelopment.meteorclient.renderer.Texture;
import meteordevelopment.meteorclient.utils.render.color.Color;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.*;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

public class Font {
    public final Texture texture;
    private final int height;
    private final float scale;
    private final float ascent;
    private final Int2ObjectOpenHashMap<CharData> charMap = new Int2ObjectOpenHashMap<>();
    // 4096 is required for the CJK glyph range used by HarmonyOS Sans SC.
    // The old 2048 atlas could not contain Chinese characters reliably.
    private static final int size = 4096;

    public Font(ByteBuffer buffer, int height) {
        this.height = height;

        // Initialize font
        STBTTFontinfo fontInfo = STBTTFontinfo.create();
        STBTruetype.stbtt_InitFont(fontInfo, buffer);

        // Allocate buffers
        ByteBuffer bitmap = BufferUtils.createByteBuffer(size * size);
        int cjkCount = CjkCodepoints.CODEPOINTS.length;
        STBTTPackedchar.Buffer[] cdata = {
            STBTTPackedchar.create(95), // Basic Latin
            STBTTPackedchar.create(96), // Latin 1 Supplement
            STBTTPackedchar.create(128), // Latin Extended-A
            STBTTPackedchar.create(144), // Greek and Coptic
            STBTTPackedchar.create(256), // Cyrillic
            STBTTPackedchar.create(cjkCount), // CJK — exact codepoints from translation resources
            STBTTPackedchar.create(1) // infinity symbol
        };

        // Prepare the CJK codepoint array as a direct IntBuffer
        IntBuffer cjkCodepoints = BufferUtils.createIntBuffer(cjkCount);
        cjkCodepoints.put(CjkCodepoints.CODEPOINTS);
        cjkCodepoints.flip();

        // create and initialise packing context
        STBTTPackContext packContext = STBTTPackContext.create();
        if (!STBTruetype.stbtt_PackBegin(packContext, bitmap, size, size, 0, 1)) {
            throw new IllegalStateException("Unable to create font atlas " + size + "x" + size);
        }

        // create the pack range, populate with the specific packing ranges.
        // Use exact codepoint array for CJK (sparse) instead of a continuous range.
        STBTTPackRange.Buffer packRange = STBTTPackRange.create(cdata.length);
        // Keep a wider gap between glyphs so linear filtering cannot bleed into
        // a neighbouring glyph in the atlas.
        packRange.put(STBTTPackRange.create().set(height, 32, null, 95, cdata[0], (byte) 3, (byte) 3));
        packRange.put(STBTTPackRange.create().set(height, 160, null, 96, cdata[1], (byte) 2, (byte) 2));
        packRange.put(STBTTPackRange.create().set(height, 256, null, 128, cdata[2], (byte) 2, (byte) 2));
        packRange.put(STBTTPackRange.create().set(height, 880, null, 144, cdata[3], (byte) 2, (byte) 2));
        packRange.put(STBTTPackRange.create().set(height, 1024, null, 256, cdata[4], (byte) 2, (byte) 2));
        // CJK: pass exact codepoint array so stbtt packs only the needed glyphs
        packRange.put(STBTTPackRange.create().set(height, 0, cjkCodepoints, cjkCount, cdata[5], (byte) 2, (byte) 2));
        packRange.put(STBTTPackRange.create().set(height, 8734, null, 1, cdata[6], (byte) 2, (byte) 2)); // infinity symbol
        packRange.flip();

        // write and finish
        if (!STBTruetype.stbtt_PackFontRanges(packContext, buffer, 0, packRange)) {
            STBTruetype.stbtt_PackEnd(packContext);
            throw new IllegalStateException("Font atlas is too small for HarmonyOS Sans SC glyphs");
        }
        STBTruetype.stbtt_PackEnd(packContext);

        // Create texture object and get font scale
        // Clamp atlas edges: REPEAT lets glyphs sample pixels from the opposite
        // edge of the atlas, which causes flickering and clipped characters.
        texture = new Texture(size, size, TextureFormat.RED8,
            com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
            com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
            FilterMode.LINEAR, FilterMode.LINEAR);
        texture.upload(bitmap);
        scale = STBTruetype.stbtt_ScaleForPixelHeight(fontInfo, height);

        // Get font vertical ascent
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer ascent = stack.mallocInt(1);
            STBTruetype.stbtt_GetFontVMetrics(fontInfo, ascent, null, null);
            this.ascent = ascent.get(0);
        }

        for (int i = 0; i < cdata.length; i++) {
            STBTTPackedchar.Buffer cbuf = cdata[i];
            STBTTPackRange range = packRange.get(i);
            IntBuffer codepointArray = range.array_of_unicode_codepoints();
            int offset = range.first_unicode_codepoint_in_range();

            for (int j = 0; j < cbuf.capacity(); j++) {
                STBTTPackedchar packedChar = cbuf.get(j);

                // Ranges with an explicit codepoint array (CJK) map index -> codepoint
                // through that array; continuous ranges use offset + index.
                int cp = (codepointArray != null) ? codepointArray.get(j) : j + offset;
                if (cp <= 0) continue;

                float ipw = 1f / size; // pixel width and height
                float iph = 1f / size;

                charMap.put(cp, new CharData(
                    packedChar.xoff(),
                    packedChar.yoff(),
                    packedChar.xoff2(),
                    packedChar.yoff2(),
                    packedChar.x0() * ipw,
                    packedChar.y0() * iph,
                    packedChar.x1() * ipw,
                    packedChar.y1() * iph,
                    packedChar.xadvance()
                ));
            }
        }
    }

    public double getWidth(String string, int length) {
        double width = 0;
        for (int i = 0; i < length;) {
            int cp = string.codePointAt(i);
            i += Character.charCount(cp);
            CharData c = charMap.get(cp);
            if (c == null) c = charMap.get(32);

            width += c.xAdvance;
        }

        return width;
    }

    public int getHeight() {
        return height;
    }

    public double render(MeshBuilder mesh, String string, double x, double y, Color color, double scale) {
        y += ascent * this.scale * scale;

        int length = string.length();
        mesh.ensureCapacity(length * 4, length * 6);
        for (int i = 0; i < length;) {
            int cp = string.codePointAt(i);
            i += Character.charCount(cp);
            CharData c = charMap.get(cp);
            if (c == null) c = charMap.get(32);

            mesh.quad(
                mesh.vec2(x + c.x0 * scale, y + c.y0 * scale).vec2(c.u0, c.v0).color(color).next(),
                mesh.vec2(x + c.x0 * scale, y + c.y1 * scale).vec2(c.u0, c.v1).color(color).next(),
                mesh.vec2(x + c.x1 * scale, y + c.y1 * scale).vec2(c.u1, c.v1).color(color).next(),
                mesh.vec2(x + c.x1 * scale, y + c.y0 * scale).vec2(c.u1, c.v0).color(color).next()
            );

            x += c.xAdvance * scale;
        }

        return x;
    }

    private record CharData(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, float xAdvance) {}
}

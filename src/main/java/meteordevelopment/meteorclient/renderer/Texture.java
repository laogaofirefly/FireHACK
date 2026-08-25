/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.renderer;

import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

public class Texture extends AbstractTexture {
    public Texture(int width, int height, TextureFormat format, FilterMode min, FilterMode mag) {
        this(width, height, format, AddressMode.REPEAT, AddressMode.REPEAT, min, mag);
    }

    public Texture(int width, int height, TextureFormat format, AddressMode addressU, AddressMode addressV, FilterMode min, FilterMode mag) {
        glTexture = RenderSystem.getDevice().createTexture("", 15, format, width, height, 1, 1);
        sampler = RenderSystem.getSamplerCache().get(addressU, addressV, min, mag, false);
        glTextureView = RenderSystem.getDevice().createTextureView(glTexture);
    }

    public int getWidth() {
        return getGlTexture().getWidth(0);
    }

    public int getHeight() {
        return getGlTexture().getHeight(0);
    }

    public void upload(byte[] bytes) {
        upload(BufferUtils.createByteBuffer(bytes.length).put(bytes));
    }

    public void upload(ByteBuffer buffer) {
        int w = getWidth();
        int h = getHeight();
        int pixelCount = w * h;

        // NativeImage stores data as int[] (4 bytes per pixel) regardless of format.
        // The font bitmap from stbtt is 1 byte per pixel (RED8/LUMINANCE).
        // The old memCopy(src, image.imageId(), buffer.remaining()) only copied 25%
        // of the pixel data (16 MB byte buffer into 67 MB int[] backing array),
        // leaving 75% of glyphs uninitialised → random missing/corrupt characters.
        // Fix: expand each byte to a full RGBA int so all pixels are uploaded.
        var image = getImage();
        long destAddr = image.imageId();
        for (int i = 0; i < pixelCount; i++) {
            int v = buffer.get(i) & 0xFF;
            MemoryUtil.putInt(destAddr + (long) i * 4, v | (255 << 24)); // RGBA: R=v, G=0, B=0, A=255
        }

        RenderSystem.getDevice().createCommandEncoder().writeToTexture(glTexture, image);

        image.close();
    }

    private @NotNull NativeImage getImage() {
        NativeImage.Format imageFormat = switch (glTexture.getFormat()) {
            case RGBA8 -> NativeImage.Format.RGBA;
            case RED8 -> NativeImage.Format.LUMINANCE;
            default -> throw new IllegalArgumentException();
        };

        // Workaround for writeToTexture(IntBuffer) overload comparing width * height to the size of the int buffer.
        // And since we are working with pixels which are only one byte in size, the sizes don't match
        return new NativeImage(imageFormat, getWidth(), getHeight(), false);
    }

    public static Texture readResource(String path, boolean flipY, FilterMode filter) {
        try (var in = Texture.class.getResourceAsStream(path)) {
            if (in == null) return null;

            var data = TextureUtil.readResource(in).rewind();

            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer width = stack.mallocInt(1);
                IntBuffer height = stack.mallocInt(1);
                IntBuffer comp = stack.mallocInt(1);

                STBImage.stbi_set_flip_vertically_on_load(flipY);
                ByteBuffer image = STBImage.stbi_load_from_memory(data, width, height, comp, 4);

                var texture = new Texture(width.get(0), height.get(0), TextureFormat.RGBA8, filter, filter);
                texture.upload(image);

                STBImage.stbi_image_free(image);
                STBImage.stbi_set_flip_vertically_on_load(false);

                return texture;
            }
        } catch (IOException e) {
            return null;
        }
    }
}

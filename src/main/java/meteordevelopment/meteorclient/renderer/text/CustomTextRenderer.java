package meteordevelopment.meteorclient.renderer.text;

import meteordevelopment.meteorclient.renderer.MeshBuilder;
import meteordevelopment.meteorclient.renderer.MeshRenderer;
import meteordevelopment.meteorclient.renderer.MeteorRenderPipelines;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.ByteBuffer;

public class CustomTextRenderer implements TextRenderer {
    public static final Color SHADOW_COLOR = new Color(60, 60, 60, 180);

    private final MeshBuilder mesh = new MeshBuilder(MeteorRenderPipelines.UI_TEXT);

    public final FontFace fontFace;

    /** Single font instance — no multi-atlas switching, no flickering. */
    private final Font font;
    private Font currentFont;

    private boolean building;
    private boolean scaleOnly;
    private double fontScale = 1;
    private double scale = 1;

    public CustomTextRenderer(FontFace fontFace) throws IOException {
        this.fontFace = fontFace;

        ByteBuffer buffer = fontFace.readToDirectByteBuffer();

        // Single font size at 27px. All scaling is done by the renderer,
        // avoiding atlas switches that cause flickering.
        font = new Font(buffer, 27);
    }

    @Override
    public void setAlpha(double a) {
        mesh.alpha = a;
    }

    @Override
    public void begin(double scale, boolean scaleOnly, boolean big) {
        if (building) throw new RuntimeException("CustomTextRenderer.begin() called twice");

        if (!scaleOnly) mesh.begin();

        // Always use the same single font — no atlas switching.
        currentFont = font;

        this.building = true;
        this.scaleOnly = scaleOnly;

        this.fontScale = 1.0;
        this.scale = scale;
    }

    @Override
    public double getWidth(String text, int length, boolean shadow) {
        if (text.isEmpty()) return 0;

        Font f = building ? currentFont : font;
        return (f.getWidth(text, length) + (shadow ? 1 : 0)) * scale / 1.5;
    }

    @Override
    public double getHeight(boolean shadow) {
        Font f = building ? currentFont : font;
        return (f.getHeight() + 1 + (shadow ? 1 : 0)) * scale / 1.5;
    }

    @Override
    public double render(String text, double x, double y, Color color, boolean shadow) {
        boolean wasBuilding = building;
        if (!wasBuilding) begin();

        double width;
        if (shadow) {
            int preShadowA = SHADOW_COLOR.a;
            SHADOW_COLOR.a = (int) (color.a / 255.0 * preShadowA);

            width = currentFont.render(mesh, text, x + fontScale * scale / 1.5, y + fontScale * scale / 1.5, SHADOW_COLOR, scale / 1.5);
            currentFont.render(mesh, text, x, y, color, scale / 1.5);

            SHADOW_COLOR.a = preShadowA;
        }
        else {
            width = currentFont.render(mesh, text, x, y, color, scale / 1.5);
        }

        if (!wasBuilding) end();
        return width;
    }

    @Override
    public boolean isBuilding() {
        return building;
    }

    @Override
    public void end() {
        if (!building) throw new RuntimeException("CustomTextRenderer.end() called without calling begin()");

        if (!scaleOnly) {
            mesh.end();

            MeshRenderer.begin()
                .attachments(MinecraftClient.getInstance().getFramebuffer())
                .pipeline(MeteorRenderPipelines.UI_TEXT)
                .mesh(mesh)
                .sampler("u_Texture", currentFont.texture.getGlTextureView(), currentFont.texture.getSampler())
                .end();
        }

        building = false;
        scale = 1;
    }

    public void destroy() {
        font.texture.close();
    }
}
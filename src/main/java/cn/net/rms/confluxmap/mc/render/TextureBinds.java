package cn.net.rms.confluxmap.mc.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * The texture binding this mod's GUI-space batches sample. Before 1.21.5 that is the live
 * {@code RenderSystem.setShaderTexture} slot; from 1.21.8 on, recorded GUI elements carry
 * their own TextureSetup, so the bound view also has to survive here until the batch is
 * finished - {@link Mesh} reads it back through {@link #guiTextureSetup}.
 */
public final class TextureBinds {
    private static GpuTextureView boundTexture;
    private static GpuSampler boundSampler;

    private TextureBinds() {
    }

    /** Selects the flat position+texture shader and standard alpha blending, for textured GUI quads. */
    public static void beginTexturedQuads() {
    }

    public static void bindTexture(final int glId) {
    }

    public static void bindTexture(final GpuTextureView texture) {
        bindTexture(
            texture,
            RenderSystem.getSamplerCache().getSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST,
                FilterMode.NEAREST,
                false
            )
        );
    }
    
    public static void bindTexture(final GpuTextureView texture, final GpuSampler sampler) {
        boundTexture = texture;
        boundSampler = sampler;
    }
    
    static GpuTextureView boundTexture() {
        return boundTexture;
    }
    
    static GpuSampler boundSampler() {
        return boundSampler;
    }

    static TextureSetup guiTextureSetup(final boolean textured) {
        if (!textured || boundTexture == null) {
            return TextureSetup.noTexture();
        }
        return TextureSetup.singleTexture(boundTexture, boundSampler);
    }

    /**
     * Binds an already-vanilla-managed texture (player skin, mob texture, etc.) by identifier.
     *
     * <p>Core shaders sample what {@code RenderSystem.setShaderTexture} points at, not the
     * legacy {@code TextureManager} bind - using the latter leaves unit 0 on whatever was
     * drawn last (map tiles), which is exactly the "icons show dark terrain" bug.
     */
    public static void bindTexture(final Minecraft client, final Identifier id) {
        final var texture = client.getTextureManager().getTexture(id);
        bindTexture(texture.getTextureView(), texture.getSampler());
    }
}
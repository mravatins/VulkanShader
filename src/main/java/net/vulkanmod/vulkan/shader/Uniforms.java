package net.vulkanmod.vulkan.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.vulkan.util.MappedBuffer;

import java.util.function.Supplier;

public class Uniforms {

    public static Object2ReferenceOpenHashMap<String, Supplier<Integer>> vec1i_uniformMap = new Object2ReferenceOpenHashMap<>();

    public static Object2ReferenceOpenHashMap<String, Supplier<Float>> vec1f_uniformMap = new Object2ReferenceOpenHashMap<>();
    public static Object2ReferenceOpenHashMap<String, Supplier<MappedBuffer>> vec2f_uniformMap = new Object2ReferenceOpenHashMap<>();
    public static Object2ReferenceOpenHashMap<String, Supplier<MappedBuffer>> vec3f_uniformMap = new Object2ReferenceOpenHashMap<>();
    public static Object2ReferenceOpenHashMap<String, Supplier<MappedBuffer>> vec4f_uniformMap = new Object2ReferenceOpenHashMap<>();

    public static Object2ReferenceOpenHashMap<String, Supplier<MappedBuffer>> mat4f_uniformMap = new Object2ReferenceOpenHashMap<>();

    public static void setupDefaultUniforms() {

        // Mat4
        mat4f_uniformMap.put("ModelViewMat", VRenderSystem::getModelViewMatrix);
        mat4f_uniformMap.put("ProjMat", VRenderSystem::getProjectionMatrix);
        mat4f_uniformMap.put("MVP", VRenderSystem::getMVP);
        mat4f_uniformMap.put("TextureMat", VRenderSystem::getTextureMatrix);
        mat4f_uniformMap.put("LightSpaceMat", VRenderSystem::getLightSpaceMatrix);
        mat4f_uniformMap.put("LightSpaceViewMat", VRenderSystem::getLightSpaceViewMatrix);
        mat4f_uniformMap.put("InvProjMat", VRenderSystem::getInverseProjectionMatrix);

        // Vec1i
        vec1i_uniformMap.put("EndPortalLayers", () -> 15);

        // Vec1
        vec1f_uniformMap.put("FogStart", () -> VRenderSystem.getFogData().renderDistanceStart);
        vec1f_uniformMap.put("FogEnd", () -> VRenderSystem.getFogData().renderDistanceEnd);
        vec1f_uniformMap.put("FogEnvironmentalStart", () -> VRenderSystem.getFogData().environmentalStart);
        vec1f_uniformMap.put("FogEnvironmentalEnd", () -> VRenderSystem.getFogData().environmentalEnd);
        vec1f_uniformMap.put("FogRenderDistanceStart", () -> VRenderSystem.getFogData().renderDistanceStart);
        vec1f_uniformMap.put("FogRenderDistanceEnd", () -> VRenderSystem.getFogData().renderDistanceEnd);
        vec1f_uniformMap.put("FogSkyEnd", () -> VRenderSystem.getFogData().skyEnd);
        vec1f_uniformMap.put("FogCloudsEnd", () -> VRenderSystem.getFogData().cloudEnd);
        vec1f_uniformMap.put("LineWidth", RenderSystem::getShaderLineWidth);
        vec1f_uniformMap.put("AlphaCutout", () -> VRenderSystem.alphaCutout);
        vec1f_uniformMap.put("Time", VRenderSystem::getTime);

        // Vec2
        vec2f_uniformMap.put("ScreenSize", VRenderSystem::getScreenSize);

        // Vec3
        vec3f_uniformMap.put("ModelOffset", () -> VRenderSystem.modelOffset);
        vec3f_uniformMap.put("ChunkOffset", () -> VRenderSystem.modelOffset);
        vec3f_uniformMap.put("CamPos", VRenderSystem::getCameraWorldPos);

        // Vec4
        vec4f_uniformMap.put("ColorModulator", VRenderSystem::getShaderColor);
        vec4f_uniformMap.put("FogColor", VRenderSystem::getShaderFogColor);
        vec4f_uniformMap.put("Light0_Direction", () -> VRenderSystem.lightDirection0);
        vec4f_uniformMap.put("Light1_Direction", () -> VRenderSystem.lightDirection1);

    }

    public static Supplier<MappedBuffer> getUniformSupplier(String type, String name) {
        return switch (type) {
            case "mat4" -> Uniforms.mat4f_uniformMap.get(name);
            case "vec4" -> Uniforms.vec4f_uniformMap.get(name);
            case "vec3" -> Uniforms.vec3f_uniformMap.get(name);
            case "vec2" -> Uniforms.vec2f_uniformMap.get(name);

            default -> null;
        };
    }
}

package net.vulkanmod.render;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.vulkanmod.render.chunk.build.thread.ThreadBuilderPack;
import net.vulkanmod.render.shader.ShaderLoadUtil;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;

import java.util.function.Function;

public abstract class PipelineManager {
    public static VertexFormat terrainVertexFormat;

    public static void setTerrainVertexFormat(VertexFormat format) {
        terrainVertexFormat = format;
    }

    static GraphicsPipeline
            terrainShader, terrainShaderEarlyZ, waterShader,
            fastBlitPipeline, cloudsPipeline,
            shadowTerrainShader, shadowEntityShader;

    private static Function<TerrainRenderType, GraphicsPipeline> shaderGetter;

    public static void init() {
        setTerrainVertexFormat(CustomVertexFormat.COMPRESSED_TERRAIN);
        createBasicPipelines();
        setDefaultShader();
        ThreadBuilderPack.defaultTerrainBuilderConstructor();
    }

    public static void setDefaultShader() {
        setShaderGetter(
                renderType -> renderType == TerrainRenderType.TRANSLUCENT ? waterShader : terrainShader);
    }

    private static void createBasicPipelines() {
        terrainShaderEarlyZ = createPipeline("terrain_earlyZ", terrainVertexFormat);
        terrainShader = createPipeline("terrain", terrainVertexFormat);
        waterShader = createPipeline("terrain_water", terrainVertexFormat);
        fastBlitPipeline = createPipeline("blit", CustomVertexFormat.NONE);
        cloudsPipeline = createPipeline("clouds", DefaultVertexFormat.POSITION_COLOR);
        shadowTerrainShader = createPipeline("shadow", terrainVertexFormat);
        shadowEntityShader = createPipeline("entity_shadow", DefaultVertexFormat.POSITION);
    }

    private static GraphicsPipeline createPipeline(String configName, VertexFormat vertexFormat) {
        Pipeline.Builder pipelineBuilder = new Pipeline.Builder(vertexFormat, configName);

        final String path = ShaderLoadUtil.resolveShaderPath("basic");
        JsonObject config = ShaderLoadUtil.getJsonConfig(path, configName);
        pipelineBuilder.parseBindings(config);

        ShaderLoadUtil.loadShaders(pipelineBuilder, config, configName, path);

        var pipeline = pipelineBuilder.createGraphicsPipeline();

        for (var buffer : pipeline.getBuffers()) {
            buffer.setUseGlobalBuffer(true);
        }

        return pipeline;
    }

    public static GraphicsPipeline getTerrainShader(TerrainRenderType renderType) {
        return shaderGetter.apply(renderType);
    }

    public static void setShaderGetter(Function<TerrainRenderType, GraphicsPipeline> consumer) {
        shaderGetter = consumer;
    }

    public static GraphicsPipeline getTerrainDirectShader(RenderType renderType) {
        return terrainShader;
    }

    public static GraphicsPipeline getTerrainIndirectShader(RenderType renderType) {
        return terrainShaderEarlyZ;
    }

    public static GraphicsPipeline getFastBlitPipeline() {
        return fastBlitPipeline;
    }

    public static GraphicsPipeline getCloudsPipeline() {
        return cloudsPipeline;
    }

    public static GraphicsPipeline getShadowTerrainShader() {
        return shadowTerrainShader;
    }

    public static GraphicsPipeline getShadowEntityShader() {
        return shadowEntityShader;
    }

    public static void destroyPipelines() {
        terrainShaderEarlyZ.cleanUp();
        terrainShader.cleanUp();
        waterShader.cleanUp();
        fastBlitPipeline.cleanUp();
        cloudsPipeline.cleanUp();
        shadowTerrainShader.cleanUp();
        shadowEntityShader.cleanUp();
    }
}

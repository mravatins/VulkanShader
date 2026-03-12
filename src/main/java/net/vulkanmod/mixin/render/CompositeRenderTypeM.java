package net.vulkanmod.mixin.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.vulkanmod.render.engine.*;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.OptionalDouble;
import java.util.OptionalInt;

@Mixin(RenderType.CompositeRenderType.class)
public abstract class CompositeRenderTypeM {

    @Shadow @Final private RenderType.CompositeState state;
    @Shadow @Final private RenderPipeline renderPipeline;

    // TODO
    /**
     * @author
     * @reason
     */
    @Overwrite
    public void draw(MeshData meshData) {
        ((RenderType.CompositeRenderType)(Object)(this)).setupRenderState();
        GpuBufferSlice gpuBufferSlice = RenderSystem.getDynamicUniforms()
                                                    .writeTransform(
                                                            RenderSystem.getModelViewMatrix(),
                                                            new Vector4f(1.0F, 1.0F, 1.0F, 1.0F),
                                                            new Vector3f(),
                                                            RenderSystem.getTextureMatrix(),
                                                            RenderSystem.getShaderLineWidth()
                                                    );
        MeshData var3 = meshData;

        try {
            GpuBuffer gpuBuffer = this.renderPipeline.getVertexFormat().uploadImmediateVertexBuffer(meshData.vertexBuffer());
            GpuBuffer gpuBuffer2;
            VertexFormat.IndexType indexType;
            if (meshData.indexBuffer() == null) {
                RenderSystem.AutoStorageIndexBuffer autoStorageIndexBuffer = RenderSystem.getSequentialBuffer(meshData.drawState().mode());
                gpuBuffer2 = autoStorageIndexBuffer.getBuffer(meshData.drawState().indexCount());
                indexType = autoStorageIndexBuffer.type();
            } else {
                gpuBuffer2 = this.renderPipeline.getVertexFormat().uploadImmediateIndexBuffer(meshData.indexBuffer());
                indexType = meshData.drawState().indexType();
            }

            RenderTarget renderTarget = ((CompositeStateAccessor)(Object)this.state).getOutputState().getRenderTarget();
            GpuTextureView gpuTextureView = RenderSystem.outputColorTextureOverride != null
                    ? RenderSystem.outputColorTextureOverride
                    : renderTarget.getColorTextureView();
            GpuTextureView gpuTextureView2 = renderTarget.useDepth
                    ? (RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : renderTarget.getDepthTextureView())
                    : null;

            try (RenderPass renderPass = RenderSystem.getDevice()
                                                     .createCommandEncoder()
                                                     .createRenderPass(() -> "Immediate draw for " +
                                                                             ((RenderType.CompositeRenderType) (Object) (this)).getName(),
                                                                       gpuTextureView, OptionalInt.empty(), gpuTextureView2, OptionalDouble.empty())) {
                renderPass.setPipeline(this.renderPipeline);
                ScissorState scissorState = RenderSystem.getScissorStateForRenderTypeDraws();
                if (scissorState.enabled()) {
                    renderPass.enableScissor(scissorState.x(), scissorState.y(), scissorState.width(), scissorState.height());
                }

                RenderSystem.bindDefaultUniforms(renderPass);
                renderPass.setUniform("DynamicTransforms", gpuBufferSlice);
                renderPass.setVertexBuffer(0, gpuBuffer);

                for (int i = 0; i < 12; i++) {
                    GpuTextureView gpuTextureView3 = RenderSystem.getShaderTexture(i);
                    if (gpuTextureView3 != null) {
                        renderPass.bindSampler("Sampler" + i, gpuTextureView3);

                        VkGpuTexture vkGpuTexture = (VkGpuTexture) gpuTextureView3.texture();
                        VTextureSelector.bindTexture(i, vkGpuTexture.getVulkanImage());
                    }
                }

                // Always bind the shadow map to slot 3 so entity shaders that declare
                // ShadowSampler (layout binding=3) can perform terrain-shadow lookups.
                net.vulkanmod.vulkan.texture.VulkanImage shadowMap =
                        net.vulkanmod.vulkan.Renderer.getInstance().getShadowPass().getShadowMap();
                VTextureSelector.bindTexture(3, shadowMap);

                VRenderSystem.applyModelViewMatrix(RenderSystem.getModelViewMatrix());
                VRenderSystem.calculateMVP();

                renderPass.setIndexBuffer(gpuBuffer2, indexType);

                VkCommandEncoder commandEncoder = (VkCommandEncoder) RenderSystem.getDevice().createCommandEncoder();
                commandEncoder.trySetup((VkRenderPass) renderPass);

                Renderer.getDrawer().draw(meshData.vertexBuffer(), meshData.indexBuffer(), meshData.drawState().mode(), meshData.drawState().format(), meshData.drawState().vertexCount());
            }
        } catch (Throwable var17) {
            if (meshData != null) {
                try {
                    var3.close();
                } catch (Throwable var14) {
                    var17.addSuppressed(var14);
                }
            }

            throw var17;
        }

        if (meshData != null) {
            meshData.close();
        }

        ((RenderType.CompositeRenderType)(Object)(this)).clearRenderState();
    }

}

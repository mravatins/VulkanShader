package net.vulkanmod.mixin.chunk;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.profiling.Profiler;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.pass.ShadowPass;
import org.lwjgl.system.MemoryStack;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.SortedSet;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow @Final private Long2ObjectMap<SortedSet<BlockDestructionProgress>> destructionProgress;

    @Unique private WorldRenderer worldRenderer;

    @Unique double camX, camY, camZ;
    @Unique Matrix4f modelView, projection;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void init(Minecraft minecraft, EntityRenderDispatcher entityRenderDispatcher,
                      BlockEntityRenderDispatcher blockEntityRenderDispatcher, RenderBuffers renderBuffers,
                      LevelRenderState levelRenderState, FeatureRenderDispatcher featureRenderDispatcher,
                      CallbackInfo ci) {
        this.worldRenderer = WorldRenderer.init(entityRenderDispatcher, blockEntityRenderDispatcher, renderBuffers, levelRenderState, featureRenderDispatcher);
    }

    @Inject(method = "setLevel", at = @At("RETURN"))
    private void setLevel(ClientLevel clientLevel, CallbackInfo ci) {
        this.worldRenderer.setLevel(clientLevel);
    }

    @Inject(method = "allChanged", at = @At("RETURN"))
    private void onAllChanged(CallbackInfo ci) {
        this.worldRenderer.allChanged();
    }

    @Inject(method = "extractVisibleBlockEntities", at = @At("HEAD"), cancellable = true)
    private void onExtractVisibleBlockEntities(Camera camera, float partialTick, LevelRenderState levelRenderState,
                                               CallbackInfo ci) {
        this.worldRenderer.setPartialTick(partialTick);

        ci.cancel();
    }

    @Inject(method = "submitBlockEntities", at = @At(value = "RETURN"), cancellable = true)
    private void onSubmitBlockEntities(PoseStack poseStack, LevelRenderState levelRenderState,
                                     SubmitNodeStorage submitNodeStorage, CallbackInfo ci) {
        this.worldRenderer.renderBlockEntities(poseStack, levelRenderState, submitNodeStorage, this.destructionProgress);

        ci.cancel();
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    private void cullTerrain(Camera camera, Frustum frustum, boolean spectator) {
        // TODO: port capture frustum
        this.worldRenderer.setupRenderer(camera, frustum, false, spectator);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    public boolean isSectionCompiled(BlockPos blockPos) {
        return this.worldRenderer.isSectionCompiled(blockPos);
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void updateMatrices(GraphicsResourceAllocator graphicsResourceAllocator, DeltaTracker deltaTracker,
                                boolean bl, Camera camera, Matrix4f modelView, Matrix4f projection, Matrix4f matrix4f,
                                GpuBufferSlice gpuBufferSlice, Vector4f vector4f, boolean bl2, CallbackInfo ci) {
        this.modelView = modelView;
        this.projection = projection;
    }

    @Overwrite
    private ChunkSectionsToRender prepareChunkRenders(Matrix4fc matrix4fc, double camX, double camY, double camZ) {
        this.camX = camX;
        this.camY = camY;
        this.camZ = camZ;

        return null;
    }

    @Redirect(method = "method_62214", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;)V"))
    private void renderSectionLayer(ChunkSectionsToRender instance, ChunkSectionLayerGroup chunkSectionLayerGroup) {
        if (chunkSectionLayerGroup == ChunkSectionLayerGroup.OPAQUE) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ShadowPass shadowPass = Renderer.getInstance().getShadowPass();
                shadowPass.begin(Renderer.getCommandBuffer(), stack);
                this.worldRenderer.renderShadowTerrain(camX, camY, camZ);
                this.worldRenderer.renderEntityShadows(camX, camY, camZ);
                shadowPass.end(Renderer.getCommandBuffer());
            }

            Renderer.getInstance().getMainPass().rebindMainTarget();

            Profiler profiler = Profiler.getMainProfiler();
            profiler.push("Opaque_terrain");

            this.worldRenderer.renderSectionLayer(TerrainRenderType.SOLID, camX, camY, camZ, modelView, projection);
            this.worldRenderer.renderSectionLayer(TerrainRenderType.CUTOUT, camX, camY, camZ, modelView, projection);
            this.worldRenderer.renderSectionLayer(TerrainRenderType.CUTOUT_MIPPED, camX, camY, camZ, modelView, projection);
        }
        else if (chunkSectionLayerGroup == ChunkSectionLayerGroup.TRANSLUCENT) {
            Profiler profiler = Profiler.getMainProfiler();
            profiler.pop();
            profiler.push("Translucent_terrain");

            this.worldRenderer.renderSectionLayer(TerrainRenderType.TRANSLUCENT, camX, camY, camZ, modelView, projection);

            profiler.pop();
        }

    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    public void onChunkReadyToRender(ChunkPos chunkPos) {
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    private void setSectionDirty(int x, int y, int z, boolean flag) {
        this.worldRenderer.setSectionDirty(x, y, z, flag);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    public String getSectionStatistics() {
        return this.worldRenderer.getChunkStatistics();
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    public boolean hasRenderedAllSections() {
        return !this.worldRenderer.graphNeedsUpdate() && this.worldRenderer.getTaskDispatcher().isIdle();
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    public int countRenderedSections() {
        return this.worldRenderer.getVisibleSectionsCount();
    }

    @Redirect(method = "addWeatherPass", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;getDepthFar()F"))
    private float getRenderDistanceZFar(GameRenderer instance) {
        return instance.getRenderDistance() * 4F;
    }

}

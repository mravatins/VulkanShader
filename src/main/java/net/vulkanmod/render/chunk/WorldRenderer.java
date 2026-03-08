package net.vulkanmod.render.chunk;

import com.google.common.collect.Sets;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.Zone;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.PipelineManager;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.build.RenderRegionBuilder;
import net.vulkanmod.render.chunk.build.task.TaskDispatcher;
import net.vulkanmod.render.chunk.build.task.ChunkTask;
import net.vulkanmod.render.chunk.graph.SectionGraph;
import net.vulkanmod.render.profiling.BuildTimeProfiler;
import net.vulkanmod.render.profiling.Profiler;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import net.vulkanmod.vulkan.memory.buffer.IndirectBuffer;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import org.lwjgl.system.MemoryUtil;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.*;

public class WorldRenderer {
    private static WorldRenderer INSTANCE;

    public static WorldRenderer init(EntityRenderDispatcher entityRenderDispatcher,
                                     BlockEntityRenderDispatcher blockEntityRenderDispatcher,
                                     RenderBuffers renderBuffers,
                                     LevelRenderState levelRenderState,
                                     FeatureRenderDispatcher featureRenderDispatcher) {
        if (INSTANCE != null) {
            return INSTANCE;
        }
        else {
            return INSTANCE = new WorldRenderer(entityRenderDispatcher, blockEntityRenderDispatcher, renderBuffers, levelRenderState, featureRenderDispatcher);
        }
    }

    private final Minecraft minecraft;
    private ClientLevel level;
    private int renderDistance;
    private final RenderBuffers renderBuffers;

    private final EntityRenderDispatcher entityRenderDispatcher;
    private final BlockEntityRenderDispatcher blockEntityRenderDispatcher;
    private final LevelRenderState levelRenderState;
    private final FeatureRenderDispatcher featureRenderDispatcher;

    private float partialTick;
    private Vec3 cameraPos;
    private int lastCameraSectionX;
    private int lastCameraSectionY;
    private int lastCameraSectionZ;
    private float lastCameraX;
    private float lastCameraY;
    private float lastCameraZ;
    private float lastCamRotX;
    private float lastCamRotY;

    private SectionGrid sectionGrid;

    private SectionGraph sectionGraph;
    private boolean graphNeedsUpdate;

    private final Set<BlockEntity> globalBlockEntities = Sets.newHashSet();

    private final TaskDispatcher taskDispatcher;

    private double xTransparentOld;
    private double yTransparentOld;
    private double zTransparentOld;

    IndirectBuffer[] indirectBuffers;

    // Pre-allocated CPU buffer for entity shadow AABB geometry.
    // 6 faces × 4 verts × 12 bytes = 288 bytes per entity; 4096 entities max ≈ 1 MB.
    private static final java.nio.ByteBuffer ENTITY_SHADOW_BUF = MemoryUtil.memAlloc(4096 * 288);

    public RenderRegionBuilder renderRegionCache;

    private final List<Runnable> onAllChangedCallbacks = new ObjectArrayList<>();

    private WorldRenderer(EntityRenderDispatcher entityRenderDispatcher,
                          BlockEntityRenderDispatcher blockEntityRenderDispatcher,
                          RenderBuffers renderBuffers,
                          LevelRenderState levelRenderState,
                          FeatureRenderDispatcher featureRenderDispatcher)
    {
        this.minecraft = Minecraft.getInstance();
        this.renderBuffers = renderBuffers;
        this.entityRenderDispatcher = entityRenderDispatcher;
        this.blockEntityRenderDispatcher = blockEntityRenderDispatcher;
        this.levelRenderState = levelRenderState;
        this.featureRenderDispatcher = featureRenderDispatcher;

        this.renderRegionCache = new RenderRegionBuilder();
        this.taskDispatcher = new TaskDispatcher();

        ChunkTask.setTaskDispatcher(this.taskDispatcher);
        allocateIndirectBuffers();
        TerrainRenderType.updateMapping();

        Renderer.getInstance().addOnResizeCallback(() -> {
            if (this.indirectBuffers.length != Renderer.getFramesNum())
                allocateIndirectBuffers();
        });
    }

    private void allocateIndirectBuffers() {
        if (this.indirectBuffers != null)
            Arrays.stream(this.indirectBuffers).forEach(Buffer::scheduleFree);

        this.indirectBuffers = new IndirectBuffer[Renderer.getFramesNum()];

        for (int i = 0; i < this.indirectBuffers.length; ++i) {
            this.indirectBuffers[i] = new IndirectBuffer(1000000, MemoryTypes.HOST_MEM);
        }
    }

    private void benchCallback() {
        BuildTimeProfiler.runBench(this.graphNeedsUpdate || !this.taskDispatcher.isIdle());
    }

    public void setupRenderer(Camera camera, Frustum frustum, boolean isCapturedFrustum, boolean spectator) {
        Profiler profiler = Profiler.getMainProfiler();
        profiler.push("Setup_Renderer");

        ProfilerFiller mcProfiler = net.minecraft.util.profiling.Profiler.get();

        benchCallback();

        this.cameraPos = camera.getPosition();
        if (this.minecraft.options.getEffectiveRenderDistance() != this.renderDistance) {
            this.allChanged();
        }

        mcProfiler.push("camera");
        float cameraX = (float) cameraPos.x();
        float cameraY = (float) cameraPos.y();
        float cameraZ = (float) cameraPos.z();
        int sectionX = SectionPos.posToSectionCoord(cameraX);
        int sectionY = SectionPos.posToSectionCoord(cameraY);
        int sectionZ = SectionPos.posToSectionCoord(cameraZ);

        profiler.push("reposition");
        if (this.lastCameraSectionX != sectionX || this.lastCameraSectionY != sectionY || this.lastCameraSectionZ != sectionZ) {
            this.lastCameraSectionX = sectionX;
            this.lastCameraSectionY = sectionY;
            this.lastCameraSectionZ = sectionZ;
            this.sectionGrid.repositionCamera(cameraX, cameraZ);
        }
        profiler.pop();

        double entityDistanceScaling = this.minecraft.options.entityDistanceScaling().get();
        Entity.setViewScale(Mth.clamp((double) this.renderDistance / 8.0D, 1.0D, 2.5D) * entityDistanceScaling);

        mcProfiler.popPush("cull");

        mcProfiler.popPush("update");

        boolean cameraMoved = false;
        float d_xRot = Math.abs(camera.getXRot() - this.lastCamRotX);
        float d_yRot = Math.abs(camera.getYRot() - this.lastCamRotY);
        cameraMoved |= d_xRot > 2.0f || d_yRot > 2.0f;

        cameraMoved |= cameraX != this.lastCameraX || cameraY != this.lastCameraY || cameraZ != this.lastCameraZ;
        this.graphNeedsUpdate |= cameraMoved;

        if (!isCapturedFrustum) {
            //Debug
//            this.graphNeedsUpdate = true;

            if (this.graphNeedsUpdate()) {
                this.graphNeedsUpdate = false;
                this.lastCameraX = cameraX;
                this.lastCameraY = cameraY;
                this.lastCameraZ = cameraZ;
                this.lastCamRotX = camera.getXRot();
                this.lastCamRotY = camera.getYRot();

                this.sectionGraph.update(camera, frustum, spectator);
            }
        }

        this.indirectBuffers[Renderer.getCurrentFrame()].reset();

        mcProfiler.pop();
        profiler.pop();
    }

    public void uploadSections() {
        ProfilerFiller mcProfiler = net.minecraft.util.profiling.Profiler.get();
        mcProfiler.push("upload");

        Profiler profiler = Profiler.getMainProfiler();
        profiler.push("Uploads");

        try {
            if (this.taskDispatcher.updateSections())
                this.graphNeedsUpdate = true;
        } catch (Exception e) {
            Initializer.LOGGER.error(e.getMessage());
            allChanged();
        }

        profiler.pop();

        mcProfiler.pop();
    }

    public boolean isSectionCompiled(BlockPos blockPos) {
        RenderSection renderSection = this.sectionGrid.getSectionAtBlockPos(blockPos);
        return renderSection != null && renderSection.isCompiled();
    }

    public void allChanged() {
        if (this.level != null) {
            this.level.clearTintCaches();

            this.renderRegionCache.clear();
            this.taskDispatcher.createThreads(Initializer.CONFIG.builderThreads);

            this.graphNeedsUpdate = true;

            this.renderDistance = this.minecraft.options.getEffectiveRenderDistance();
            if (this.sectionGrid != null) {
                this.sectionGrid.freeAllBuffers();
            }

            this.taskDispatcher.clearBatchQueue();
            synchronized (this.globalBlockEntities) {
                this.globalBlockEntities.clear();
            }

            this.sectionGrid = new SectionGrid(this.level, this.renderDistance);
            this.sectionGraph = new SectionGraph(this.level, this.sectionGrid, this.taskDispatcher);

            this.onAllChangedCallbacks.forEach(Runnable::run);

            Entity entity = this.minecraft.getCameraEntity();
            if (entity != null) {
                this.sectionGrid.repositionCamera(entity.getX(), entity.getZ());
            }

        }
    }

    public void setLevel(@Nullable ClientLevel level) {
        this.lastCameraX = Float.MIN_VALUE;
        this.lastCameraY = Float.MIN_VALUE;
        this.lastCameraZ = Float.MIN_VALUE;
        this.lastCameraSectionX = Integer.MIN_VALUE;
        this.lastCameraSectionY = Integer.MIN_VALUE;
        this.lastCameraSectionZ = Integer.MIN_VALUE;

//        this.entityRenderDispatcher.setLevel(level);
        this.level = level;
        ChunkStatusMap.createInstance(renderDistance);
        if (level != null) {
            this.allChanged();
        } else {
            if (this.sectionGrid != null) {
                this.sectionGrid.freeAllBuffers();
                this.sectionGrid = null;
            }

            this.taskDispatcher.stopThreads();

            this.graphNeedsUpdate = true;
        }

    }

    public void addOnAllChangedCallback(Runnable runnable) {
        this.onAllChangedCallbacks.add(runnable);
    }

    public void clearOnAllChangedCallbacks() {
        this.onAllChangedCallbacks.clear();
    }

    public void renderShadowTerrain(double camX, double camY, double camZ) {
        Renderer renderer = Renderer.getInstance();

        VRenderSystem.enableCull();
        VRenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
        GlStateManager._enableDepthTest();
        GlStateManager._depthMask(true);

        GraphicsPipeline pipeline = PipelineManager.getShadowTerrainShader();
        renderer.bindGraphicsPipeline(pipeline);

        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        AbstractTexture blockAtlasTexture = textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS);
        RenderSystem.setShaderTexture(0, blockAtlasTexture.getTextureView());
        VTextureSelector.bindShaderTextures(pipeline);

        IndexBuffer indexBuffer = Renderer.getDrawer().getQuadsIndexBuffer().getIndexBuffer();
        Renderer.getDrawer().bindIndexBuffer(Renderer.getCommandBuffer(), indexBuffer, indexBuffer.indexType.value);

        // Populate shadow section queues from ALL compiled sections (not just camera-visible),
        // so shadow map is stable when the camera turns.
        ChunkAreaManager chunkAreaManager = getChunkAreaManager();
        if (chunkAreaManager != null) {
            for (ChunkArea area : chunkAreaManager.chunkAreasArr) {
                area.shadowSectionQueue.clear();
            }
            if (this.sectionGrid != null && this.sectionGrid.sections != null) {
                for (RenderSection section : this.sectionGrid.sections) {
                    if (section == null || section.isCompletelyEmpty()) continue;
                    ChunkArea area = section.getChunkArea();
                    if (area != null) {
                        area.shadowSectionQueue.add(section);
                    }
                }
            }
        }

        // Use same render type filtering as renderSectionLayer — solid data is remapped to CUTOUT_MIPPED
        Set<TerrainRenderType> allowedRenderTypes = Initializer.CONFIG.uniqueOpaqueLayer
                ? TerrainRenderType.COMPACT_RENDER_TYPES
                : TerrainRenderType.SEMI_COMPACT_RENDER_TYPES;

        for (TerrainRenderType renderType : TerrainRenderType.VALUES) {
            if (renderType == TerrainRenderType.TRANSLUCENT || renderType == TerrainRenderType.TRIPWIRE) continue;
            if (!allowedRenderTypes.contains(renderType)) continue;

            renderType.setCutoutUniform();

            if (chunkAreaManager != null) {
                for (ChunkArea chunkArea : chunkAreaManager.chunkAreasArr) {
                    var queue = chunkArea.shadowSectionQueue;
                    DrawBuffers drawBuffers = chunkArea.drawBuffers;

                    renderer.uploadAndBindUBOs(pipeline);
                    if (drawBuffers.getAreaBuffer(renderType) != null && queue.size() > 0) {
                        drawBuffers.bindBuffers(Renderer.getCommandBuffer(), pipeline, renderType, camX, camY, camZ);
                        renderer.uploadAndBindUBOs(pipeline);
                        drawBuffers.buildDrawBatchesDirect(cameraPos, queue, renderType);
                    }
                }
            }
        }

        VRenderSystem.setModelOffset(0, 0, 0);
        renderer.pushConstants(pipeline);
    }

    public void renderEntityShadows(double camX, double camY, double camZ) {
        if (level == null) return;

        Renderer renderer = Renderer.getInstance();
        GraphicsPipeline pipeline = PipelineManager.getShadowEntityShader();
        renderer.bindGraphicsPipeline(pipeline);
        renderer.uploadAndBindUBOs(pipeline);

        java.nio.ByteBuffer buf = ENTITY_SHADOW_BUF;
        buf.clear();

        ShadowVertexConsumer shadowConsumer = new ShadowVertexConsumer(buf);
        ShadowNodeCollector nodeCollector = new ShadowNodeCollector(shadowConsumer);

        PoseStack poseStack = new PoseStack();
        net.minecraft.client.renderer.state.CameraRenderState cameraState = new net.minecraft.client.renderer.state.CameraRenderState();
        cameraState.pos = new Vec3(camX, camY, camZ);
        cameraState.blockPos = net.minecraft.core.BlockPos.containing(camX, camY, camZ);
        cameraState.initialized = true;
        cameraState.entityPos = cameraState.pos;
        cameraState.orientation = new org.joml.Quaternionf();

        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isInvisible() || entity.isSpectator()) continue;

            double dx = entity.getX() - camX;
            double dy = entity.getY() - camY;
            double dz = entity.getZ() - camZ;

            try {
                var renderState = this.entityRenderDispatcher.extractEntity(entity, this.partialTick);
                poseStack.pushPose();
                this.entityRenderDispatcher.submit(renderState, cameraState, dx, dy, dz, poseStack, nodeCollector);
                poseStack.popPose();
            } catch (Exception ignored) {
            }

            if (buf.remaining() < 1024) break;
        }

        int vertexCount = shadowConsumer.getVertexCount();
        if (vertexCount > 0) {
            buf.flip();
            Drawer drawer = Renderer.getDrawer();
            drawer.draw(buf, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION, vertexCount);
        }
    }

    private static class ShadowVertexConsumer implements VertexConsumer {
        private final java.nio.ByteBuffer buffer;
        private int vertexCount;

        ShadowVertexConsumer(java.nio.ByteBuffer buffer) {
            this.buffer = buffer;
        }

        int getVertexCount() { return vertexCount; }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            if (buffer.remaining() >= 12) {
                buffer.putFloat(x).putFloat(y).putFloat(z);
                vertexCount++;
            }
            return this;
        }

        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }

    private static class ShadowNodeCollector implements net.minecraft.client.renderer.SubmitNodeCollector {
        private final ShadowVertexConsumer consumer;

        ShadowNodeCollector(ShadowVertexConsumer consumer) {
            this.consumer = consumer;
        }

        @Override
        public net.minecraft.client.renderer.OrderedSubmitNodeCollector order(int i) {
            return this;
        }

        @Override
        public <S> void submitModel(net.minecraft.client.model.Model<? super S> model, S state,
                                     PoseStack poseStack, RenderType renderType, int light, int overlay, int color,
                                     net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, int i,
                                     net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
            model.renderToBuffer(poseStack, consumer, light, overlay);
        }

        @Override
        public void submitModelPart(net.minecraft.client.model.geom.ModelPart part, PoseStack poseStack,
                                     RenderType renderType, int light, int overlay,
                                     net.minecraft.client.renderer.texture.TextureAtlasSprite sprite,
                                     boolean bl, boolean bl2, int color,
                                     net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay crumblingOverlay, int i) {
            part.render(poseStack, consumer, light, overlay);
        }

        @Override
        public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
                                          net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer renderer) {
            renderer.render(poseStack.last(), consumer);
        }

        // No-op for everything else
        @Override public void submitHitbox(PoseStack ps, net.minecraft.client.renderer.entity.state.EntityRenderState s, net.minecraft.client.renderer.entity.state.HitboxesRenderState h) {}
        @Override public void submitShadow(PoseStack ps, float f, java.util.List<net.minecraft.client.renderer.entity.state.EntityRenderState.ShadowPiece> l) {}
        @Override public void submitNameTag(PoseStack ps, Vec3 v, int i, net.minecraft.network.chat.Component c, boolean b, int j, double d, net.minecraft.client.renderer.state.CameraRenderState cs) {}
        @Override public void submitText(PoseStack ps, float f1, float f2, net.minecraft.util.FormattedCharSequence seq, boolean b, net.minecraft.client.gui.Font.DisplayMode dm, int i, int j, int k, int l) {}
        @Override public void submitFlame(PoseStack ps, net.minecraft.client.renderer.entity.state.EntityRenderState s, org.joml.Quaternionf q) {}
        @Override public void submitLeash(PoseStack ps, net.minecraft.client.renderer.entity.state.EntityRenderState.LeashState ls) {}
        @Override public void submitBlock(PoseStack ps, net.minecraft.world.level.block.state.BlockState bs, int i, int j, int k) {}
        @Override public void submitMovingBlock(PoseStack ps, net.minecraft.client.renderer.block.MovingBlockRenderState mrs) {}
        @Override public void submitBlockModel(PoseStack ps, RenderType rt, net.minecraft.client.renderer.block.model.BlockStateModel bsm, float f1, float f2, float f3, int i, int j, int k) {}
        @Override public void submitItem(PoseStack ps, net.minecraft.world.item.ItemDisplayContext idc, int i, int j, int k, int[] ia, java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> quads, RenderType rt, net.minecraft.client.renderer.item.ItemStackRenderState.FoilType ft) {}
        @Override public void submitParticleGroup(net.minecraft.client.renderer.SubmitNodeCollector.ParticleGroupRenderer pgr) {}
    }

    public void renderSectionLayer(TerrainRenderType renderType, double camX, double camY, double camZ, Matrix4f modelView, Matrix4f projection) {
        Renderer.getInstance().getMainPass().rebindMainTarget();

        this.sortTranslucentSections(camX, camY, camZ);

        ProfilerFiller mcProfiler = net.minecraft.util.profiling.Profiler.get();
        Zone zone = mcProfiler.zone(() -> "render_" + renderType);

        final boolean isTranslucent = renderType == TerrainRenderType.TRANSLUCENT;
        final boolean indirectDraw = Initializer.CONFIG.indirectDraw;

        if (!isTranslucent) {
            GlStateManager._disableBlend();
        } else {
            GlStateManager._enableBlend();
            VRenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        }

        VRenderSystem.enableCull();
        VRenderSystem.depthFunc(GL11.GL_LEQUAL);

        GlStateManager._enableDepthTest();
        GlStateManager._depthMask(true);

        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._disablePolygonOffset();
        VRenderSystem.setPolygonModeGL(GL11.GL_FILL);

        VRenderSystem.applyMVP(modelView, projection);
        VRenderSystem.setPrimitiveTopologyGL(GL11.GL_TRIANGLES);

        Renderer renderer = Renderer.getInstance();
        GraphicsPipeline pipeline = PipelineManager.getTerrainShader(renderType);
        renderer.bindGraphicsPipeline(pipeline);

        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        AbstractTexture blockAtlasTexture = textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS);
        blockAtlasTexture.setUseMipmaps(true);

        RenderSystem.setShaderTexture(0, blockAtlasTexture.getTextureView());
        RenderSystem.setShaderTexture(2, Minecraft.getInstance().gameRenderer.lightTexture().getTextureView());

        VTextureSelector.bindShaderTextures(pipeline);
        VTextureSelector.bindTexture(3, Renderer.getInstance().getShadowPass().getShadowMap());

        IndexBuffer indexBuffer = Renderer.getDrawer().getQuadsIndexBuffer().getIndexBuffer();
        Renderer.getDrawer().bindIndexBuffer(Renderer.getCommandBuffer(), indexBuffer, indexBuffer.indexType.value);

        int currentFrame = Renderer.getCurrentFrame();
        Set<TerrainRenderType> allowedRenderTypes = Initializer.CONFIG.uniqueOpaqueLayer ? TerrainRenderType.COMPACT_RENDER_TYPES : TerrainRenderType.SEMI_COMPACT_RENDER_TYPES;
        if (allowedRenderTypes.contains(renderType)) {
            renderType.setCutoutUniform();

            for (Iterator<ChunkArea> iterator = this.sectionGraph.getChunkAreaQueue().iterator(isTranslucent); iterator.hasNext(); ) {
                ChunkArea chunkArea = iterator.next();
                var queue = chunkArea.sectionQueue;
                DrawBuffers drawBuffers = chunkArea.drawBuffers;

                renderer.uploadAndBindUBOs(pipeline);
                if (drawBuffers.getAreaBuffer(renderType) != null && queue.size() > 0) {

                    drawBuffers.bindBuffers(Renderer.getCommandBuffer(), pipeline, renderType, camX, camY, camZ);
                    renderer.uploadAndBindUBOs(pipeline);

                    if (indirectDraw)
                        drawBuffers.buildDrawBatchesIndirect(cameraPos, indirectBuffers[currentFrame], queue, renderType);
                    else
                        drawBuffers.buildDrawBatchesDirect(cameraPos, queue, renderType);
                }
            }
        }

        if (renderType == TerrainRenderType.CUTOUT || renderType == TerrainRenderType.TRIPWIRE) {
            indirectBuffers[currentFrame].submitUploads();
//            uniformBuffers.submitUploads();
        }

        // Need to reset push constants in case the pipeline will still be used for rendering
        if (!indirectDraw) {
            VRenderSystem.setModelOffset(0, 0, 0);
            renderer.pushConstants(pipeline);
        }

        zone.close();
    }

    private void sortTranslucentSections(double camX, double camY, double camZ) {
        ProfilerFiller mcProfiler = net.minecraft.util.profiling.Profiler.get();
        mcProfiler.push("translucent_sort");
        double d0 = camX - this.xTransparentOld;
        double d1 = camY - this.yTransparentOld;
        double d2 = camZ - this.zTransparentOld;
        if (d0 * d0 + d1 * d1 + d2 * d2 > 2.0D) {
            this.xTransparentOld = camX;
            this.yTransparentOld = camY;
            this.zTransparentOld = camZ;
            int j = 0;

            Iterator<RenderSection> iterator = this.sectionGraph.getSectionQueue().iterator(false);

            while (iterator.hasNext() && j < 200) {
                RenderSection section = iterator.next();
                section.resortTransparency(this.taskDispatcher);

                if (!section.isCompletelyEmpty()) {
                    ++j;
                }
            }
        }

        mcProfiler.pop();
    }

    public void renderBlockEntities(PoseStack poseStack, LevelRenderState levelRenderState,
                                    SubmitNodeStorage submitNodeStorage,
                                    Long2ObjectMap<SortedSet<BlockDestructionProgress>> destructionProgress) {
        Profiler profiler = Profiler.getMainProfiler();
        profiler.pop();
        profiler.push("Block-entities");

        Vec3 vec3 = levelRenderState.cameraRenderState.pos;
        double camX = vec3.x();
        double camY = vec3.y();
        double camZ = vec3.z();

        for (RenderSection renderSection : this.sectionGraph.getBlockEntitiesSections()) {
            List<BlockEntity> list = renderSection.getCompiledSection().getBlockEntities();
            if (!list.isEmpty()) {
                for (BlockEntity blockEntity : list) {
                    BlockPos blockPos = blockEntity.getBlockPos();
                    SortedSet<BlockDestructionProgress> sortedSet = destructionProgress.get(blockPos.asLong());
                    ModelFeatureRenderer.CrumblingOverlay crumblingOverlay;
                    if (sortedSet != null && !sortedSet.isEmpty()) {
                        poseStack.pushPose();
                        poseStack.translate(blockPos.getX() - camX, blockPos.getY() - camY, blockPos.getZ() - camZ);
                        crumblingOverlay = new ModelFeatureRenderer.CrumblingOverlay(sortedSet.last()
                                                                                              .getProgress(), poseStack.last());
                        poseStack.popPose();
                    } else {
                        crumblingOverlay = null;
                    }

                    BlockEntityRenderState blockEntityRenderState = this.blockEntityRenderDispatcher.tryExtractRenderState(blockEntity, this.partialTick, crumblingOverlay);
                    if (blockEntityRenderState != null) {
                        levelRenderState.blockEntityRenderStates.add(blockEntityRenderState);
                    }
                }
            }
        }

        Iterator<BlockEntity> iterator = this.level.getGloballyRenderedBlockEntities().iterator();

        while (iterator.hasNext()) {
            BlockEntity blockEntity2 = iterator.next();
            if (blockEntity2.isRemoved()) {
                iterator.remove();
            } else {
                BlockEntityRenderState blockEntityRenderState2 = this.blockEntityRenderDispatcher.tryExtractRenderState(blockEntity2, this.partialTick, null);
                if (blockEntityRenderState2 != null) {
                    levelRenderState.blockEntityRenderStates.add(blockEntityRenderState2);
                }
            }
        }

        for (BlockEntityRenderState blockEntityRenderState : levelRenderState.blockEntityRenderStates) {
            BlockPos blockPos = blockEntityRenderState.blockPos;
            poseStack.pushPose();
            poseStack.translate(blockPos.getX() - camX, blockPos.getY() - camY, blockPos.getZ() - camZ);
            var blockEntityRenderDispatcher = this.minecraft.getBlockEntityRenderDispatcher();
            blockEntityRenderDispatcher.submit(blockEntityRenderState, poseStack, submitNodeStorage, levelRenderState.cameraRenderState);
            poseStack.popPose();
        }
    }

    public void setPartialTick(float partialTick) {
        this.partialTick = partialTick;
    }

    public void scheduleGraphUpdate() {
        this.graphNeedsUpdate = true;
    }

    public boolean graphNeedsUpdate() {
        return this.graphNeedsUpdate;
    }

    public int getVisibleSectionsCount() {
        return this.sectionGraph.getSectionQueue().size();
    }

    public void setSectionDirty(int x, int y, int z, boolean flag) {
        this.sectionGrid.setDirty(x, y, z, flag);

        this.renderRegionCache.remove(x, z);
    }

    public SectionGrid getSectionGrid() {
        return this.sectionGrid;
    }

    public ChunkAreaManager getChunkAreaManager() {
        if (this.sectionGrid == null)
            return null;
        return this.sectionGrid.chunkAreaManager;
    }

    public TaskDispatcher getTaskDispatcher() {
        return taskDispatcher;
    }

    public short getLastFrame() {
        return this.sectionGraph.getLastFrame();
    }

    public int getRenderDistance() {
        return this.renderDistance;
    }

    public String getChunkStatistics() {
        if (this.sectionGraph == null) {
            return null;
        }

        return this.sectionGraph.getStatistics();
    }

    public void cleanUp() {
        if (indirectBuffers != null)
            Arrays.stream(indirectBuffers).forEach(Buffer::scheduleFree);
    }

    public static WorldRenderer getInstance() {
        return INSTANCE;
    }

    public static ClientLevel getLevel() {
        return INSTANCE.level;
    }

    public static Vec3 getCameraPos() {
        return INSTANCE.cameraPos;
    }

}

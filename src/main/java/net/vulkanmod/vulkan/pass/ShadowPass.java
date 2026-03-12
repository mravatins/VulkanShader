package net.vulkanmod.vulkan.pass;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkViewport;

import static org.lwjgl.vulkan.VK10.*;

public class ShadowPass {
    public static final int SHADOW_MAP_SIZE = 4096;

    public static ShadowPass create() {
        return new ShadowPass();
    }

    private Framebuffer shadowFramebuffer;
    private RenderPass shadowRenderPass;

    ShadowPass() {
        createResources();
    }

    private void createResources() {
        // Force D32_SFLOAT (pure depth, no stencil) so the image view aspect is
        // DEPTH_BIT only, which is required for shader sampling as sampler2D.
        this.shadowFramebuffer = Framebuffer.builder(SHADOW_MAP_SIZE, SHADOW_MAP_SIZE, 0, true)
                .setDepthFormat(VK_FORMAT_D32_SFLOAT)
                .build();

        RenderPass.Builder builder = RenderPass.builder(this.shadowFramebuffer);
        builder.getDepthAttachmentInfo()
                .setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE)
                .setFinalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL);

        this.shadowRenderPass = builder.build();
    }

    private void update() {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;

        long lastTick = -1;
        if (level == null || level.getGameTime() == lastTick)
            return;

        lastTick = level.getGameTime();

        net.minecraft.client.Camera camera = mc.gameRenderer.getMainCamera();
        net.minecraft.world.phys.Vec3 camPos = camera.getPosition();

        float timeOfDay = (level.getDayTime() % 24000L) / 24000.0f;
        float sunAngle = timeOfDay * 2.0f * (float) Math.PI;

        float sunDirX = (float) Math.cos(sunAngle);
        float sunDirY = (float) Math.sin(sunAngle);
        float sunDirZ = 0.0f;

        VRenderSystem.setShaderLightDir(0, sunDirX, sunDirY, sunDirZ);
        VRenderSystem.setShaderLightDir(1, 0.0f, 0.0f, 0.0f); // Default second light

        // range must satisfy: SHADOW_MAP_SIZE / (2 * range) = integer (texels per
        // block).
        // 4096 / (2 * 256) = 8 texels/block — every block edge lands exactly on a texel
        // boundary.
        float shadowDistance = 256.0f;
        float range = 256.0f;

        // Snap the frustum center to the block grid (blocks are 1-unit integer
        // aligned).
        // This prevents the shadow frustum from drifting sub-block as the camera moves,
        // which would cause block shadows to shimmer or misalign between frames.
        double centerX = Math.floor(camPos.x);
        double centerY = Math.floor(camPos.y);
        double centerZ = Math.floor(camPos.z);

        float lightX = (float) centerX + sunDirX * shadowDistance;
        float lightY = (float) centerY + sunDirY * shadowDistance;
        float lightZ = (float) centerZ + sunDirZ * shadowDistance;

        Vector3f up = Math.abs(sunDirY) > 0.99f
                ? new Vector3f(0.0f, 0.0f, 1.0f)
                : new Vector3f(0.0f, 1.0f, 0.0f);

        Matrix4f lightView = new Matrix4f().lookAt(
                lightX, lightY, lightZ,
                (float) centerX, (float) centerY, (float) centerZ,
                up.x, up.y, up.z);

        Matrix4f lightProj = new Matrix4f().ortho(-range, range, -range, range, 0.5f, shadowDistance * 2.0f, true);

        // World-space light VP matrix
        Matrix4f worldLightVP = new Matrix4f(lightProj).mul(lightView);

        // REMOVED texel snapping — suspected cause of shrink/grow jitter

        Matrix4f lightSpaceMat = worldLightVP.translate((float) camPos.x, (float) camPos.y, (float) camPos.z);
        lightSpaceMat.get(VRenderSystem.lightSpaceMatrix.buffer.asFloatBuffer());
        VRenderSystem.recomputeLightSpaceViewMatrix();
    }

    public void begin(VkCommandBuffer commandBuffer, MemoryStack stack) {
        update();
        Renderer.getInstance().beginRenderPass(this.shadowRenderPass, this.shadowFramebuffer);

        // Non-flipped viewport: UV.y = NDC.y * 0.5 + 0.5 is consistent when sampling in
        // Step 4.
        VkViewport.Buffer viewport = VkViewport.malloc(1, stack);
        viewport.x(0).y(0).width(SHADOW_MAP_SIZE).height(SHADOW_MAP_SIZE).minDepth(0.0f).maxDepth(1.0f);
        vkCmdSetViewport(commandBuffer, 0, viewport);

        // Reduced depth bias to minimize Peter Panning (shadow gap).
        vkCmdSetDepthBias(commandBuffer, 0.0f, 0.0f, 0.0f);
    }

    public void end(VkCommandBuffer commandBuffer) {
        Renderer.getInstance().endRenderPass(commandBuffer);
    }

    public VulkanImage getShadowMap() {
        return this.shadowFramebuffer.getDepthAttachment();
    }

    public void onResize() {
        // Shadow map has a fixed size — nothing to do on window resize
    }

    public void cleanUp() {
        this.shadowRenderPass.cleanUp();
        this.shadowFramebuffer.cleanUp();
    }
}

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
        if (level == null) return;

        // Sun angle: 0 at sunrise, PI at sunset, 2PI back to next sunrise
        long dayTime = level.getDayTime();
        float timeOfDay = (dayTime % 24000L) / 24000.0f;
        float sunAngle = timeOfDay * 2.0f * (float) Math.PI;

        // Sun direction vector (pointing FROM world TOWARD sun).
        // Minecraft rotates celestial objects around the Z axis (Axis.ZP), so
        // the sun sweeps through the X-Y plane: sunrise near +X, noon at +Y, sunset near -X.
        float sunDirX = (float) Math.cos(sunAngle);
        float sunDirY = (float) Math.sin(sunAngle);
        float sunDirZ = 0.0f;

        // Place the light eye in the direction of the sun from the origin (camera position).
        // lookAt(eye, target=origin, up) → view matrix that looks from the sun toward the scene.
        float shadowDistance = 200.0f;
        float lightX = sunDirX * shadowDistance;
        float lightY = sunDirY * shadowDistance;
        float lightZ = sunDirZ * shadowDistance;

        // Avoid degenerate up vector when sun is near zenith/nadir (sunDirY ≈ ±1)
        Vector3f up = Math.abs(sunDirY) > 0.99f
                ? new Vector3f(0.0f, 0.0f, 1.0f)
                : new Vector3f(0.0f, 1.0f, 0.0f);

        Matrix4f lightView = new Matrix4f().lookAt(lightX, lightY, lightZ, 0, 0, 0, up.x, up.y, up.z);

        // Orthographic projection, right-handed, Vulkan depth convention [0, 1] (zZeroToOne=true)
        // Matches Minecraft's clip control (GL_ZERO_TO_ONE) so LightSpaceMat and MVP use the same depth range
        float range = 160.0f;
        Matrix4f lightProj = new Matrix4f().ortho(-range, range, -range, range, 0.5f, shadowDistance * 2.0f, true);

        Matrix4f lightSpaceMat = new Matrix4f(lightProj).mul(lightView);

        // Texel snapping: snap the matrix translation to shadow-map texel boundaries so
        // the shadow map doesn't shift sub-texel when the camera moves, eliminating shimmering.
        org.joml.Vector4f shadowOrigin = lightSpaceMat.transform(new org.joml.Vector4f(0, 0, 0, 1));
        float halfSize = SHADOW_MAP_SIZE / 2.0f;
        float snapX = (Math.round(shadowOrigin.x * halfSize) / halfSize) - shadowOrigin.x;
        float snapY = (Math.round(shadowOrigin.y * halfSize) / halfSize) - shadowOrigin.y;
        lightSpaceMat.m30(lightSpaceMat.m30() + snapX);
        lightSpaceMat.m31(lightSpaceMat.m31() + snapY);

        lightSpaceMat.get(VRenderSystem.lightSpaceMatrix.buffer.asFloatBuffer());
    }

    public void begin(VkCommandBuffer commandBuffer, MemoryStack stack) {
        update();
        Renderer.getInstance().beginRenderPass(this.shadowRenderPass, this.shadowFramebuffer);

        // Non-flipped viewport: UV.y = NDC.y * 0.5 + 0.5 is consistent when sampling in Step 4.
        VkViewport.Buffer viewport = VkViewport.malloc(1, stack);
        viewport.x(0).y(0).width(SHADOW_MAP_SIZE).height(SHADOW_MAP_SIZE).minDepth(0.0f).maxDepth(1.0f);
        vkCmdSetViewport(commandBuffer, 0, viewport);

        // Slope-scale depth bias: eliminates shadow acne without a constant offset gap.
        // constantFactor=0 (no flat bias), clamp=0, slopeFactor=1.5 (bias scales with surface slope).
        vkCmdSetDepthBias(commandBuffer, 0.0f, 0.0f, 1.5f);
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

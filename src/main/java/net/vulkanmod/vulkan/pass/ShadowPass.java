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
        net.minecraft.client.Camera camera = mc.gameRenderer.getMainCamera();
        net.minecraft.world.phys.Vec3 camPos = camera.getPosition();

        // Sun angle: 0 at sunrise, PI at sunset, 2PI back to next sunrise
        long dayTime = level.getDayTime();
        float timeOfDay = (dayTime % 24000L) / 24000.0f;
        float sunAngle = timeOfDay * 2.0f * (float) Math.PI;

        float sunDirX = (float) Math.cos(sunAngle);
        float sunDirY = (float) Math.sin(sunAngle);
        float sunDirZ = 0.0f;

        float shadowDistance = 200.0f;
        float range = 160.0f;

        // Build the light view-projection in WORLD space (centered on camera world position)
        float lightX = (float) camPos.x + sunDirX * shadowDistance;
        float lightY = (float) camPos.y + sunDirY * shadowDistance;
        float lightZ = (float) camPos.z + sunDirZ * shadowDistance;

        Vector3f up = Math.abs(sunDirY) > 0.99f
                ? new Vector3f(0.0f, 0.0f, 1.0f)
                : new Vector3f(0.0f, 1.0f, 0.0f);

        Matrix4f lightView = new Matrix4f().lookAt(
                lightX, lightY, lightZ,
                (float) camPos.x, (float) camPos.y, (float) camPos.z,
                up.x, up.y, up.z);

        Matrix4f lightProj = new Matrix4f().ortho(-range, range, -range, range, 0.5f, shadowDistance * 2.0f, true);

        // World-space light VP matrix
        Matrix4f worldLightVP = new Matrix4f(lightProj).mul(lightView);

        // Texel snapping: snap the world-space light VP so that world-space positions
        // always land on the same shadow-map texel regardless of camera position.
        // Project world origin to find its fractional texel offset, then adjust.
        org.joml.Vector4f originInLight = worldLightVP.transform(new org.joml.Vector4f(0, 0, 0, 1));
        float halfSize = SHADOW_MAP_SIZE / 2.0f;
        float snapX = (Math.round(originInLight.x * halfSize) / halfSize) - originInLight.x;
        float snapY = (Math.round(originInLight.y * halfSize) / halfSize) - originInLight.y;
        worldLightVP.m30(worldLightVP.m30() + snapX);
        worldLightVP.m31(worldLightVP.m31() + snapY);

        // The shaders operate in camera-relative space (pos = worldPos - camPos).
        // To convert: LightSpaceMat_cam * pos_cam = worldLightVP * (pos_cam + camPos)
        //           = worldLightVP * pos_cam + worldLightVP * camPos
        // So we pre-translate: LightSpaceMat_cam = worldLightVP * translate(camPos)
        Matrix4f lightSpaceMat = worldLightVP.translate((float) camPos.x, (float) camPos.y, (float) camPos.z);

        lightSpaceMat.get(VRenderSystem.lightSpaceMatrix.buffer.asFloatBuffer());
    }

    public void begin(VkCommandBuffer commandBuffer, MemoryStack stack) {
        update();
        Renderer.getInstance().beginRenderPass(this.shadowRenderPass, this.shadowFramebuffer);

        // Non-flipped viewport: UV.y = NDC.y * 0.5 + 0.5 is consistent when sampling in Step 4.
        VkViewport.Buffer viewport = VkViewport.malloc(1, stack);
        viewport.x(0).y(0).width(SHADOW_MAP_SIZE).height(SHADOW_MAP_SIZE).minDepth(0.0f).maxDepth(1.0f);
        vkCmdSetViewport(commandBuffer, 0, viewport);

        // Depth bias: constant factor pushes all depths slightly, slope factor scales with surface angle.
        // This prevents shadow acne and ensures proper shadow detection under overhangs.
        vkCmdSetDepthBias(commandBuffer, 2.0f, 0.0f, 2.0f);
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

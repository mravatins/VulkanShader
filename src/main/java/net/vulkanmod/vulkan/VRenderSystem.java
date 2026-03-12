package net.vulkanmod.vulkan;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.FogData;
import net.vulkanmod.render.engine.VkGpuBuffer;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.util.ColorUtil;
import net.vulkanmod.vulkan.util.MappedBuffer;
import net.vulkanmod.vulkan.util.VUtil;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.vulkan.VK10.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public abstract class VRenderSystem {
    private static final float DEFAULT_DEPTH_VALUE = 1.0f;

    private static long window;

    public static boolean depthTest = true;
    public static boolean depthMask = true;
    public static int depthFun = 515;
    public static int topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    public static int polygonMode = VK_POLYGON_MODE_FILL;
    public static boolean canSetLineWidth = false;

    public static int colorMask = PipelineState.ColorMask.getColorMask(true, true, true, true);

    public static boolean cull = true;

    public static boolean logicOp = false;
    public static int logicOpFun = 0;

    public static float clearDepthValue = DEFAULT_DEPTH_VALUE;
    public static FloatBuffer clearColor = MemoryUtil.memCallocFloat(4);

    public static MappedBuffer modelViewMatrix = new MappedBuffer(16 * 4);
    public static MappedBuffer projectionMatrix = new MappedBuffer(16 * 4);
    public static MappedBuffer TextureMatrix = new MappedBuffer(16 * 4);
    public static MappedBuffer MVP = new MappedBuffer(16 * 4);

    public static MappedBuffer modelOffset = new MappedBuffer(3 * 4);
    public static MappedBuffer lightDirection0 = new MappedBuffer(4 * 4);
    public static MappedBuffer lightDirection1 = new MappedBuffer(4 * 4);

    public static MappedBuffer lightSpaceMatrix = new MappedBuffer(16 * 4);
    public static MappedBuffer inverseProjectionMatrix = new MappedBuffer(16 * 4);
    public static MappedBuffer inverseViewMatrix = new MappedBuffer(16 * 4);
    public static MappedBuffer lightSpaceViewMatrix = new MappedBuffer(16 * 4);

    public static MappedBuffer cameraWorldPos = new MappedBuffer(3 * 4);

    public static MappedBuffer getCameraWorldPos() {
        net.minecraft.client.Camera cam = Minecraft.getInstance().gameRenderer.getMainCamera();
        net.minecraft.world.phys.Vec3 pos = cam.getPosition();
        VUtil.UNSAFE.putFloat(cameraWorldPos.ptr,     (float) pos.x);
        VUtil.UNSAFE.putFloat(cameraWorldPos.ptr + 4, (float) pos.y);
        VUtil.UNSAFE.putFloat(cameraWorldPos.ptr + 8, (float) pos.z);
        return cameraWorldPos;
    }

    private static final long START_TIME_MS = System.currentTimeMillis();

    public static float getTime() {
        return (System.currentTimeMillis() - START_TIME_MS) / 1000.0f;
    }

    static {
        new org.joml.Matrix4f().identity().get(lightSpaceMatrix.buffer.asFloatBuffer());
    }

    public static MappedBuffer shaderColor = new MappedBuffer(4 * 4);
    public static MappedBuffer shaderFogColor = new MappedBuffer(4 * 4);
    public static FogData fogData;

    public static MappedBuffer screenSize = new MappedBuffer(2 * 4);

    public static net.vulkanmod.vulkan.texture.VulkanImage sceneColorImage;

    public static void createSceneColorImage(int width, int height, int format) {
        if (sceneColorImage != null)
            sceneColorImage.free();

        sceneColorImage = net.vulkanmod.vulkan.texture.VulkanImage.builder(width, height)
                .setName("SceneColor")
                .setFormat(format)
                .setUsage(org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT
                        | org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT)
                .setLinearFiltering(true)
                .setClamp(true)
                .createVulkanImage();
    }

    public static float alphaCutout = 0.0f;

    private static boolean depthBiasEnabled = false;
    private static float depthBiasConstant = 0.0f;
    private static float depthBiasSlope = 0.0f;

    public static void initRenderer() {
        Vulkan.initVulkan(window);

        setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    public static MappedBuffer getScreenSize() {
        updateScreenSize();
        return screenSize;
    }

    public static void updateScreenSize() {
        Window window = Minecraft.getInstance().getWindow();

        screenSize.putFloat(0, (float) window.getWidth());
        screenSize.putFloat(4, (float) window.getHeight());
    }

    public static void setWindow(long window) {
        VRenderSystem.window = window;
    }

    public static ByteBuffer getModelOffset() {
        return modelOffset.buffer;
    }

    public static int maxSupportedTextureSize() {
        return DeviceManager.deviceProperties.limits().maxImageDimension2D();
    }

    public static void applyMVP(Matrix4f MV, Matrix4f P) {
        applyModelViewMatrix(MV);
        applyProjectionMatrix(P);
        calculateMVP();
    }

    public static void applyModelViewMatrix(Matrix4f mat) {
        mat.get(modelViewMatrix.buffer.asFloatBuffer());
    }

    public static void applyProjectionMatrix(Matrix4f mat) {
        mat.get(projectionMatrix.buffer.asFloatBuffer());
        mat.invert(new Matrix4f()).get(inverseProjectionMatrix.buffer.asFloatBuffer());
    }

    public static void applyProjectionMatrix(GpuBufferSlice bufferSlice) {
        long ptr = ((VkGpuBuffer) bufferSlice.buffer()).getBuffer().getDataPtr();
        ByteBuffer byteBuffer = MemoryUtil.memByteBuffer(ptr + bufferSlice.offset(), bufferSlice.length());
        Matrix4f matrix4f = new Matrix4f().set(byteBuffer);

        matrix4f.get(projectionMatrix.buffer.asFloatBuffer());
        matrix4f.invert(new Matrix4f()).get(inverseProjectionMatrix.buffer.asFloatBuffer());
    }

    public static MappedBuffer getInverseProjectionMatrix() {
        return inverseProjectionMatrix;
    }

    public static void calculateMVP() {
        org.joml.Matrix4f MV = new org.joml.Matrix4f(modelViewMatrix.buffer.asFloatBuffer());
        org.joml.Matrix4f P = new org.joml.Matrix4f(projectionMatrix.buffer.asFloatBuffer());

        P.mul(MV).get(MVP.buffer);
    }

    public static void setTextureMatrix(Matrix4f mat) {
        mat.get(TextureMatrix.buffer.asFloatBuffer());
    }

    public static MappedBuffer getTextureMatrix() {
        return TextureMatrix;
    }

    public static MappedBuffer getModelViewMatrix() {
        return modelViewMatrix;
    }

    public static MappedBuffer getProjectionMatrix() {
        return projectionMatrix;
    }

    public static MappedBuffer getMVP() {
        return MVP;
    }

    public static MappedBuffer getLightSpaceMatrix() {
        return lightSpaceMatrix;
    }

    /**
     * Store the camera-only view matrix (not including entity model transforms) so we can
     * compute LightSpaceViewMat = LightSpaceMat * InverseViewMat for entity shadow lookups.
     */
    public static void setCameraViewMatrix(Matrix4f viewMat) {
        // Invert to get InverseViewMat
        viewMat.invert(new Matrix4f()).get(inverseViewMatrix.buffer.asFloatBuffer());
        recomputeLightSpaceViewMatrix();
    }

    public static void recomputeLightSpaceViewMatrix() {
        Matrix4f lsm = new Matrix4f().set(lightSpaceMatrix.buffer.asFloatBuffer());
        Matrix4f ivm = new Matrix4f().set(inverseViewMatrix.buffer.asFloatBuffer());
        lsm.mul(ivm).get(lightSpaceViewMatrix.buffer.asFloatBuffer());
    }

    public static MappedBuffer getLightSpaceViewMatrix() {
        return lightSpaceViewMatrix;
    }

    public static void setShaderLightDir(int index, float x, float y, float z) {
        long ptr = (index == 0 ? lightDirection0 : lightDirection1).ptr;
        VUtil.UNSAFE.putFloat(ptr, x);
        VUtil.UNSAFE.putFloat(ptr + 4, y);
        VUtil.UNSAFE.putFloat(ptr + 8, z);
        VUtil.UNSAFE.putFloat(ptr + 12, 0.0f);
    }

    public static void setModelOffset(float x, float y, float z) {
        long ptr = modelOffset.ptr;
        VUtil.UNSAFE.putFloat(ptr, x);
        VUtil.UNSAFE.putFloat(ptr + 4, y);
        VUtil.UNSAFE.putFloat(ptr + 8, z);
    }

    public static void setShaderColor(float f1, float f2, float f3, float f4) {
        ColorUtil.setRGBA_Buffer(shaderColor, f1, f2, f3, f4);
    }

    public static void setShaderFogColor(float f1, float f2, float f3, float f4) {
        ColorUtil.setRGBA_Buffer(shaderFogColor, f1, f2, f3, f4);
    }

    public static MappedBuffer getShaderColor() {
        return shaderColor;
    }

    public static MappedBuffer getShaderFogColor() {
        return shaderFogColor;
    }

    public static FogData getFogData() {
        return fogData;
    }

    public static void setClearColor(float f1, float f2, float f3, float f4) {
        ColorUtil.setRGBA_Buffer(clearColor, f1, f2, f3, f4);
    }

    public static void clear(int mask) {
        Renderer.clearAttachments(mask);
    }

    public static void clearDepth(double depth) {
        clearDepthValue = (float) depth;
    }

    // Pipeline state

    public static void disableDepthTest() {
        depthTest = false;
    }

    public static void depthMask(boolean b) {
        depthMask = b;
    }

    public static void setPrimitiveTopologyGL(final int mode) {
        VRenderSystem.topology = switch (mode) {
            case GL11.GL_LINES, GL11.GL_LINE_STRIP -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
            case GL11.GL_TRIANGLE_FAN, GL11.GL_TRIANGLES, GL11.GL_TRIANGLE_STRIP -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            default -> throw new RuntimeException(String.format("Unknown GL primitive topology: %s", mode));
        };
    }

    public static void setPolygonModeGL(final int mode) {
        VRenderSystem.polygonMode = switch (mode) {
            case GL11.GL_POINT -> VK_POLYGON_MODE_POINT;
            case GL11.GL_LINE -> VK_POLYGON_MODE_LINE;
            case GL11.GL_FILL -> VK_POLYGON_MODE_FILL;
            default -> throw new RuntimeException(String.format("Unknown GL polygon mode: %s", mode));
        };
    }

    public static void setLineWidth(final float width) {
        if (canSetLineWidth) {
            Renderer.setLineWidth(width);
        }
    }

    public static void colorMask(boolean b, boolean b1, boolean b2, boolean b3) {
        colorMask = PipelineState.ColorMask.getColorMask(b, b1, b2, b3);
    }

    public static int getColorMask() {
        return colorMask;
    }

    public static void enableDepthTest() {
        depthTest = true;
    }

    public static void enableCull() {
        cull = true;
    }

    public static void disableCull() {
        cull = false;
    }

    public static void depthFunc(int depthFun) {
        VRenderSystem.depthFun = depthFun;
    }

    public static void enableBlend() {
        PipelineState.blendInfo.enabled = true;
    }

    public static void disableBlend() {
        PipelineState.blendInfo.enabled = false;
    }

    public static void blendFunc(int srcFactor, int dstFactor) {
        PipelineState.blendInfo.setBlendFunction(srcFactor, dstFactor);
    }

    public static void blendFuncSeparate(int srcFactorRGB, int dstFactorRGB, int srcFactorAlpha, int dstFactorAlpha) {
        PipelineState.blendInfo.setBlendFuncSeparate(srcFactorRGB, dstFactorRGB, srcFactorAlpha, dstFactorAlpha);
    }

    public static void blendOp(int op) {
        PipelineState.blendInfo.setBlendOp(op);
    }

    public static void enableColorLogicOp() {
        logicOp = true;
    }

    public static void disableColorLogicOp() {
        logicOp = false;
    }

    public static void logicOp(int glLogicOp) {
        logicOpFun = glLogicOp;
    }

    public static void polygonOffset(float slope, float biasConstant) {
        if (depthBiasConstant != biasConstant || depthBiasSlope != slope) {
            depthBiasConstant = biasConstant;
            depthBiasSlope = slope;

            Renderer.setDepthBias(depthBiasConstant, depthBiasSlope);
        }
    }

    public static void enablePolygonOffset() {
        if (!depthBiasEnabled) {
            Renderer.setDepthBias(depthBiasConstant, depthBiasSlope);
            depthBiasEnabled = true;
        }
    }

    public static void disablePolygonOffset() {
        if (depthBiasEnabled) {
            Renderer.setDepthBias(0.0F, 0.0F);
            depthBiasEnabled = false;
        }
    }

}

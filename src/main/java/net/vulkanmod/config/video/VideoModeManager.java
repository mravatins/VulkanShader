package net.vulkanmod.config.video;

import net.vulkanmod.Initializer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

public abstract class VideoModeManager {
    private static VideoModeSet.VideoMode osVideoMode;
    private static VideoModeSet[] videoModeSets;

    private static long[] monitors;
    private static String[] monitorNames;

    public static VideoModeSet.VideoMode selectedVideoMode;

    public static void init() {
        enumerateMonitors();
        long monitor = glfwGetPrimaryMonitor();
        osVideoMode = getCurrentVideoMode(monitor);
        videoModeSets = populateVideoResolutions(monitor);
    }

    private static void enumerateMonitors() {
        PointerBuffer monitorsBuffer = glfwGetMonitors();
        if (monitorsBuffer != null && monitorsBuffer.limit() > 0) {
            monitors = new long[monitorsBuffer.limit()];
            monitorNames = new String[monitorsBuffer.limit()];
            for (int i = 0; i < monitorsBuffer.limit(); i++) {
                monitors[i] = monitorsBuffer.get(i);
                String name = glfwGetMonitorName(monitors[i]);
                monitorNames[i] = (name != null) ? name : ("Monitor " + (i + 1));
            }
        } else {
            long primary = glfwGetPrimaryMonitor();
            monitors = new long[]{primary};
            String name = glfwGetMonitorName(primary);
            monitorNames = new String[]{name != null ? name : "Primary"};
        }
    }

    public static void applyMonitorConfig(int monitorIndex) {
        long monitor = getMonitorByIndex(monitorIndex);
        osVideoMode = getCurrentVideoMode(monitor);
        videoModeSets = populateVideoResolutions(monitor);
    }

    public static long getMonitorByIndex(int idx) {
        if (monitors != null && idx >= 0 && idx < monitors.length) {
            return monitors[idx];
        }
        return glfwGetPrimaryMonitor();
    }

    public static long getConfiguredMonitor() {
        return getMonitorByIndex(Initializer.CONFIG.monitorIndex);
    }

    public static int[] getConfiguredMonitorPos() {
        long monitor = getConfiguredMonitor();
        int[] x = new int[1], y = new int[1];
        glfwGetMonitorPos(monitor, x, y);
        return new int[]{x[0], y[0]};
    }

    public static int getMonitorCount() {
        return monitors != null ? monitors.length : 1;
    }

    public static String getMonitorName(int index) {
        if (monitorNames != null && index >= 0 && index < monitorNames.length) {
            return (index + 1) + ": " + monitorNames[index];
        }
        return "Monitor " + (index + 1);
    }

    public static void applySelectedVideoMode() {
        Initializer.CONFIG.videoMode = selectedVideoMode;
    }

    public static VideoModeSet[] getVideoResolutions() {
        return videoModeSets;
    }

    public static VideoModeSet getFirstAvailable() {
        if(videoModeSets != null)
            return videoModeSets[videoModeSets.length - 1];
        else
            return VideoModeSet.getDummy();
    }

    public static VideoModeSet.VideoMode getOsVideoMode() {
        return osVideoMode;
    }

    public static VideoModeSet.VideoMode getCurrentVideoMode(long monitor){
        GLFWVidMode vidMode = GLFW.glfwGetVideoMode(monitor);

        if (vidMode == null)
            throw new NullPointerException("Unable to get current video mode");

        return new VideoModeSet.VideoMode(vidMode.width(), vidMode.height(), vidMode.redBits(), vidMode.refreshRate());
    }

    public static VideoModeSet[] populateVideoResolutions(long monitor) {
        GLFWVidMode.Buffer buffer = GLFW.glfwGetVideoModes(monitor);

        List<VideoModeSet> videoModeSets = new ArrayList<>();

        int currWidth = 0, currHeight = 0, currBitDepth = 0;
        VideoModeSet videoModeSet = null;

        for (int i = 0; i < buffer.limit(); i++) {
            buffer.position(i);
            int bitDepth = buffer.redBits();
            if (buffer.redBits() < 8 || buffer.greenBits() != bitDepth || buffer.blueBits() != bitDepth)
                continue;

            int width = buffer.width();
            int height = buffer.height();
            int refreshRate = buffer.refreshRate();

            if (currWidth != width || currHeight != height || currBitDepth != bitDepth) {
                currWidth = width;
                currHeight = height;
                currBitDepth = bitDepth;

                videoModeSet = new VideoModeSet(currWidth, currHeight, currBitDepth);
                videoModeSets.add(videoModeSet);
            }

            videoModeSet.addRefreshRate(refreshRate);
        }

        VideoModeSet[] arr = new VideoModeSet[videoModeSets.size()];
        videoModeSets.toArray(arr);

        return arr;
    }

    public static VideoModeSet getFromVideoMode(VideoModeSet.VideoMode videoMode) {
        for (var set : videoModeSets) {
            if (set.width == videoMode.width && set.height == videoMode.height)
                return set;
        }

        return null;
    }
}

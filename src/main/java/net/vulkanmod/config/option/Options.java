package net.vulkanmod.config.option;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ParticleStatus;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.gui.OptionBlock;
import net.vulkanmod.config.video.VideoModeManager;
import net.vulkanmod.config.video.VideoModeSet;
import net.vulkanmod.config.video.WindowMode;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.build.light.LightMode;
import net.vulkanmod.render.shader.ShaderPackManager;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.device.DeviceManager;

import java.util.stream.IntStream;

public abstract class Options {
    public static boolean fullscreenDirty = false;
    static Config config = Initializer.CONFIG;
    static Minecraft minecraft = Minecraft.getInstance();
    static Window window = minecraft.getWindow();
    static net.minecraft.client.Options minecraftOptions = minecraft.options;

    public static OptionBlock[] getVideoOpts() {
        var videoMode = config.videoMode;
        var videoModeSet = VideoModeManager.getFromVideoMode(videoMode);

        if (videoModeSet == null) {
            videoModeSet = VideoModeSet.getDummy();
            videoMode = videoModeSet.getVideoMode(-1);
        }

        VideoModeManager.selectedVideoMode = videoMode;
        var refreshRates = videoModeSet.getRefreshRates();

        CyclingOption<Integer> RefreshRate = (CyclingOption<Integer>) new CyclingOption<>(
                Component.translatable("vulkanmod.options.refreshRate"),
                refreshRates.toArray(new Integer[0]),
                (value) -> {
                    VideoModeManager.selectedVideoMode.refreshRate = value;
                    VideoModeManager.applySelectedVideoMode();

                    if (minecraftOptions.fullscreen().get())
                        fullscreenDirty = true;
                },
                () -> VideoModeManager.selectedVideoMode.refreshRate)
                .setTranslator(refreshRate -> Component.nullToEmpty(refreshRate.toString()));

        Option<VideoModeSet> resolutionOption = new CyclingOption<>(
                Component.translatable("options.fullscreen.resolution"),
                VideoModeManager.getVideoResolutions(),
                (value) -> {
                    VideoModeManager.selectedVideoMode = value.getVideoMode(RefreshRate.getNewValue());
                    VideoModeManager.applySelectedVideoMode();

                    if (minecraftOptions.fullscreen().get())
                        fullscreenDirty = true;
                },
                () -> {
                    var selectedVideoMode = VideoModeManager.selectedVideoMode;
                    var selectedVideoModeSet = VideoModeManager.getFromVideoMode(selectedVideoMode);

                    return selectedVideoModeSet != null ? selectedVideoModeSet : VideoModeSet.getDummy();
                })
                .setTranslator(resolution -> Component.nullToEmpty(resolution.toString()));

        resolutionOption.setOnChange(() -> {
            var newVideoMode = resolutionOption.getNewValue();
            var newRefreshRates = newVideoMode.getRefreshRates().toArray(new Integer[0]);

            RefreshRate.setValues(newRefreshRates);
            RefreshRate.setNewValue(newRefreshRates[newRefreshRates.length - 1]);
        });

        return new OptionBlock[]{
                new OptionBlock("", new Option<?>[]{
                        new CyclingOption<>(Component.translatable("vulkanmod.options.monitorSelector"),
                                IntStream.range(0, VideoModeManager.getMonitorCount()).boxed()
                                         .toArray(Integer[]::new),
                                value -> {
                                    config.monitorIndex = value;
                                    VideoModeManager.applyMonitorConfig(value);
                                    if (minecraftOptions.fullscreen().get()
                                            || config.windowMode != WindowMode.WINDOWED.mode)
                                        fullscreenDirty = true;
                                },
                                () -> {
                                    int idx = config.monitorIndex;
                                    return (idx >= 0 && idx < VideoModeManager.getMonitorCount()) ? idx : 0;
                                })
                                .setTranslator(value -> Component.nullToEmpty(VideoModeManager.getMonitorName(value))),
                        resolutionOption,
                        RefreshRate,
                        new CyclingOption<>(Component.translatable("vulkanmod.options.windowMode"),
                                WindowMode.values(),
                                value -> {
                                    boolean exclusiveFullscreen = value == WindowMode.EXCLUSIVE_FULLSCREEN;
                                    minecraftOptions.fullscreen()
                                                    .set(exclusiveFullscreen);

                                    config.windowMode = value.mode;
                                    fullscreenDirty = true;
                                },
                                () -> WindowMode.fromValue(config.windowMode))
                                .setTranslator(value -> Component.translatable(WindowMode.getComponentName(value))),
                        new RangeOption(Component.translatable("options.framerateLimit"),
                                        10, 260, 10,
                                        value -> Component.nullToEmpty(value == 260 ?
                                                                               Component.translatable(
                                                                                                "options.framerateLimit.max")
                                                                                        .getString() :
                                                                               String.valueOf(value)),
                                        value -> {
                                            minecraftOptions.framerateLimit().set(value);
                                            minecraft.getFramerateLimitTracker().setFramerateLimit(value);
                                        },
                                        () -> minecraftOptions.framerateLimit().get()),
                        new SwitchOption(Component.translatable("options.vsync"),
                                         value -> {
                                             minecraftOptions.enableVsync().set(value);
                                             window.updateVsync(value);
                                         },
                                         () -> minecraftOptions.enableVsync().get()),
                        new CyclingOption<>(Component.translatable("options.inactivityFpsLimit"),
                                            InactivityFpsLimit.values(),
                                            value -> minecraftOptions.inactivityFpsLimit().set(value),
                                            () -> minecraftOptions.inactivityFpsLimit().get())
                                .setTranslator(inactivityFpsLimit -> Component.translatable(inactivityFpsLimit.getKey()))
                }),
                new OptionBlock("", new Option<?>[]{
                        new RangeOption(Component.translatable("options.guiScale"),
                                        0, window.calculateScale(0, minecraft.isEnforceUnicode()), 1,
                                        value -> Component.translatable((value == 0)
                                                                                ? "options.guiScale.auto"
                                                                                : String.valueOf(value)),
                                        value -> {
                                            minecraftOptions.guiScale().set(value);
                                            minecraft.resizeDisplay();
                                        },
                                        () -> (minecraftOptions.guiScale().get())),
                        new RangeOption(Component.translatable("options.gamma"),
                                        0, 100, 1,
                                        value -> Component.translatable(switch (value) {
                                            case 0 -> "options.gamma.min";
                                            case 50 -> "options.gamma.default";
                                            case 100 -> "options.gamma.max";
                                            default -> String.valueOf(value);
                                        }),
                                        value -> minecraftOptions.gamma().set(value * 0.01),
                                        () -> (int) (minecraftOptions.gamma().get() * 100.0)),
                }),
                new OptionBlock("", new Option<?>[]{
                        new SwitchOption(Component.translatable("options.viewBobbing"),
                                         (value) -> minecraftOptions.bobView().set(value),
                                         () -> minecraftOptions.bobView().get()),
                        new CyclingOption<>(Component.translatable("options.attackIndicator"),
                                            AttackIndicatorStatus.values(),
                                            value -> minecraftOptions.attackIndicator().set(value),
                                            () -> minecraftOptions.attackIndicator().get())
                                .setTranslator(value -> Component.translatable(value.getKey())),
                        new SwitchOption(Component.translatable("options.autosaveIndicator"),
                                         value -> minecraftOptions.showAutosaveIndicator().set(value),
                                         () -> minecraftOptions.showAutosaveIndicator().get()),
                })
        };
    }

    public static OptionBlock[] getGraphicsOpts() {
        return new OptionBlock[]{
                new OptionBlock("", new Option<?>[]{
                        new RangeOption(Component.translatable("options.renderDistance"),
                                        2, 32, 1,
                                        (value) -> minecraftOptions.renderDistance().set(value),
                                        () -> minecraftOptions.renderDistance().get()),
                        new RangeOption(Component.translatable("options.simulationDistance"),
                                        5, 32, 1,
                                        (value) -> minecraftOptions.simulationDistance().set(value),
                                        () -> minecraftOptions.simulationDistance().get()),
                        new CyclingOption<>(Component.translatable("options.prioritizeChunkUpdates"),
                                            PrioritizeChunkUpdates.values(),
                                            value -> minecraftOptions.prioritizeChunkUpdates().set(value),
                                            () -> minecraftOptions.prioritizeChunkUpdates().get())
                                .setTranslator(value -> Component.translatable(value.getKey())),
                }),
                new OptionBlock("", new Option<?>[]{
                        new CyclingOption<>(Component.translatable("options.graphics"),
                                            new GraphicsStatus[]{GraphicsStatus.FAST, GraphicsStatus.FANCY},
                                            value -> minecraftOptions.graphicsMode().set(value),
                                            () -> minecraftOptions.graphicsMode().get())
                                .setTranslator(graphicsMode -> Component.translatable(graphicsMode.getKey())),
                        new CyclingOption<>(Component.translatable("options.particles"),
                                            new ParticleStatus[]{ParticleStatus.MINIMAL, ParticleStatus.DECREASED, ParticleStatus.ALL},
                                            value -> minecraftOptions.particles().set(value),
                                            () -> minecraftOptions.particles().get())
                                .setTranslator(particlesMode -> Component.translatable(particlesMode.getKey())),
                        new CyclingOption<>(Component.translatable("options.renderClouds"),
                                            CloudStatus.values(),
                                            value -> minecraftOptions.cloudStatus().set(value),
                                            () -> minecraftOptions.cloudStatus().get())
                                .setTranslator(value -> Component.translatable(value.getKey())),
                        new RangeOption(Component.translatable("options.renderCloudsDistance"),
                                        2, 128, 1,
                                        (value) -> minecraftOptions.cloudRange().set(value),
                                        () -> minecraftOptions.cloudRange().get()),
                        new CyclingOption<>(Component.translatable("options.ao"),
                                            new Integer[]{LightMode.FLAT, LightMode.SMOOTH, LightMode.SUB_BLOCK},
                                            (value) -> {
                                                if (value > LightMode.FLAT)
                                                    minecraftOptions.ambientOcclusion().set(true);
                                                else
                                                    minecraftOptions.ambientOcclusion().set(false);

                                                config.ambientOcclusion = value;

                                                minecraft.levelRenderer.allChanged();
                                            },
                                            () -> config.ambientOcclusion)
                                .setTranslator(value -> Component.translatable(switch (value) {
                                    case LightMode.FLAT -> "options.off";
                                    case LightMode.SMOOTH -> "options.on";
                                    case LightMode.SUB_BLOCK -> "vulkanmod.options.ao.subBlock";
                                    default -> "vulkanmod.options.unknown";
                                }))
                                .setTooltip(Component.translatable("vulkanmod.options.ao.subBlock.tooltip")),
                        new RangeOption(Component.translatable("options.biomeBlendRadius"),
                                        0, 7, 1,
                                        value -> {
                                            int v = value * 2 + 1;
                                            return Component.nullToEmpty("%d x %d".formatted(v, v));
                                        },
                                        (value) -> {
                                            minecraftOptions.biomeBlendRadius().set(value);
                                            minecraft.levelRenderer.allChanged();
                                        },
                                        () -> minecraftOptions.biomeBlendRadius().get()),
                }),
                new OptionBlock("", new Option<?>[]{
                        new SwitchOption(Component.translatable("options.entityShadows"),
                                         value -> minecraftOptions.entityShadows().set(value),
                                         () -> minecraftOptions.entityShadows().get()),
                        new RangeOption(Component.translatable("options.entityDistanceScaling"),
                                        50, 500, 25,
                                        value -> minecraftOptions.entityDistanceScaling().set(value * 0.01),
                                        () -> minecraftOptions.entityDistanceScaling().get().intValue() * 100),
                        new CyclingOption<>(Component.translatable("options.mipmapLevels"),
                                            new Integer[]{0, 1, 2, 3, 4},
                                            value -> {
                                                minecraftOptions.mipmapLevels().set(value);
                                                minecraft.updateMaxMipLevel(value);
                                                minecraft.delayTextureReload();
                                            },
                                            () -> minecraftOptions.mipmapLevels().get())
                                .setTranslator(value -> Component.nullToEmpty(value.toString()))
                })
        };
    }

    public static OptionBlock[] getOptimizationOpts() {
        return new OptionBlock[]{
                new OptionBlock("", new Option[]{
                        new CyclingOption<>(Component.translatable("vulkanmod.options.advCulling"),
                                            new Integer[]{1, 2, 3, 10},
                                            value -> config.advCulling = value,
                                            () -> config.advCulling)
                                .setTranslator(value -> Component.translatable(switch (value) {
                                    case 1 -> "vulkanmod.options.advCulling.aggressive";
                                    case 2 -> "vulkanmod.options.advCulling.normal";
                                    case 3 -> "vulkanmod.options.advCulling.conservative";
                                    case 10 -> "options.off";
                                    default -> "vulkanmod.options.unknown";
                                }))
                                .setTooltip(Component.translatable("vulkanmod.options.advCulling.tooltip")),
                        new SwitchOption(Component.translatable("vulkanmod.options.entityCulling"),
                                         value -> config.entityCulling = value,
                                         () -> config.entityCulling)
                                .setTooltip(Component.translatable("vulkanmod.options.entityCulling.tooltip")),
                        new SwitchOption(Component.translatable("vulkanmod.options.uniqueOpaqueLayer"),
                                         value -> {
                                             config.uniqueOpaqueLayer = value;
                                             TerrainRenderType.updateMapping();
                                             minecraft.levelRenderer.allChanged();
                                         },
                                         () -> config.uniqueOpaqueLayer)
                                .setTooltip(Component.translatable("vulkanmod.options.uniqueOpaqueLayer.tooltip")),
                        new SwitchOption(Component.translatable("vulkanmod.options.backfaceCulling"),
                                         value -> {
                                             config.backFaceCulling = value;
                                             Minecraft.getInstance().levelRenderer.allChanged();
                                         },
                                         () -> config.backFaceCulling)
                                .setTooltip(Component.translatable("vulkanmod.options.backfaceCulling.tooltip")),
                        new SwitchOption(Component.translatable("vulkanmod.options.indirectDraw"),
                                         value -> config.indirectDraw = value,
                                         () -> config.indirectDraw)
                                .setTooltip(Component.translatable("vulkanmod.options.indirectDraw.tooltip")),
                })
        };

    }

    public static OptionBlock[] getOtherOpts() {
        return new OptionBlock[]{
                new OptionBlock("", new Option[]{
                        new RangeOption(Component.translatable("vulkanmod.options.builderThreads"),
                                        0, (Runtime.getRuntime().availableProcessors() - 1), 1,
                                        value -> {
                                            config.builderThreads = value;
                                            WorldRenderer.getInstance().getTaskDispatcher().createThreads(value);
                                        },
                                        () -> config.builderThreads)
                                .setTranslator(value -> {
                            if (value == 0)
                                return Component.translatable("vulkanmod.options.builderThreads.auto");
                            else
                                return Component.nullToEmpty(String.valueOf(value));
                        }),
                        new RangeOption(Component.translatable("vulkanmod.options.frameQueue"),
                                        2, 5, 1,
                                        value -> {
                                            config.frameQueueSize = value;
                                            Renderer.scheduleSwapChainUpdate();
                                        }, () -> config.frameQueueSize)
                                .setTooltip(Component.translatable("vulkanmod.options.frameQueue.tooltip")),
                        new SwitchOption(Component.translatable("vulkanmod.options.textureAnimations"),
                                         value -> {
                                             config.textureAnimations = value;
                                         },
                                         () -> config.textureAnimations),
                }),
                new OptionBlock("", new Option[]{
                        new CyclingOption<>(Component.translatable("vulkanmod.options.deviceSelector"),
                                            IntStream.range(-1, DeviceManager.suitableDevices.size()).boxed()
                                                     .toArray(Integer[]::new),
                                            value -> config.device = value,
                                            () -> config.device)
                                .setTranslator(value -> Component.translatable((value == -1)
                                                                                       ? "vulkanmod.options.deviceSelector.auto"
                                                                                       : DeviceManager.suitableDevices.get(
                                        value).deviceName)
                                )
                                .setTooltip(Component.nullToEmpty("%s: %s".formatted(
                                Component.translatable("vulkanmod.options.deviceSelector.tooltip").getString(),
                                DeviceManager.device.deviceName)))
                })
        };

    }

    public static OptionBlock[] getShaderOpts() {
        return new OptionBlock[] {
                new OptionBlock("", new Option[] {
                        new CyclingOption<>(Component.literal("Shader Pack"),
                                ShaderPackManager.getAvailableShaderPacks(),
                                value -> {
                                    config.shaderPack = value;
                                    ShaderPackManager.reloadActiveShaderPack();
                                },
                                () -> {
                                    String shaderPack = config.shaderPack;
                                    if (shaderPack == null || shaderPack.isBlank()) {
                                        return ShaderPackManager.INTERNAL_PACK;
                                    }
                                    return shaderPack;
                                })
                                .setTranslator(Component::literal)
                                .setTooltip(Component.literal("Select a shader pack from the Minecraft shaders folder and press Apply to reload shaders."))
                })
        };
    }
}

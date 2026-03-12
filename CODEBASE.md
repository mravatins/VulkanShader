# CODEBASE Findings

## Scope
This document summarizes the current architecture and behavior of the `VulkanModShader` codebase after a full pass through core runtime, rendering, chunk build, shader, and mixin systems.

## High-Level Summary
- This is a Fabric client mod that replaces Minecraft's GL-centric render path with a Vulkan backend.
- The project is heavily mixin-driven (`82` mixin classes) and runtime behavior depends on those overrides.
- Core rendering logic is split between:
  - `net.vulkanmod.vulkan` (low-level Vulkan objects, frame lifecycle, memory, synchronization)
  - `net.vulkanmod.render` (chunk rendering, shader loading, modern MC `GpuDevice` bridge)
  - `net.vulkanmod.gl` + `mixin.compatibility.gl` (OpenGL compatibility shim to keep vanilla/mod paths functional)
- Shader handling supports both:
  - legacy/basic Vulkan pipelines from `assets/vulkanmod/shaders/basic/*`
  - modern `RenderPipeline` integration via custom `VkGpuDevice` / `VkCommandEncoder`.

## Tech and Build Baseline
- Minecraft: `1.21.10`
- Fabric Loader: `0.18.4`
- Fabric API: `0.138.4+1.21.10`
- Java target: `21`
- Loom: `1.15.4`
- LWJGL Vulkan + VMA + shaderc included directly in mod dependencies.

## Module Layout
- `config` (`24` files): user config, video modes, options UI wiring, update check.
- `vulkan` (`64` files): instance/device/swapchain/framebuffers/pipelines/memory/sync/queues.
- `render` (`108` files): chunk renderer, section graph, builders, pipeline manager, profiling, new GPU-device bridge.
- `gl` (`7` files): GL object emulation wrappers.
- `mixin` (`82` files): interception layer for Minecraft and LWJGL behavior.

## Runtime Boot Flow
1. Fabric entrypoint: `Initializer.onInitializeClient()`.
2. Loads mod version + platform + video modes.
3. Loads/writes config at `config/vulkanmod_settings.json`.
4. Registers Fabric renderer API implementation (`VulkanModRenderer`).
5. Starts async Modrinth update check.

Then during render startup:
1. `WindowMixin` forces GLFW `NO_API` (no OpenGL context), captures native window handle.
2. `RenderSystemMixin.initRenderer(...)` is overwritten:
   - calls `VRenderSystem.initRenderer()` -> `Vulkan.initVulkan(window)`
   - sets `RenderSystem.DEVICE = new VkGpuDevice(...)`
   - creates renderer singleton via `Renderer.initRenderer()`.
3. Frame begin/end is redirected:
   - begin: `mixin.render.frame.MinecraftMixin` calls `Renderer.beginFrame()`
   - end: `mixin.render.frame.RenderSystemMixin` redirects `glfwSwapBuffers` to `Renderer.endFrame()`.

## Vulkan Core (`net.vulkanmod.vulkan`)

### Instance/Device/Swapchain
- `Vulkan`:
  - creates instance/surface/device allocator (VMA), command pool, staging buffers.
  - validation layers are present but disabled by default (`ENABLE_VALIDATION_LAYERS=false`).
  - dynamic rendering flag exists but is disabled (`DYNAMIC_RENDERING=false`).
- `DeviceManager`:
  - enumerates devices, filters suitability, auto-selects discrete GPU if possible.
  - builds queue families (graphics/present/transfer/compute).
  - toggles features like wide lines and indirect draw support.
- `SwapChain`:
  - creates color images + depth attachment.
  - handles minimized window case (`width/height == 0`) by dropping active swapchain.
  - supports vsync present mode switching and fallback logic.

### Frame Lifecycle
- `Renderer` owns:
  - per-frame command buffers, semaphores/fences, drawer buffers.
  - `MainPass` and `ShadowPass`.
  - resize callbacks and swapchain recreation.
- Frame pattern:
  - `preInitFrame()` resets per-frame buffers + submits pending chunk/texture uploads.
  - `beginFrame()` acquires swapchain image and starts command recording.
  - render passes execute.
  - `endFrame()` submits + presents + advances frame index.

### Memory and Synchronization
- `MemoryManager` (VMA-backed):
  - tracks all buffers/images in maps.
  - delayed free model indexed by frame-in-flight.
  - frame ops queue for deferred cleanup actions.
- `Synchronization`:
  - manages fence/semaphore wait lists for background transfer and frame dependencies.

### Draw Path
- `Drawer` owns per-frame host-visible vertex/index/uniform buffers.
- Uses auto-index generators for quads/lines/fans/strips.
- Records Vulkan draw calls directly on currently active command buffer.

## Shader/Pipeline System

### Basic Terrain Pipelines
- `PipelineManager` creates fixed pipelines (`terrain`, `terrain_earlyZ`, `terrain_water`, `blit`, `clouds`, `shadow`, `entity_shadow`).
- Config is JSON-driven from `assets/vulkanmod/shaders/basic/*/*.json`.
- Shader sources are compiled to SPIR-V at runtime (`SPIRVUtils` + shaderc).

### Pipeline Object Model
- `Pipeline`:
  - descriptor set layout + pipeline layout + per-frame descriptor pools/sets.
  - supports UBOs, manual UBOs, samplers, push constants.
- `GraphicsPipeline`:
  - caches Vulkan pipeline handles keyed by `PipelineState`.
  - dynamic state includes viewport/scissor/depth bias/line width (when relevant).

### Descriptor Behavior
- `DescriptorSets` tracks previously bound UBO/image states and reuses sets when possible.
- Uses dynamic offsets into global per-frame uniform buffer when UBOs are global.

### Shader Loading Utilities
- `ShaderLoadUtil` resolves shader/config paths inside mod resources and compiles sources.
- Includes remap support for selected core shaders (`core/screenquad.vsh`, `core/rendertype_item_entity_translucent_cull.vsh`).

## Modern Minecraft `GpuDevice` Bridge
- `VkGpuDevice` implements Mojang `GpuDevice`.
- `VkCommandEncoder` implements command encoder + render pass behavior expected by modern MC rendering APIs.
- `EGlProgram`/`ExtendedRenderPipeline` attach Vulkan pipeline/program metadata to `RenderPipeline`.
- `ShaderManagerM` precompiles extra custom pipelines registered in `CustomRenderPipelines`.

This is important: the project is not only replacing old GL calls; it is also integrating with modern `RenderPipeline` and `GpuTexture` abstractions.

## Chunk Rendering Architecture

### Core Objects
- `WorldRenderer`: top-level terrain/chunk render coordinator.
- `SectionGrid`: fixed-size moving 3D grid of `RenderSection` objects around camera.
- `SectionGraph`: BFS-like visibility traversal + frustum + rebuild scheduling.
- `TaskDispatcher`: multithreaded compile task scheduler (high/low priority queues).
- `BuildTask`: block/fluid mesh generation + visibility + block entity collection.
- `DrawBuffers`: per-chunk-area packed vertex/index storage + indirect draw command building.

### Key Flow
1. Camera move/frustum update triggers `SectionGraph.update(...)`.
2. Dirty sections schedule `BuildTask` work on builder threads.
3. `CompileResult` gets queued to main thread via `TaskDispatcher.compileResults`.
4. Main thread uploads to `DrawBuffers` through `UploadManager`.
5. Terrain draws use direct/indirect draw path depending on config/device features.

### Performance Features
- configurable advanced culling aggressiveness.
- optional unique opaque layer compaction.
- optional indirect draw mode.
- custom queues and compact data structures (`StaticQueue`, `ResettableQueue`, etc.).

## GL Compatibility Layer
- `mixin.compatibility.gl.*` overwrites many LWJGL `GL11/14/15/30` methods.
- `gl.*` classes emulate textures/buffers/framebuffers/renderbuffers and map operations to Vulkan equivalents.
- This keeps vanilla + mod code paths that still call GL APIs from crashing immediately.

Important caveat: many GL calls are stubbed/no-op/partial. Compatibility is pragmatic, not complete.

## Mixin Strategy and Impact
- `vulkanmod.mixins.json` includes wide coverage across:
  - window and frame loop
  - render system
  - chunk renderer
  - shader manager/pipelines
  - texture update paths
  - entity/cloud/particle/fog/GUI paths
  - compatibility and profiling paths
- Many mixins use `@Overwrite`, which is powerful but high-maintenance across Minecraft updates.

## Config and User Controls
- Persisted config includes:
  - video mode/window mode/GPU selector
  - culling and chunk options
  - frame queue size and builder threads
  - AO mode, texture animations, backface culling
- Options GUI uses custom widgets (`config.gui.*`) and writes to `Initializer.CONFIG`.

## Notable Operational Behaviors
- Fabulous graphics mode is forcibly downgraded to Fancy.
- Main target handling is heavily customized (`MainTargetMixin`, `RenderTargetMixin`).
- Depth far is overwritten to `Float.POSITIVE_INFINITY` (`GameRendererMixin`).
- Resize/minimize handling relies on deferred swapchain recreation.

## Current Risk Areas / Sharp Edges
- High number of `TODO`/`FIXME`/`UnsupportedOperationException` in render + GL bridge paths.
- Many `@Overwrite`s increase breakage risk when updating MC/Fabric internals.
- Some features are explicitly partial:
  - dynamic rendering is disabled
  - several `VkCommandEncoder` paths are TODO/unsupported
  - texture mipmap generation path has known crash comment
  - some GL state queries return placeholders (e.g., `glGetError -> 0`)
- Runtime shader compilation can add startup/runtime complexity and debugging overhead.

## Where to Start for a Shader-Focused Fork
If your main goal is evolving shader support, the most relevant codepaths are:
- Shader asset configs/sources:
  - `src/main/resources/assets/vulkanmod/shaders/basic/*`
  - `src/main/resources/assets/vulkanmod/shaders/include/*`
- Pipeline creation and binding:
  - `render/PipelineManager.java`
  - `vulkan/shader/Pipeline.java`
  - `vulkan/shader/GraphicsPipeline.java`
  - `vulkan/shader/DescriptorSets.java`
- Source loading/remapping:
  - `render/shader/ShaderLoadUtil.java`
  - `render/shader/CustomRenderPipelines.java`
- Modern render-pipeline bridge:
  - `render/engine/VkGpuDevice.java`
  - `render/engine/VkCommandEncoder.java`
  - `mixin/render/shader/*`

## Practical Mental Model
- Think of this codebase as a Vulkan rendering platform grafted into Minecraft through mixins.
- There are two rendering adaptation layers:
  - low-level Vulkan engine for chunk/world rendering and passes.
  - compatibility layer for GL-era and modern Mojang render APIs.
- Most bugs and regressions will come from synchronization between those layers, especially around frame boundaries, resource lifetimes, and texture/framebuffer transitions.

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

VulkanMod is a Fabric mod for Minecraft Java 1.21.10 that replaces the default OpenGL renderer with a custom Vulkan 1.2 rendering engine. It is a full renderer rewrite — not a translation layer like Zink.

## Build & Run Commands

```bash
# Build the mod jar
./gradlew build

# Launch Minecraft client with the mod (development environment)
./gradlew runClient

# Clean build artifacts
./gradlew clean
```

There are no unit tests. Testing is done by running the game via `runClient`.

Key versions (see `gradle.properties`):
- Minecraft: `1.21.10`
- Fabric Loader: `0.17.3`
- Java: 21
- LWJGL: `3.3.3` (with Vulkan, VMA, and shaderc bindings)

## Architecture

The codebase has four main packages under `net.vulkanmod`:

### `vulkan/` — Core Vulkan backend
- **`Vulkan.java`** — Instance, device selection, surface, VMA allocator initialization. `ENABLE_VALIDATION_LAYERS` flag here to toggle debug layers.
- **`Renderer.java`** — Per-frame render loop: acquire swapchain image, submit command buffers, present. Manages in-flight frames and swapchain recreation.
- **`VRenderSystem.java`** — Static render state (depth test, cull, topology, blend mode) that mirrors what `RenderSystem` provides in vanilla. Mixins redirect GL state calls here.
- **`device/`** — `DeviceManager` selects physical device; `Device` wraps logical device and queues.
- **`memory/`** — VMA-based allocator; `MemoryTypes` defines GPU/CPU/staging memory types; `buffer/` has typed buffer wrappers (`VertexBuffer`, `IndexBuffer`, `UniformBuffer`, `StagingBuffer`, `IndirectBuffer`).
- **`shader/`** — `GraphicsPipeline` creates/caches Vulkan pipelines from JSON descriptors + GLSL source (compiled via shaderc to SPIRV at runtime via `SPIRVUtils`). `PipelineState` tracks dynamic state. `Uniforms` manages UBO binding.
- **`framebuffer/`** — `SwapChain` and `RenderPass`/`Framebuffer` wrappers.
- **`queue/`** — `GraphicsQueue`, `TransferQueue`, `ComputeQueue`, `PresentQueue` each wrap a `VkQueue` with a dedicated `CommandPool`.
- **`pass/`** — `MainPass`/`DefaultMainPass` orchestrate the main render pass sequence.

### `render/` — Rendering logic
- **`PipelineManager.java`** — Manages the set of active `GraphicsPipeline`s for all render types; selects the right pipeline per draw call.
- **`chunk/`** — Custom world renderer replacing `LevelRenderer`:
  - `WorldRenderer.java` — Drives chunk culling and draw submission.
  - `graph/SectionGraph.java` — BFS/graph-based chunk visibility graph.
  - `cull/` — Frustum and occlusion culling.
  - `build/` — Async chunk mesh building pipeline (`TaskDispatcher`, `ChunkTask`, FRAPI integration in `frapi/`).
  - `buffer/` — `AreaBuffer` pools geometry; `DrawBuffers` + `DrawParametersBuffer` support indirect draw; `UploadManager` handles async GPU uploads.
- **`engine/`** — Blaze3D integration points: `VkCommandEncoder`, `VkGpuBuffer`, `VkGpuDevice`, `VkGpuTexture` implement the abstract GPU interfaces Minecraft uses.
- **`vertex/`** — `TerrainRenderType`, `TerrainBufferBuilder`, vertex format definitions.
- **`texture/`** — Texture upload helpers.
- **`sky/`, `model/`, `profiling/`** — Sky rendering, model rendering, frame/build-time profilers.

### `mixin/` — Minecraft mixins
Intercepts vanilla rendering code to redirect to the Vulkan backend:
- `render/` — Mixins on `RenderSystem`, `GlStateManager`, `GameRenderer`, `MinecraftMixin`, render types, fog, GUI, etc.
- `compatibility/` — Compatibility fixes for vanilla GL calls.
- `gl/` — Mixins on GL program/shader classes.
- `window/` — Window/fullscreen handling.
- `wayland/` — Native Wayland support.

### `gl/` — OpenGL compatibility shim
`VkGl*` classes (`VkGlBuffer`, `VkGlFramebuffer`, `VkGlProgram`, `VkGlShader`, `VkGlTexture`) implement vanilla's GL abstractions on top of Vulkan, allowing code paths that haven't been fully migrated to work transparently.

### `interfaces/` — Mixin accessor interfaces
`Extended*` interfaces added to vanilla classes via mixins (e.g., `ExtendedRenderType`, `VertexFormatMixed`, `FrustumMixed`).

### `config/` — Settings
`Config` loads/saves `vulkanmod_settings.json`. `Platform` detects OS/GPU. `VideoModeManager` handles display modes.

## Shaders

Shaders live in `src/main/resources/assets/vulkanmod/shaders/` organized as:
- `basic/` — Custom VulkanMod shaders (terrain, terrain_earlyZ, clouds, sun, blit, shadow)
- `core/` — Replacements for vanilla core shaders
- `include/` — Shared GLSL includes
- `post/` — Post-processing shaders

Each shader directory has a JSON descriptor and GLSL `.vsh`/`.fsh` files. SPIRV compilation happens at runtime via shaderc.

## Key Development Notes

- **Validation layers**: Toggle `ENABLE_VALIDATION_LAYERS` in `Vulkan.java` for Vulkan debug output.
- **Indirect draw**: The terrain renderer supports indirect draw mode (controlled by config), which batches draw calls using `IndirectBuffer`.
- **Mojang mappings**: Uses official Mojang mappings (`loom.officialMojangMappings()`), not Yarn mappings (the `yarn_mappings` property in `gradle.properties` is unused).
- **Access widener**: `src/main/resources/vulkanmod.accesswidener` widens access to Minecraft internals needed by the renderer.
- The `run/` directory contains the development game instance data and is not part of the mod source.

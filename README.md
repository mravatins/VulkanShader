# VulkanModShader

`VulkanModShader` is my in-progress fork focused on building my own shader workflow on top of a Vulkan-based Minecraft renderer.

This repository targets:
- Minecraft `1.21.10`
- Fabric Loader `0.18.4+`
- Java `21`

## Current Status

Active development. APIs, rendering behavior, and assets can change frequently.

## Project Goals

- Build custom shader pipelines for Minecraft rendering.
- Keep performance-oriented Vulkan rendering as the foundation.
- Experiment quickly while keeping the codebase clean and maintainable.

## Build From Source

```bash
./gradlew build
```

Built jars are output to `build/libs/`.

## Development Run

```bash
./gradlew runClient
```

## Shader Pack Development

External shader packs can be loaded from the Minecraft `shaders` folder.

- Detailed authoring guide: [`docs/SHADER_PACKS.md`](/home/nsunil/git/VulkanShader/docs/SHADER_PACKS.md)
- In the dev environment, the shader folder is `run/shaders/`
- In-game, open `Video Settings -> Shaders`, pick a pack, then press `Apply`

Both folder-based packs and `.zip` packs are supported.

## Install (Local Build)

1. Build the mod with `./gradlew build`.
2. Copy the produced jar from `build/libs/` into your `.minecraft/mods` folder.
3. Launch Minecraft with Fabric for the matching game version.

## Notes

- This is a personal fork and may diverge significantly from upstream behavior.
- Expect breaking changes while shader systems are being developed.

## Credit

Credit to the original project: this started as a fork of [xCollateral/VulkanMod](https://github.com/xCollateral/VulkanMod).

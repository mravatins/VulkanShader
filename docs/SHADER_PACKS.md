# Shader Pack Authoring

This mod can load external shader packs from the Minecraft `shaders` folder instead of relying only on the built-in shaders packaged in the mod jar.

This document explains how to build a shader pack for the current loader, how files are resolved, how the JSON pipeline format works, and how to test and reload packs while developing.

## Where Shader Packs Live

Shader packs are loaded from the Minecraft game directory:

- Normal install: `.minecraft/shaders/`
- Dev environment for this repo: `run/shaders/`

You can install a pack as either:

- A folder
- A `.zip` file

Examples:

```text
.minecraft/
  shaders/
    MyShaderPack/
    AnotherPack.zip
```

The built-in pack is always available in the menu as `Internal`.

## How To Select A Shader Pack

In game:

1. Open `Video Settings`
2. Open `Shaders`
3. Select the pack
4. Press `Apply`

Applying the option reloads the pipelines and refreshes the world renderer.

## Pack Layout

The loader currently looks for shader files relative to the pack root using this structure:

```text
MyShaderPack/
  basic/
    terrain/
      terrain.json
      terrain.vsh
      terrain.fsh
    terrain_water/
      terrain_water.json
      terrain_water.vsh
      terrain_water.fsh
    shadow/
      shadow.json
      shadow.vsh
      shadow.fsh
  include/
    fog.glsl
    lighting.glsl
```

The `basic/` folder mirrors the built-in shader tree under:

```text
src/main/resources/assets/vulkanmod/shaders/basic/
```

If you want to override only one shader, you only need to include the files you want to replace. Missing files fall back to the built-in versions shipped with the mod.

That means a valid pack can be extremely small:

```text
MyShaderPack/
  basic/
    terrain/
      terrain.fsh
```

In that example, everything else still comes from the built-in shaders.

## Folder And Zip Packs

Both layouts work:

### Folder Pack

```text
shaders/
  MyShaderPack/
    basic/
    include/
```

### Zip Pack

```text
shaders/
  MyShaderPack.zip
```

The zip can either contain the files at the root:

```text
basic/terrain/terrain.fsh
include/common.glsl
```

or inside a top-level folder:

```text
MyShaderPack/basic/terrain/terrain.fsh
MyShaderPack/include/common.glsl
```

The loader accepts both.

## Resolution Rules

The loader resolves files in this order:

1. The selected external shader pack
2. The built-in shader files packaged with the mod

That fallback behavior applies to:

- Pipeline JSON files
- Vertex shaders
- Fragment shaders
- `#include` files

This is useful while developing because you can override a single file, reload, and leave the rest of the renderer on the internal defaults.

## Pipeline JSON Format

Each shader pipeline is described by a `.json` file. The built-in examples to study first are:

- `src/main/resources/assets/vulkanmod/shaders/basic/terrain/terrain.json`
- `src/main/resources/assets/vulkanmod/shaders/basic/terrain_water/terrain_water.json`
- `src/main/resources/assets/vulkanmod/shaders/basic/shadow/shadow.json`

A typical pipeline JSON includes:

- `vertex`: the vertex shader name
- `fragment`: the fragment shader name
- `samplers`: texture bindings
- `UBOs`: uniform buffer layouts
- `PushConstants`: push-constant layout

Example:

```json
{
  "vertex": "terrain",
  "fragment": "terrain",
  "samplers": [
    { "name": "Sampler0" },
    { "name": "Sampler2" },
    { "name": "Sampler3", "layout": "depth" }
  ],
  "UBOs": [
    {
      "type": "vertex",
      "binding": 0,
      "fields": [
        {
          "name": "MVP",
          "type": "matrix4x4",
          "count": 16,
          "values": [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]
        }
      ]
    },
    {
      "type": "fragment",
      "binding": 1,
      "fields": [
        {
          "name": "FogColor",
          "type": "float",
          "count": 4,
          "values": [0, 0, 0, 0]
        }
      ]
    }
  ],
  "PushConstants": [
    {
      "name": "ModelOffset",
      "type": "float",
      "count": 3,
      "values": [0, 0, 0]
    }
  ]
}
```

## Important JSON Rules

Your GLSL and your JSON have to match.

That means:

- Sampler bindings must line up with the order of `samplers`
- UBO bindings must line up with `binding`
- Field order and type layout must match what the shader expects
- Push constants must match the shader declaration

If the JSON says a field exists and the shader does not match its layout, the pipeline may compile incorrectly or fail at runtime.

## Samplers

Each sampler entry declares a texture slot the pipeline expects.

Examples:

```json
{ "name": "Sampler0" }
{ "name": "Sampler3", "layout": "depth" }
```

Notes:

- `layout: "depth"` is used for depth textures
- The numeric part of the sampler name is significant because the renderer binds textures by slot
- Do not renumber samplers casually unless you are also changing the renderer side that binds them

If you are only changing shading logic and not pipeline bindings, copy the built-in sampler list exactly.

## UBOs

UBOs declare uniform buffer layout for vertex or fragment stages.

Fields currently used by built-in shaders include things like:

- `MVP`
- `FogColor`
- `Light0_Direction`
- `Light1_Direction`
- `LightSpaceMat`
- `ModelViewMat`
- `ProjMat`
- `ScreenSize`
- `Time`
- `CamPos`

Supported field styles shown in current shaders:

- `float`
- `matrix4x4`

When authoring a pack, the safest path is:

1. Start from the built-in JSON for the pipeline you are overriding
2. Keep the bindings and field layout unchanged
3. Only change the GLSL behavior first
4. Expand the pipeline format only when you also know the Java side provides the new values

## Push Constants

Push constants are declared in the JSON and referenced in the shader. The terrain pipelines currently use:

```json
{
  "name": "ModelOffset",
  "type": "float",
  "count": 3,
  "values": [0, 0, 0]
}
```

Again, the shader declaration must match the pipeline definition.

## Shader File Naming

A pipeline named `terrain` usually resolves files like this:

```text
basic/terrain/terrain.json
basic/terrain/terrain.vsh
basic/terrain/terrain.fsh
```

The loader also supports a few fallback naming patterns internally, but you should follow the standard layout above unless you have a strong reason not to.

That will save time and avoid confusing path bugs.

## Includes

The shader compiler supports `#include`.

Custom include files should live under:

```text
include/
```

Example:

```glsl
#include "fog.glsl"
```

Resolution order for includes:

1. `include/` inside the active shader pack
2. Built-in include files in the mod resources

That means you can:

- add a pack-specific include file
- override a built-in include file
- reuse built-in includes without copying them

## Minimal Override Workflow

If you want to make a quick pack, do this:

1. Copy the built-in shader you want to override
2. Put it under the matching `basic/...` path in your pack
3. Select the pack in game
4. Press `Apply`
5. Iterate

Example:

```text
MyPack/
  basic/
    terrain_water/
      terrain_water.fsh
```

That pack overrides only the water fragment shader.

## Recommended Authoring Workflow

Start small.

Recommended order:

1. Override only one `.fsh` file
2. Keep the built-in `.json`
3. Verify the pack loads
4. Add `#include` files for shared code
5. Override `.json` only when you need different samplers or uniforms

This reduces the number of moving parts while you are debugging.

## Example: Simple Terrain Color Override

Folder structure:

```text
MyPack/
  basic/
    terrain/
      terrain.fsh
```

If your custom `terrain.fsh` compiles, the renderer will use it while still falling back to:

- built-in `terrain.vsh`
- built-in `terrain.json`
- built-in includes not overridden by your pack

## Example: Full Pipeline Override

If you need a custom pipeline layout:

```text
MyPack/
  basic/
    terrain_water/
      terrain_water.json
      terrain_water.vsh
      terrain_water.fsh
  include/
    water_common.glsl
```

Use this only when necessary. Once you override the `.json`, you are responsible for keeping it compatible with the engine-side bindings.

## Debugging Failed Packs

If a pack does not work, check these first:

- Wrong folder root inside the pack
- Wrong file names
- Missing `.json` when you changed pipeline layout
- Missing include file
- Sampler binding mismatch
- UBO field mismatch
- GLSL compile error

Practical advice:

- Start from a known working built-in shader
- Change one thing at a time
- Keep pack overrides as small as possible until the pipeline loads cleanly

## Current Built-In Pipelines To Study

Good starting points in this repo:

- `src/main/resources/assets/vulkanmod/shaders/basic/terrain/`
- `src/main/resources/assets/vulkanmod/shaders/basic/terrain_water/`
- `src/main/resources/assets/vulkanmod/shaders/basic/shadow/`
- `src/main/resources/assets/vulkanmod/shaders/basic/entity_shadow/`
- `src/main/resources/assets/vulkanmod/shaders/basic/blit/`
- `src/main/resources/assets/vulkanmod/shaders/basic/clouds/`

These are the real reference implementations for the current loader.

## Example Dev Setup

In this repo, a quick test pack can be dropped here:

```text
run/shaders/MyPack/
```

or:

```text
run/shaders/MyPack.zip
```

Then:

1. Run `./gradlew runClient`
2. Open the shader menu
3. Select the pack
4. Press `Apply`

## Best Practices

- Keep the same file layout as the built-in shaders
- Avoid changing sampler indices unless you are also changing engine bindings
- Reuse built-in JSON where possible
- Put shared GLSL in `include/`
- Test overrides one pipeline at a time
- Package finished packs as `.zip` for easier distribution

## Current Limitations

This loader is still oriented around the mod's existing pipeline model. That means:

- It is not a drop-in Iris or OptiFine shader format
- Pack authors currently work against this repo's `basic/` pipeline layout
- The pack format will evolve as more renderer features are exposed

If you are building a pack, treat the built-in shaders in this repo as the source of truth.

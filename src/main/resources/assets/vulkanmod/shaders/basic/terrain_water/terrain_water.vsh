#version 460

#include "light.glsl"
#include "fog.glsl"

layout (binding = 0) uniform UniformBufferObject {
    mat4  MVP;
    float Time;
    // 12 bytes implicit std140 padding between float and vec3
    vec3  CamPos;
};

layout (push_constant) uniform pushConstant {
    vec3 ModelOffset;
};

// Lightmap at binding 4; binding 3 is SceneColor (fragment), binding 2 is block atlas (fragment)
layout (binding = 4) uniform sampler2D Sampler2;

layout (location = 0) out vec4 vertexColor;
layout (location = 1) out vec2 texCoord0;
layout (location = 2) out float sphericalVertexDistance;
layout (location = 3) out float cylindricalVertexDistance;
layout (location = 4) out vec3 worldPos;
layout (location = 5) out vec2 worldXZ;    // world-space XZ for wave functions

#define COMPRESSED_VERTEX

#ifdef COMPRESSED_VERTEX
    layout (location = 0) in ivec4 Position;
    layout (location = 1) in uvec2 UV0;
    layout (location = 2) in uint PackedColor;
#else
    layout (location = 0) in vec3 Position;
    layout (location = 1) in vec4 Color;
    layout (location = 2) in vec2 UV0;
    layout (location = 3) in ivec2 UV2;
    layout (location = 4) in vec3 Normal;
#endif

const float UV_INV = 1.0 / 32768.0;
const vec3 POSITION_INV = vec3(1.0 / 2048.0);

vec3 getVertexPosition() {
    const vec3 baseOffset = bitfieldExtract(ivec3(gl_InstanceIndex) >> ivec3(0, 16, 8), 0, 8);
    return fma(Position.xyz, POSITION_INV, ModelOffset + baseOffset);
}


void main() {
    vec3 pos = getVertexPosition();

    // worldXZ is world-space (camera-relative pos + camera world position)
    vec2 wxz = pos.xz + CamPos.xz;

    gl_Position = MVP * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);

    const vec4 Color = unpackUnorm4x8(PackedColor);
    vertexColor = Color * sample_lightmap2(Sampler2, Position.a);

    texCoord0 = UV0 * UV_INV;
    worldPos = pos;
    worldXZ  = wxz;
}

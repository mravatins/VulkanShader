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

layout (binding = 4) uniform sampler2D Sampler2;

layout (location = 0) out vec4 vertexColor;
layout (location = 1) out vec2 texCoord0;
layout (location = 2) out float sphericalVertexDistance;
layout (location = 3) out float cylindricalVertexDistance;
layout (location = 4) out vec3 worldPos;
layout (location = 5) out vec2 worldXZ;

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

// Returns a Y displacement for a given XZ world position and time
float waveHeight(vec2 wxz, float t) {
    float w1 = sin(wxz.x * 1.20 + t * 1.1) * cos(wxz.y * 0.90 + t * 0.7) * 0.030;
    float w2 = sin(wxz.x * 2.10 - t * 0.8) * sin(wxz.y * 1.80 + t * 1.3) * 0.015;
    float w3 = cos(wxz.x * 0.50 + t * 0.3) * cos(wxz.y * 0.40 - t * 0.25) * 0.020; // slow swell
    return w1 + w2 + w3;
}

void main() {
    vec3 pos = getVertexPosition();

    // World-space XZ (camera-relative + cam world pos)
    vec2 wxz = pos.xz + CamPos.xz;

    // --- 3D vertex displacement ---
    // Primary Y wave
    float dy = waveHeight(wxz, Time);

    // Slight XZ ripple to give horizontal sway, adds to the 3D feel
    float dx = sin(wxz.y * 1.5 + Time * 0.9) * 0.004;
    float dz = cos(wxz.x * 1.3 - Time * 0.7) * 0.004;

    pos.y += dy;
    pos.x += dx;
    pos.z += dz;

    gl_Position = MVP * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);

    const vec4 Color = unpackUnorm4x8(PackedColor);
    vertexColor = Color * sample_lightmap2(Sampler2, Position.a);

    texCoord0 = UV0 * UV_INV;
    worldPos  = pos;
    worldXZ   = wxz;
}
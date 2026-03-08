#version 450

#include "light.glsl"
#include "fog.glsl"

layout(binding = 2) uniform sampler2D Sampler0;

layout(binding = 1) uniform UBO {
    vec4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
    float AlphaCutout;
    // std140: 7 floats = 28 bytes after vec4, total 44 bytes -> implicit 4-byte pad -> mat4 at offset 48
    mat4 LightSpaceMat;
};

layout(binding = 4) uniform sampler2D ShadowSampler;

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;
layout(location = 2) in float sphericalVertexDistance;
layout(location = 3) in float cylindricalVertexDistance;
layout(location = 4) in vec3 worldPos;

layout(location = 0) out vec4 fragColor;

float computeShadow(vec3 pos) {
    vec4 lightSpacePos = LightSpaceMat * vec4(pos, 1.0);
    vec3 projCoords = lightSpacePos.xyz / lightSpacePos.w;
    vec2 shadowCoords = projCoords.xy * 0.5 + 0.5;
    float currentDepth = projCoords.z; // already in [0, 1]: Vulkan/GL_ZERO_TO_ONE convention

    if (shadowCoords.x < 0.0 || shadowCoords.x > 1.0 ||
        shadowCoords.y < 0.0 || shadowCoords.y > 1.0 ||
        currentDepth < 0.0 || currentDepth > 1.0) return 0.0;

    float pcfDepth = texture(ShadowSampler, shadowCoords).r;
    return (currentDepth > pcfDepth) ? 1.0 : 0.0;
}

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    if (color.a < AlphaCutout) {
        discard;
    }
    float shadow = computeShadow(worldPos);
    color.rgb *= 1.0 - 0.5 * shadow;
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

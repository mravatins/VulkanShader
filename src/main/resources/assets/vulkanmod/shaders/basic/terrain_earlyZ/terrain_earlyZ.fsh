#version 450

layout(early_fragment_tests) in;

#include "light.glsl"
#include "fog.glsl"

layout(binding = 2) uniform sampler2D Sampler0;

layout(binding = 1) uniform UBO {
    vec4  FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
    float AlphaCutout;
};

layout(location = 0) in vec4  vertexColor;
layout(location = 1) in vec2  texCoord0;
layout(location = 2) in float sphericalVertexDistance;
layout(location = 3) in float cylindricalVertexDistance;
layout(location = 4) in vec3  worldPos;
layout(location = 5) in float vertexAO;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;

    // Correct alpha cutout — without this, leaves/fences write wrong depth
    if (color.a < AlphaCutout) discard;

    // Face shading — same as terrain.fsh so depth pass lighting matches
    vec3 normal   = normalize(cross(dFdy(worldPos), dFdx(worldPos)));
    float NdotL   = clamp(dot(normal, normalize(vec3(0.4, 0.9, 0.3))), 0.0, 1.0);
    float faceDark = mix(0.70, 1.0, NdotL);

    color.rgb *= faceDark * vertexAO;

    fragColor = apply_fog(color,
        sphericalVertexDistance, cylindricalVertexDistance,
        FogEnvironmentalStart, FogEnvironmentalEnd,
        FogRenderDistanceStart, FogRenderDistanceEnd,
        FogColor);
}
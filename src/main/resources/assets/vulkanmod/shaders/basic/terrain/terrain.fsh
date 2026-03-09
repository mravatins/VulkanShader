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
    vec4 Light0_Direction;
    vec4 Light1_Direction;
    mat4 LightSpaceMat;
};

layout(binding = 4) uniform sampler2D ShadowSampler;

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;
layout(location = 2) in float sphericalVertexDistance;
layout(location = 3) in float cylindricalVertexDistance;
layout(location = 4) in vec3 worldPos;

layout(location = 0) out vec4 fragColor;



float computeShadow(vec3 pos, vec3 normal, vec3 lightDir) {
    float biasMultiplier = sqrt(1.0 - clamp(dot(normal, lightDir), 0.0, 1.0));
    vec3 biasedPos = pos + normal * 0.07 * biasMultiplier + lightDir * 0.01;
    vec4 lightSpacePos = LightSpaceMat * vec4(biasedPos, 1.0);
    vec3 projCoords = lightSpacePos.xyz / lightSpacePos.w;
    vec2 shadowCoords = projCoords.xy * 0.5 + 0.5;
    float currentDepth = projCoords.z;

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

    // Reconstruct world-space normal for shadow bias (avoids acne on angled surfaces)
    vec3 normal = normalize(cross(dFdy(worldPos), dFdx(worldPos)));

    float shadow = computeShadow(worldPos, normal, Light0_Direction.xyz);
    // vertexColor already encodes Minecraft's lightmap; just attenuate lit areas by shadow
    color.rgb *= 1.0 - 0.5 * shadow;

    // --- ACES Tone Mapping ---
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    color.rgb = clamp((color.rgb * (a * color.rgb + b)) / (color.rgb * (c * color.rgb + d) + e), 0.0, 1.0);

    // --- Color Grading ---
    color.rgb = (color.rgb - 0.5) * 1.1 + 0.5;
    float luma = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    color.rgb = mix(vec3(luma), color.rgb, 1.25);

    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

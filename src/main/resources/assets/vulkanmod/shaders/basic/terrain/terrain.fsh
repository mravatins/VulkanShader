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
layout(location = 5) in float vertexAO;

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

    float pcfDepth   = texture(ShadowSampler, shadowCoords).r;
    float shadowDist = max(currentDepth - pcfDepth, 0.0);
    float softness   = clamp(shadowDist * 80.0, 0.0, 1.0);

    vec2  texelSize = vec2(1.0 / 2048.0);
    float spread    = softness * 4.0;

    float s0 = (currentDepth > texture(ShadowSampler, shadowCoords + vec2( texelSize.x,  texelSize.y) * spread).r) ? 1.0 : 0.0;
    float s1 = (currentDepth > texture(ShadowSampler, shadowCoords + vec2(-texelSize.x,  texelSize.y) * spread).r) ? 1.0 : 0.0;
    float s2 = (currentDepth > texture(ShadowSampler, shadowCoords + vec2( texelSize.x, -texelSize.y) * spread).r) ? 1.0 : 0.0;
    float s3 = (currentDepth > texture(ShadowSampler, shadowCoords + vec2(-texelSize.x, -texelSize.y) * spread).r) ? 1.0 : 0.0;

    return (s0 + s1 + s2 + s3) * 0.25;
}

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    if (color.a < AlphaCutout) discard;

    vec3 normal   = normalize(cross(dFdy(worldPos), dFdx(worldPos)));
    vec3 lightDir = normalize(Light0_Direction.xyz);

    float shadow = computeShadow(worldPos, normal, lightDir);

    float NdotL    = clamp(dot(normal, lightDir), 0.0, 1.0);
    float faceDark = mix(0.70, 1.0, NdotL);

    color.rgb *= faceDark * (1.0 - 0.5 * shadow) * vertexAO;

    // --- Foliage translucency ---
    // Detect leaf-like surfaces by green dominance in the final color
    float greenDominance = color.g - max(color.r, color.b);
    float isLeaf         = clamp(greenDominance * 4.0, 0.0, 1.0);

    // Wrap lighting — bleeds NdotL past 0 so back-lit faces glow through
    float wrapNdotL  = clamp((dot(normal, lightDir) + 0.5) / 1.5, 0.0, 1.0);
    // Bright lit faces get a bonus on top too — light punching through
    float frontLight = clamp((dot(normal, lightDir) - 0.3) / 0.7, 0.0, 1.0);
    float leafLight  = max(wrapNdotL, frontLight * 1.4);

    // Warm yellow-green scatter — tinted like sunlight through a leaf
    vec3 scatterColor = vec3(0.38, 0.58, 0.08) * leafLight * (1.0 - shadow * 0.6) * 0.55;
    color.rgb += scatterColor * isLeaf;

    // --- Pre-exposure ---
    color.rgb *= 0.92;

    // --- ACES Tone Mapping ---
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    color.rgb = clamp((color.rgb * (a * color.rgb + b)) / (color.rgb * (c * color.rgb + d) + e), 0.0, 1.0);

    // --- Color Grading ---
    color.rgb = (color.rgb - 0.5) * 1.15 + 0.5;
    float luma = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    color.rgb = mix(vec3(luma), color.rgb, 1.55);
    color.rgb *= vec3(1.04, 1.02, 0.97);

    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
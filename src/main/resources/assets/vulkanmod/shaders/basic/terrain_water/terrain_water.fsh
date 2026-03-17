#version 450

#include "fog.glsl"

layout(binding = 1) uniform UBO {
    vec4  FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
    float AlphaCutout;
    float Time;
    vec4  Light0_Direction;
    mat4  ModelViewMat;
    mat4  ProjMat;
    vec2  ScreenSize;
    mat4  LightSpaceMat;
};

layout(binding = 2) uniform sampler2D Sampler0;
layout(binding = 3) uniform sampler2D SceneColor;
layout(binding = 4) uniform sampler2D SceneDepth;
layout(binding = 6) uniform sampler2D ShadowSampler;

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;
layout(location = 2) in float sphericalVertexDistance;
layout(location = 3) in float cylindricalVertexDistance;
layout(location = 4) in vec3 worldPos;
layout(location = 5) in vec2 worldXZ;

layout(location = 0) out vec4 fragColor;

float depth_delta(float sceneDepth, float waterDepth) {
    return max(sceneDepth - waterDepth, 0.0);
}

float projected_depth(vec4 clipPos) {
    return clipPos.z / clipPos.w * 0.5 + 0.5;
}

float computeShadow(vec3 pos, vec3 normal, vec3 lightDir) {
    float biasMultiplier = sqrt(1.0 - clamp(dot(normal, lightDir), 0.0, 1.0));
    vec3 biasedPos = pos + normal * 0.05 * biasMultiplier + lightDir * 0.008;
    vec4 lightSpacePos = LightSpaceMat * vec4(biasedPos, 1.0);
    vec3 projCoords = lightSpacePos.xyz / lightSpacePos.w;
    vec2 shadowCoords = projCoords.xy * 0.5 + 0.5;
    float currentDepth = projCoords.z;

    if (shadowCoords.x < 0.0 || shadowCoords.x > 1.0 ||
        shadowCoords.y < 0.0 || shadowCoords.y > 1.0 ||
        currentDepth < 0.0 || currentDepth > 1.0) return 0.0;

    vec2 texelSize = vec2(1.0 / 2048.0);
    float s0 = currentDepth > texture(ShadowSampler, shadowCoords + vec2( texelSize.x,  texelSize.y)).r ? 1.0 : 0.0;
    float s1 = currentDepth > texture(ShadowSampler, shadowCoords + vec2(-texelSize.x,  texelSize.y)).r ? 1.0 : 0.0;
    float s2 = currentDepth > texture(ShadowSampler, shadowCoords + vec2( texelSize.x, -texelSize.y)).r ? 1.0 : 0.0;
    float s3 = currentDepth > texture(ShadowSampler, shadowCoords + vec2(-texelSize.x, -texelSize.y)).r ? 1.0 : 0.0;

    return (s0 + s1 + s2 + s3) * 0.25;
}

void main() {
    vec4 waterSample = texture(Sampler0, texCoord0);
    if (waterSample.a * vertexColor.a < AlphaCutout) discard;

    float nearField = 1.0 - smoothstep(FogRenderDistanceStart * 0.20, FogRenderDistanceEnd * 0.85, cylindricalVertexDistance);
    float mediumField = 1.0 - smoothstep(FogRenderDistanceStart * 0.10, FogRenderDistanceEnd * 0.55, cylindricalVertexDistance);

    // --- Wave normals: 3 layers for organic motion ---
    // FIX 5: increased amplitudes so fresnel/normal actually varies
    vec2 wave1 = vec2(sin(worldXZ.x * 2.4 + Time * 0.8),  cos(worldXZ.y * 2.0 + Time * 0.6)) * 0.032;
    vec2 wave2 = vec2(sin(worldXZ.y * 3.1 - Time * 0.5),  cos(worldXZ.x * 2.7 + Time * 0.7)) * 0.020;
    vec2 wave3 = vec2(sin(worldXZ.x * 1.1 + Time * 0.25), cos(worldXZ.y * 0.9 - Time * 0.2)) * 0.012;
    vec2 waveDist = (wave1 + wave2 + wave3) * mix(0.35, 1.0, nearField);
    vec3 normal = normalize(vec3(waveDist.x, 1.0, waveDist.y));
    vec3 lightDir = normalize(Light0_Direction.xyz);
    float shadow = computeShadow(worldPos, normal, lightDir);
    float waterShadow = 1.0 - shadow * 0.42;
    float daylight = clamp(lightDir.y * 0.85 + 0.15, 0.0, 1.0);
    float skyAmbient = clamp(dot(FogColor.rgb, vec3(0.2126, 0.7152, 0.0722)) * 1.35, 0.08, 0.75);
    float nightFactor = 1.0 - daylight;

    // FIX 1: viewDir points FROM surface TOWARD camera (negate worldPos which is cam-relative)
    vec3 viewDir    = normalize(-worldPos);
    vec3 reflectDir = reflect(-viewDir, normal);

    // FIX 1 (cont): dot product now correct — viewDir and normal both point "up/toward cam"
    float NdotV = max(dot(viewDir, normal), 0.0);
    float fresnel = mix(0.08, 0.90, pow(1.0 - NdotV, 4.2));

    // --- Screen-space reflection ---
    // FIX 2: project a world-space offset point, not a direction vector
    // We march a small step along reflectDir from the surface and project that point
    vec3 reflSamplePoint = worldPos + reflectDir * 3.5;
    vec4 reflClip = ProjMat * ModelViewMat * vec4(reflSamplePoint, 1.0); // w=1, valid point
    vec2 reflUV   = (reflClip.xy / reflClip.w) * vec2(0.5, -0.5) + 0.5;
    reflUV       += waveDist * (0.10 + 0.12 * mediumField); // calmer in the distance
    vec2 edgeFade = smoothstep(0.0, 0.06, min(reflUV, 1.0 - reflUV));
    float edgeMask = edgeFade.x * edgeFade.y;
    float reflectedDepth = projected_depth(reflClip);
    float sceneReflDepth = texture(SceneDepth, clamp(reflUV, 0.0, 1.0)).r;
    float reflectionDelta = abs(sceneReflDepth - reflectedDepth);
    float reflectionHit = 1.0 - smoothstep(0.0, 0.080, reflectionDelta);
    float reflectionMask = edgeMask * mix(0.45, 1.0, reflectionHit) * float(reflClip.w > 0.0);

    vec3 fallbackRefl = mix(vec3(0.04, 0.06, 0.10), vec3(0.22, 0.42, 0.72), daylight);
    vec3 reflectionSample = texture(SceneColor, clamp(reflUV, 0.0, 1.0)).rgb;
    vec3 reflection   = mix(fallbackRefl, reflectionSample * mix(0.78, 1.16, daylight), reflectionMask);

    // --- Refraction: sample SceneColor slightly distorted for underbody look ---
    vec2 screenUV  = gl_FragCoord.xy / ScreenSize;
    vec2 refractUV = screenUV + waveDist * (0.008 + 0.020 * nearField);
    vec3 refraction = texture(SceneColor, clamp(refractUV, 0.0, 1.0)).rgb;
    float sceneDepth = texture(SceneDepth, clamp(refractUV, 0.0, 1.0)).r;
    float waterDepth = gl_FragCoord.z;
    float thickness = depth_delta(sceneDepth, waterDepth);
    float thicknessCue = clamp(thickness * 120.0, 0.0, 1.0);
    float shallowEdge = 1.0 - smoothstep(0.010, 0.070, thickness);
    float contactFade = 1.0 - smoothstep(0.030, 0.160, thickness);

    // --- Sun specular (Blinn-Phong) ---
    // FIX 6: added specular highlight, missing from original
    vec3 sunDir     = normalize(vec3(0.4, 0.9, 0.3)); // tunable sun direction
    vec3 halfVec    = normalize(viewDir + sunDir);
    float spec      = pow(max(dot(normal, halfVec), 0.0), 72.0);
    vec3 specColor  = vec3(1.0, 0.98, 0.92) * spec * mix(0.06, 0.50, fresnel) * mix(0.18, 1.0, daylight);

    // --- Water base color ---
    vec3 shallowColor = vec3(0.20, 0.50, 0.78);
    vec3 midColor     = vec3(0.10, 0.32, 0.60);
    vec3 deepColor    = vec3(0.04, 0.16, 0.36);
    vec3 waterBase    = mix(shallowColor, midColor, thicknessCue);
    waterBase = mix(waterBase, deepColor, thicknessCue * thicknessCue);
    waterBase += vec3(0.05, 0.10, 0.12) * shallowEdge;

    waterBase *= mix(vec3(1.0), vertexColor.rgb, 0.45);
    waterBase *= mix(0.55 + skyAmbient * 0.35, 1.0, daylight);

    vec3 absorption = mix(vec3(0.92, 0.97, 1.0), vec3(0.68, 0.82, 0.94), thicknessCue);
    vec3 transmitted = refraction * absorption;
    vec3 waterColor = mix(transmitted * 0.40 + waterBase * 0.60, waterBase, fresnel);
    waterColor = mix(waterColor, waterBase, contactFade * 0.20);
    waterColor *= waterShadow;
    waterColor *= mix(0.72 + skyAmbient * 0.28, 1.0, daylight);

    float sparkle = pow(clamp(dot(normal, normalize(vec3(0.25, 0.95, 0.18))), 0.0, 1.0), 18.0);
    waterColor += vec3(0.05, 0.08, 0.10) * sparkle * nearField * mix(0.10, 1.0, daylight);

    // Use SSR only as a grazing-angle enhancement. Overhead views should stay dominated
    // by water body color/refraction; otherwise the current screen sample looks like a duplicate world.
    float grazing = smoothstep(0.22, 0.82, 1.0 - NdotV);
    float reflectionStrength = clamp(fresnel * 1.12 + 0.08, 0.0, 1.0) * grazing * mix(0.88, 1.0, reflectionHit) * mix(0.55, 1.0, mediumField);
    vec3 finalColor = mix(waterColor, reflection, reflectionStrength);
    finalColor = mix(finalColor, finalColor * waterShadow, 0.55);
    finalColor = mix(finalColor, finalColor * (0.78 + skyAmbient * 0.22), nightFactor * 0.65);

    // Add specular on top
    finalColor += specColor * mix(0.35, 1.0, nearField) * mix(0.82, 1.0, 1.0 - shadow);

    float shoreFoam = smoothstep(0.008, 0.030, thickness) * (1.0 - smoothstep(0.030, 0.080, thickness));
    finalColor += vec3(0.16, 0.18, 0.18) * shoreFoam * (0.30 + 0.70 * fresnel) * nearField;
    finalColor -= waterBase * (contactFade * 0.10);

    float alpha = mix(0.62, 0.94, fresnel);
    alpha = mix(alpha, 0.86, thicknessCue * 0.65);
    alpha = clamp(alpha, 0.58, 0.96);

    fragColor = apply_fog(
        vec4(finalColor, alpha),
        sphericalVertexDistance,
        cylindricalVertexDistance,
        FogEnvironmentalStart,
        FogEnvironmentalEnd,
        FogRenderDistanceStart,
        FogRenderDistanceEnd,
        FogColor
    );
}

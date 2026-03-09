#version 450

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
    mat4  ModelViewMat;
    mat4  ProjMat;
    vec2  ScreenSize;
};

layout(binding = 2) uniform sampler2D Sampler0;
layout(binding = 3) uniform sampler2D SceneColor;

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;
layout(location = 2) in float sphericalVertexDistance;
layout(location = 3) in float cylindricalVertexDistance;
layout(location = 4) in vec3 worldPos;
layout(location = 5) in vec2 worldXZ;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 waterSample = texture(Sampler0, texCoord0);
    if (waterSample.a * vertexColor.a < AlphaCutout) discard;

    // Subtle wave normal from two scrolling sine layers
    vec2 wave1 = vec2(sin(worldXZ.x * 1.2 + Time * 0.8), cos(worldXZ.y * 1.0 + Time * 0.6)) * 0.012;
    vec2 wave2 = vec2(sin(worldXZ.y * 1.7 - Time * 0.5), cos(worldXZ.x * 1.4 + Time * 0.7)) * 0.008;
    vec2 waveDist = wave1 + wave2;
    vec3 normal = normalize(vec3(waveDist.x, 1.0, waveDist.y));

    vec3 viewDir    = normalize(worldPos);
    vec3 reflectDir = reflect(viewDir, normal);

    // Fresnel — more reflection at grazing angles
    float fresnel = mix(0.1, 0.7, pow(1.0 - max(dot(-viewDir, normal), 0.0), 3.0));

    // Reflection
    vec3 reflection = vec3(0.02, 0.12, 0.45);
    if (reflectDir.y > 0.0) {
        vec4 reflClip = ProjMat * ModelViewMat * vec4(reflectDir, 0.0);
        if (reflClip.w > 0.0) {
            vec2 reflUV   = reflClip.xy / reflClip.w * vec2(0.5, -0.5) + 0.5;
            // Apply wave distortion to reflection UV
            reflUV += waveDist * 0.3;
            vec2 edgeFade = smoothstep(0.0, 0.05, min(reflUV, 1.0 - reflUV));
            reflection    = mix(reflection, texture(SceneColor, clamp(reflUV, 0.0, 1.0)).rgb, edgeFade.x * edgeFade.y);
        }
    }

    // Deep blue water base
    vec3 deepBlue   = vec3(0.02, 0.12, 0.45);
    vec3 waterColor = mix(waterSample.rgb, deepBlue, 0.6);

    // Blend water color with reflection via Fresnel
    vec3 finalColor = mix(waterColor, reflection, fresnel);

    // Translucent — alpha lets you see through slightly
    float alpha = mix(0.55, 0.85, fresnel);

    fragColor = vec4(finalColor, alpha);
}
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

    // --- Wave normals: 3 layers for organic motion ---
    // FIX 5: increased amplitudes so fresnel/normal actually varies
    vec2 wave1 = vec2(sin(worldXZ.x * 1.2 + Time * 0.8),  cos(worldXZ.y * 1.0 + Time * 0.6)) * 0.045;
    vec2 wave2 = vec2(sin(worldXZ.y * 1.7 - Time * 0.5),  cos(worldXZ.x * 1.4 + Time * 0.7)) * 0.030;
    vec2 wave3 = vec2(sin(worldXZ.x * 0.4 + Time * 0.25), cos(worldXZ.y * 0.3 - Time * 0.2)) * 0.020; // slow large-scale swell
    vec2 waveDist = wave1 + wave2 + wave3;
    vec3 normal = normalize(vec3(waveDist.x, 1.0, waveDist.y));

    // FIX 1: viewDir points FROM surface TOWARD camera (negate worldPos which is cam-relative)
    vec3 viewDir    = normalize(-worldPos);
    vec3 reflectDir = reflect(-viewDir, normal);

    // FIX 1 (cont): dot product now correct — viewDir and normal both point "up/toward cam"
    float NdotV = max(dot(viewDir, normal), 0.0);
    float fresnel = mix(0.08, 0.85, pow(1.0 - NdotV, 4.0));

    // --- Screen-space reflection ---
    // FIX 2: project a world-space offset point, not a direction vector
    // We march a small step along reflectDir from the surface and project that point
    vec3 reflSamplePoint = worldPos + reflectDir * 2.0;
    vec4 reflClip = ProjMat * ModelViewMat * vec4(reflSamplePoint, 1.0); // w=1, valid point
    vec2 reflUV   = (reflClip.xy / reflClip.w) * vec2(0.5, -0.5) + 0.5;
    reflUV       += waveDist * 0.25; // wave distortion on reflection
    vec2 edgeFade = smoothstep(0.0, 0.06, min(reflUV, 1.0 - reflUV));
    float edgeMask = edgeFade.x * edgeFade.y;

    vec3 fallbackRefl = vec3(0.04, 0.18, 0.42); // sky-ish fallback when off screen
    vec3 reflection   = mix(fallbackRefl, texture(SceneColor, clamp(reflUV, 0.0, 1.0)).rgb, edgeMask);

    // --- Refraction: sample SceneColor slightly distorted for underbody look ---
    vec2 screenUV  = gl_FragCoord.xy / ScreenSize;
    vec2 refractUV = screenUV + waveDist * 0.04;
    vec3 refraction = texture(SceneColor, clamp(refractUV, 0.0, 1.0)).rgb;

    // --- Sun specular (Blinn-Phong) ---
    // FIX 6: added specular highlight, missing from original
    vec3 sunDir     = normalize(vec3(0.4, 0.9, 0.3)); // tunable sun direction
    vec3 halfVec    = normalize(viewDir + sunDir);
    float spec      = pow(max(dot(normal, halfVec), 0.0), 96.0);
    vec3 specColor  = vec3(1.0, 0.98, 0.92) * spec * 0.6;

    // --- Water base color ---
    // Shallow teal vs deep blue based on depth cue from vertexColor brightness
    vec3 shallowColor = vec3(0.05, 0.38, 0.42);
    vec3 deepColor    = vec3(0.02, 0.10, 0.38);
    float depthCue    = 1.0 - vertexColor.a; // alpha encodes some depth in vanilla MC
    vec3 waterBase    = mix(shallowColor, deepColor, clamp(depthCue, 0.0, 1.0));

    // FIX 3: apply biome tint from vertexColor.rgb (was completely ignored before)
    waterBase *= vertexColor.rgb;

    // Blend refraction into base at low fresnel (looking straight down)
    vec3 waterColor = mix(refraction * 0.55 + waterBase * 0.45, waterBase, fresnel);

    // Blend in reflection at high fresnel (grazing angles)
    vec3 finalColor = mix(waterColor, reflection, fresnel);

    // Add specular on top
    finalColor += specColor;

    // FIX 6 (alpha): tighter, more realistic — more opaque at edges, clearer overhead
    float alpha = mix(0.45, 0.92, fresnel);
    alpha = clamp(alpha * vertexColor.a + 0.1, 0.3, 0.95);

    fragColor = vec4(finalColor, alpha);
}
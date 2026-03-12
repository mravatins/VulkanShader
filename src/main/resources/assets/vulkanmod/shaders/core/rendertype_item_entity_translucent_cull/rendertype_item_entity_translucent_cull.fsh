#version 330

uniform sampler2D Sampler0; // entity texture
uniform sampler2D Sampler1; // overlay / damage flash

// Shadow map — bound to slot 3 by VulkanShader before every entity draw
uniform sampler2D Sampler3;

layout(std140) uniform Fog {
    vec4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
};

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 texCoord1;
in vec2 texCoord2;
in vec4 shadowPos;

out vec4 fragColor;

float linear_fog_value(float dist, float start, float end) {
    if (dist <= start) return 0.0;
    if (dist >= end)   return 1.0;
    return (dist - start) / (end - start);
}

vec4 apply_fog(vec4 color, float spherical, float cylindrical) {
    float fog = max(
        linear_fog_value(spherical,    FogEnvironmentalStart, FogEnvironmentalEnd),
        linear_fog_value(cylindrical,  FogRenderDistanceStart, FogRenderDistanceEnd)
    );
    return vec4(mix(color.rgb, FogColor.rgb, fog * FogColor.a), color.a);
}

float computeEntityShadow(vec4 lsPos) {
    vec3 proj = lsPos.xyz / lsPos.w;
    vec2 uv   = proj.xy * 0.5 + 0.5;

    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0 ||
        proj.z < 0.0 || proj.z > 1.0)
        return 0.0;

    // Simple 4-tap PCF
    float depth = proj.z;
    vec2 texel  = vec2(1.0 / 4096.0);
    float s0 = (depth > texture(Sampler3, uv + vec2( texel.x,  texel.y)).r) ? 1.0 : 0.0;
    float s1 = (depth > texture(Sampler3, uv + vec2(-texel.x,  texel.y)).r) ? 1.0 : 0.0;
    float s2 = (depth > texture(Sampler3, uv + vec2( texel.x, -texel.y)).r) ? 1.0 : 0.0;
    float s3 = (depth > texture(Sampler3, uv + vec2(-texel.x, -texel.y)).r) ? 1.0 : 0.0;
    return (s0 + s1 + s2 + s3) * 0.25;
}

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;

    // Overlay (damage flash, etc.)
    color *= texture(Sampler1, texCoord1);

    if (color.a < 0.1) discard;

    float shadow = computeEntityShadow(shadowPos);
    color.rgb *= (1.0 - 0.5 * shadow);

    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance);
}

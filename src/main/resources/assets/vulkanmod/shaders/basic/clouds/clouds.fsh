#version 450

#include "fog.glsl"

layout(binding = 1) uniform UBO {
    vec4 ColorModulator;
    float FogCloudsEnd;
};

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in float vertexDistance;
layout(location = 2) in vec3 worldPos;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = vertexColor;

    // Reconstruct face normal from worldPos derivatives
    vec3 normal = normalize(cross(dFdy(worldPos), dFdx(worldPos)));

    // Sun direction — matches terrain shader
    vec3 sunDir = normalize(vec3(0.4, 0.9, 0.3));

    // --- Top/bottom/side shading ---
    // Top face: upward normal gets full brightness
    float topness    = clamp(normal.y, 0.0, 1.0);
    // Bottom face: downward normal gets blue-grey ambient bounce
    float bottomness = clamp(-normal.y, 0.0, 1.0);
    // Side faces: sun-facing sides get a warm tint
    float sideLight  = clamp(dot(normal, sunDir), 0.0, 1.0);

    // Base cloud color — bright white top, blue-grey bottom, warm sides
    vec3 topColor    = vec3(1.00, 1.00, 1.00);           // pure white
    vec3 bottomColor = vec3(0.62, 0.68, 0.78);           // blue-grey underside
    vec3 sideColor   = mix(vec3(0.78, 0.80, 0.85),       // shadowed side
                           vec3(0.95, 0.94, 0.90),        // sun-facing side
                           sideLight);

    // Blend based on face orientation
    vec3 shadedColor = topColor    * topness
                     + bottomColor * bottomness
                     + sideColor   * (1.0 - topness - bottomness);

    // Multiply into vertex color so ColorModulator/time-of-day tinting still works
    color.rgb *= shadedColor;

    // --- Edge softening ---
    // Vertex alpha carries cloud shape — apply a slight gamma curve to soften hard edges
    float edgeAlpha = pow(color.a, 0.75);

    // --- Fog fade ---
    float fogFade = 1.0 - linear_fog_value(vertexDistance, 0, FogCloudsEnd);

    color.a = edgeAlpha * fogFade;

    // Subtle distance-based desaturation so far clouds feel atmospheric
    float luma = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    float distFade = clamp(vertexDistance / FogCloudsEnd, 0.0, 1.0);
    color.rgb = mix(color.rgb, vec3(luma) * 0.9 + 0.1, distFade * 0.5);

    fragColor = color;
}
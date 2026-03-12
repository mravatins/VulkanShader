#version 330

#include "light.glsl"

layout(std140) uniform Lighting {
    vec3 Light0_Direction;
    vec3 Light1_Direction;
};

layout(std140) uniform Fog {
    vec4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
};

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
    float LineWidth;
};

layout(std140) uniform Projection {
    mat4 ProjMat;
};

layout(std140) uniform ShadowBlock {
    mat4 LightSpaceViewMat;
};

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler1; // overlay texture
uniform sampler2D Sampler2; // lightmap

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;
out vec4 shadowPos;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;

    sphericalVertexDistance = length(Position);
    cylindricalVertexDistance = max(length(Position.xz), abs(Position.y));

    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
    lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
    overlayColor  = texelFetch(Sampler1, UV1, 0);
    texCoord0 = UV0;

    // LightSpaceViewMat = LightSpaceMat * InverseViewMat maps view-space -> light space
    shadowPos = LightSpaceViewMat * viewPos;
}

#version 460

layout (binding = 0) uniform UniformBufferObject {
    mat4 LightSpaceMat;
};

layout (push_constant) uniform pushConstant {
    vec3 ModelOffset;
};

#define COMPRESSED_VERTEX

#ifdef COMPRESSED_VERTEX
    layout (location = 0) in ivec4 Position;
    layout (location = 1) in uvec2 UV0;
    layout (location = 2) in uint PackedColor;
#else
    layout (location = 0) in vec3 Position;
    layout (location = 1) in vec4 Color;
    layout (location = 2) in vec2 UV0;
    layout (location = 3) in ivec2 UV2;
    layout (location = 4) in vec3 Normal;
#endif

layout (location = 0) out vec2 texCoord0;

const vec3 POSITION_INV = vec3(1.0 / 2048.0);
const float UV_INV = 1.0 / 32768.0;

vec3 getVertexPosition() {
    const vec3 baseOffset = bitfieldExtract(ivec3(gl_InstanceIndex) >> ivec3(0, 16, 8), 0, 8);
    return fma(Position.xyz, POSITION_INV, ModelOffset + baseOffset);
}

void main() {
    const vec3 pos = getVertexPosition();
    gl_Position = LightSpaceMat * vec4(pos, 1.0);
    texCoord0 = UV0 * UV_INV;
}

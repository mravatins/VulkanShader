#version 460

layout(binding = 1) uniform FragUBO {
    float AlphaCutout;
};

layout(binding = 2) uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;

void main() {
    if (texture(Sampler0, texCoord0).a < AlphaCutout) discard;
}

#version 450

layout(binding = 0) uniform UniformBufferObject {
   mat4 MVP;
   vec3 ModelOffset;
};

layout(binding = 1) uniform UBO {
    vec4 ColorModulator;
    float FogCloudsEnd;
};

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out float vertexDistance;
layout(location = 2) out vec3 worldPos;

void main() {
    gl_Position = MVP * vec4(Position, 1.0);
    vec3 viewPos = Position + ModelOffset;
    vertexDistance = length(viewPos.xyz);
    worldPos = viewPos;

    vertexColor = Color * ColorModulator;
}
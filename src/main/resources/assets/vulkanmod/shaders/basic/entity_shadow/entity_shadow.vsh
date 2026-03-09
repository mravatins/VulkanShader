#version 450

layout(binding = 0) uniform UBO {
    mat4 LightSpaceMat;
};

layout(location = 0) in vec3 Position;

void main() {
    gl_Position = LightSpaceMat * vec4(Position, 1.0);
}

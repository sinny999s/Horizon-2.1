#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D Sampler0;

layout(std140) uniform UBO {
    vec2 u_HalfTexelSize;
    float u_Offset;
};

layout(location = 0) in vec2 uv;

layout(location = 0) out vec4 color;

void main() {
    color = (
        texture(Sampler0, uv + vec2(- u_HalfTexelSize.x * 2, 0) * u_Offset) +
        texture(Sampler0, uv + vec2(- u_HalfTexelSize.x, u_HalfTexelSize.y) * u_Offset) * 2 +
        texture(Sampler0, uv + vec2(0, u_HalfTexelSize.y * 2) * u_Offset) +
        texture(Sampler0, uv + u_HalfTexelSize * u_Offset) * 2 +
        texture(Sampler0, uv + vec2(u_HalfTexelSize.x * 2, 0) * u_Offset) +
        texture(Sampler0, uv + vec2(u_HalfTexelSize.x, -u_HalfTexelSize.y) * u_Offset) * 2 +
        texture(Sampler0, uv + vec2(0, -u_HalfTexelSize.y * 2) * u_Offset) +
        texture(Sampler0, uv - u_HalfTexelSize * u_Offset) * 2
    ) / 12;
    color.a = 1;
}

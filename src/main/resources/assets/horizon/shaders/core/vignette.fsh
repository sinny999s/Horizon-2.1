#version 330
#extension GL_ARB_separate_shader_objects : require

// The damage-tint vignette: colored_texture over the whole screen (v down), one colour.
uniform sampler2D Sampler0;

layout(std140) uniform UBO {
    vec4 uColor;
};

layout(location = 0) in vec2 uv;

layout(location = 0) out vec4 color;

void main() {
    color = texture(Sampler0, vec2(uv.x, 1.0 - uv.y)) * uColor;
}

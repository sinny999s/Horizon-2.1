#version 330
#extension GL_ARB_separate_shader_objects : require

// Canvas.blurBox's vertex shader. TexCoord samples the blurred screen under this pixel.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;

layout(location = 0) out vec2 FragCoord;
layout(location = 1) out vec2 TexCoord;

void main() {
    FragCoord = UV0;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    TexCoord = gl_Position.xy * 0.5 + 0.5;
}

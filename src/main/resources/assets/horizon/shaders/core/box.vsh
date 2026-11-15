#version 330
#extension GL_ARB_separate_shader_objects : require

// Canvas.box's vertex shader, the same as rect.vsh: the corner coordinate comes in UV0, plus 2 per shape before this one
// in the draw, and the shape's index goes to the fragment shader.
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
layout(location = 1) out vec4 vertexColor;
layout(location = 2) flat out int vShape;

void main() {
    float shape = floor(UV0.x * 0.5);
    vShape = int(shape);
    FragCoord = vec2(UV0.x - 2.0 * shape, UV0.y);
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
}

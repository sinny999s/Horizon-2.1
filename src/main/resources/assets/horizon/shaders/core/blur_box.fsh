#version 330
#extension GL_ARB_separate_shader_objects : require

// Canvas.blurBox, the blurred screen in a rounded rectangle with exactly Canvas.box's shape, so a tint
// box lies right on it. (blurred_rect comes out a unit too far right and down, and fades over two units.) uSize is the
// quad's size: the box plus a one-unit margin all round for the anti-aliased edge. The parameters are one vec4 of the
// draw's Shapes array: size, radius, alpha.
uniform sampler2D Sampler0;

layout(std140) uniform UBO {
    vec4 Shapes[256];
};

layout(location = 0) in vec2 FragCoord;
layout(location = 1) in vec2 TexCoord;
layout(location = 2) in float Fade;
layout(location = 3) flat in int vShape;

layout(location = 0) out vec4 color;

float roundedDistance(vec2 pos, vec2 size, float radius) {
    vec2 v = abs(pos) - size + radius;
    return length(max(v, 0.0)) + min(max(v.x, v.y), 0.0) - radius;
}

void main() {
    vec4 p0 = Shapes[vShape];
    vec2 uSize = p0.xy;
    float uRadius = p0.z;
    float uAlpha = p0.w;

    vec2 pos = (FragCoord - 0.5) * uSize;
    float dist = roundedDistance(pos, uSize * 0.5 - 1.0, uRadius);
    // One screen pixel, in GUI units: the edge fades over it.
    float px = max(fwidth(dist), 0.0001);
    color = vec4(texture(Sampler0, TexCoord).rgb, clamp(0.5 - dist / px, 0.0, 1.0) * uAlpha * Fade);
}

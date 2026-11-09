#version 330
#extension GL_ARB_separate_shader_objects : require

// Canvas.glow and Canvas.radial, a soft light or shadow worked out per pixel instead of stacked boxes,
// whose edges show as rings. Output is premultiplied, with about one colour level of noise (see below): 8 bits only have
// a few levels across a faint gradient, and the noise hides the steps between them.
//
// uRadius >= 0: a rounded box, full out to uInner inside its edge and gone at uOuter outside it. uSize is the quad's
// size: the box plus uOuter all round. uRadius < 0: an ellipse filling the quad, fading linearly from the middle (a CSS
// radial-gradient). uMaskRadius >= 0 cuts it to a rounded box, uMaskCenter from the quad's middle (CSS overflow: hidden
// on a rounded parent, which a scissor can't do).
layout(std140) uniform UBO {
    vec2 uSize;
    float uRadius;
    float uInner;
    float uOuter;
    float uMaskRadius;
    vec2 uMaskCenter;
    vec2 uMaskSize;
};

layout(location = 0) in vec2 FragCoord;
layout(location = 1) in vec4 vertexColor;

layout(location = 0) out vec4 color;

float roundedDistance(vec2 pos, vec2 size, float radius) {
    vec2 v = abs(pos) - size + radius;
    return length(max(v, 0.0)) + min(max(v.x, v.y), 0.0) - radius;
}

// Interleaved gradient noise: evenly spread, with no visible pattern at one level of brightness.
float noise(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

void main() {
    vec2 pos = (FragCoord - 0.5) * uSize;
    float shape;
    if (uRadius < 0.0) {
        shape = 1.0 - clamp(length(pos / (uSize * 0.5)), 0.0, 1.0);
    } else {
        float x = clamp((roundedDistance(pos, uSize * 0.5 - uOuter, uRadius) + uInner) / max(uInner + uOuter, 0.0001), 0.0, 1.0);
        shape = 1.0 - x * x * (3.0 - 2.0 * x);
    }
    if (uMaskRadius >= 0.0) {
        float mask = roundedDistance(pos - uMaskCenter, uMaskSize * 0.5, uMaskRadius);
        shape *= clamp(0.5 - mask / max(fwidth(mask), 0.0001), 0.0, 1.0);
    }
    float a = vertexColor.a * shape;
    vec3 rgb = vertexColor.rgb * a;
    // Centred on the colour, except by black, where it can only go up. None where there's nothing to draw.
    vec3 n = (noise(gl_FragCoord.xy) - 0.5 * min(rgb * 255.0, vec3(1.0))) / 255.0;
    color = vec4(rgb + n * min(a * 64.0, 1.0), a);
}

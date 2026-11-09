#version 330
#extension GL_ARB_separate_shader_objects : require

// Canvas.box, a rounded rectangle as a browser draws one. uSize is the quad's size: the box plus a
// one-unit margin all round for the anti-aliased edge. The border lies inside the box, over the fill, and the fill's
// alpha counts once. With DITHER (gradients, whose few colour levels show as lines) the output is premultiplied, with about
// one level of noise, as in glow.fsh; an opaque one keeps its colours exact.
layout(std140) uniform UBO {
    vec2 uSize;
    float uRadius;
    float uBorderWidth;
    vec4 uBorderColor;
};

layout(location = 0) in vec2 FragCoord;
layout(location = 1) in vec4 vertexColor;

layout(location = 0) out vec4 color;

float roundedDistance(vec2 pos, vec2 size, float radius) {
    vec2 v = abs(pos) - size + radius;
    return length(max(v, 0.0)) + min(max(v.x, v.y), 0.0) - radius;
}

void main() {
    vec2 pos = (FragCoord - 0.5) * uSize;
    float dist = roundedDistance(pos, uSize * 0.5 - 1.0, uRadius);
    // One screen pixel, in GUI units: the edge fades over it.
    float px = max(fwidth(dist), 0.0001);
    float shape = clamp(0.5 - dist / px, 0.0, 1.0);
    vec4 c = vertexColor;
    if (uBorderWidth > 0.0) {
        float ring = 1.0 - clamp(0.5 - (dist + uBorderWidth) / px, 0.0, 1.0);
        float ba = uBorderColor.a * ring;
        float a = ba + c.a * (1.0 - ba);
        vec3 rgb = a > 0.0 ? (uBorderColor.rgb * ba + c.rgb * c.a * (1.0 - ba)) / a : c.rgb;
        c = vec4(rgb, a);
    }
#ifdef DITHER
    float a = c.a * shape;
    vec3 rgb = c.rgb * a;
    float grain = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    vec3 n = (grain - 0.5 * min(rgb * 255.0, vec3(1.0))) / 255.0;
    color = vec4(rgb + n * min(a * 64.0, 1.0) * step(a, 0.996), a);
#else
    color = vec4(c.rgb, c.a * shape);
#endif
}

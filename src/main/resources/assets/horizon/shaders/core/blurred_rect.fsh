#version 330
#extension GL_ARB_separate_shader_objects : require

// The blurred screen inside a rounded rectangle (a radius per corner),
// tinted and bordered.
uniform sampler2D Sampler0;

layout(std140) uniform UBO {
    vec4 uRadius;
    float uBorderWidth;
    vec4 uColor;
    vec4 uBorderColor;
    float uMix;
    vec2 uSize;
    float uAlpha;
};

layout(location = 0) in vec2 FragCoord;
layout(location = 1) in vec2 TexCoord;

layout(location = 0) out vec4 color;

float rdist(vec2 pos, vec2 size, vec4 radius) {
    vec2 r = radius.xy;
    if (pos.x <= 0.0) r = radius.wz;
    float rad = r.x;
    if (pos.y <= 0.0) rad = r.y;

    vec2 v = abs(pos) - size + rad;
    return min(max(v.x, v.y), 0.0) + length(max(v, 0.0)) - rad;
}

void main() {
    vec2 center = uSize * 0.5;
    vec2 fragPos = center - (FragCoord * uSize);
    float dist = rdist(fragPos, center - 1.0, uRadius);

    float smoothedAlpha = 1.0 - smoothstep(-1.0, 1.0, dist);

    if (smoothedAlpha > 0.49 || uBorderWidth == 0.0) {
        vec4 texColor = texture(Sampler0, TexCoord);
        vec4 mixedColor = mix(texColor * uColor, uColor, uMix);
        mixedColor.a *= smoothedAlpha;

        float borderAlpha = 1.0 - smoothstep(uBorderWidth - 1.0, uBorderWidth, abs(dist));

        color = mix(
            vec4(mixedColor.rgb, 0.0),
            mix(mixedColor, vec4(uBorderColor.rgb, uBorderColor.a * borderAlpha), borderAlpha),
            smoothedAlpha * uAlpha
        );
    } else {
        color = vec4(0.0);
    }
}

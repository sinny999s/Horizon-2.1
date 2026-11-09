#version 330
#extension GL_ARB_separate_shader_objects : require

// A texture clipped to a rounded rectangle.
uniform sampler2D Sampler0;

layout(std140) uniform UBO {
    vec2 uSize;
    float uRadius;
    float uAlpha;
    vec4 uColor;
    float uBorderWidth;
    vec4 uBorderColor;
    float uMix;
};

layout(location = 0) in vec2 FragCoord;

layout(location = 0) out vec4 color;

float roundedDistance(vec2 pos, vec2 size, float radius) {
    vec2 v = abs(pos) - size + radius;
    return min(max(v.x, v.y), 0.0) + length(max(v, 0.0)) - radius;
}

void main() {
    vec2 center = uSize * 0.5;
    float dist = roundedDistance(center - (FragCoord * uSize), center - 1.0, uRadius);

    float smoothedAlpha = 1.0 - smoothstep(-1.0, 1.0, dist);

    if (smoothedAlpha > 0.49 || uBorderWidth == 0.0) {
        vec4 texColor = texture(Sampler0, FragCoord) * uColor;
        vec4 mixedColor = mix(texColor, uColor, uMix);
        mixedColor.a *= smoothedAlpha;

        float borderAlpha = 1.0 - smoothstep(uBorderWidth - 2.0, uBorderWidth, abs(dist));

        color = mix(
            vec4(mixedColor.rgb, 0.0),
            mix(mixedColor, vec4(uBorderColor.rgb, uBorderColor.a * borderAlpha), borderAlpha),
            smoothedAlpha * uAlpha
        );
    } else {
        color = vec4(0.0);
    }
}

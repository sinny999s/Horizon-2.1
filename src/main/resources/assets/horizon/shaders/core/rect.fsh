#version 330
#extension GL_ARB_separate_shader_objects : require

// A rounded rectangle with an optional border.
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
    vec2 center = uSize * 0.5;
    vec2 fragPos = center - (FragCoord * uSize);
    float dist = roundedDistance(fragPos, center - 1.0, uRadius);

    float smoothedAlpha = 1.0 - smoothstep(-1.0, 1.0, dist);

    if (smoothedAlpha > 0.49 || uBorderWidth == 0.0) {
        float borderAlpha = 1.0 - smoothstep(uBorderWidth - 1.0, uBorderWidth, abs(dist));
        vec4 finalColor = mix(vertexColor, vec4(uBorderColor.rgb, uBorderColor.a * borderAlpha), borderAlpha);
        color = vec4(finalColor.rgb, finalColor.a * smoothedAlpha * vertexColor.a);
    } else {
        color = vec4(0.0);
    }
}

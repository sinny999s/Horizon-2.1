#version 330
#extension GL_ARB_separate_shader_objects : require

// A rounded rectangle with an optional border. Its parameters are two vec4s of the draw's Shapes array: size, radius,
// border width; border colour.
layout(std140) uniform UBO {
    vec4 Shapes[256];
};

layout(location = 0) in vec2 FragCoord;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) flat in int vShape;

layout(location = 0) out vec4 color;

float roundedDistance(vec2 pos, vec2 size, float radius) {
    vec2 v = abs(pos) - size + radius;
    return length(max(v, 0.0)) + min(max(v.x, v.y), 0.0) - radius;
}

void main() {
    vec4 p0 = Shapes[vShape * 2];
    vec2 uSize = p0.xy;
    float uRadius = p0.z;
    float uBorderWidth = p0.w;
    vec4 uBorderColor = Shapes[vShape * 2 + 1];

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

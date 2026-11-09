#version 330
#extension GL_ARB_separate_shader_objects : require

// Shader boxes, a translucent fill with a 2-pixel solid edge.
uniform sampler2D Sampler0;

layout(std140) uniform UBO {
    vec2 uHalfTexelSize;
    float time;
    int mode;
};

layout(location = 0) in vec2 uv;

layout(location = 0) out vec4 color;

void main() {
    vec4 center = texture(Sampler0, uv);

    if (center.a != 0) {
        if (mode == 0) {
            color = vec4(center.rgb, center.a * 0.75);
        }

        if (mode == 1) {
            float stripe = fract((uv.x + uv.y) * 12.0 + time * 1.5);
            color = vec4(mix(center.rgb, center.rgb * 1.5, smoothstep(0.3, 0.5, stripe) * smoothstep(0.7, 0.5, stripe) * 0.6), center.a * 0.75);
        }

        if (mode == 2) {
            if (int(gl_FragCoord.x / 4.0) % 8 == 0 && int(gl_FragCoord.y / 4.0) % 8 == 0) {
                color = vec4(center.rgb, center.a * 3.0);
            } else {
                color = vec4(center.rgb, center.a * 0.75);
            }
        }

        return;
    }

    for (float x = -2; x <= 2; x++) {
        for (float y = -2; y <= 2; y++) {
            vec4 curColor = texture(Sampler0, uv + vec2(uHalfTexelSize.x * x, uHalfTexelSize.y * y));
            if (curColor.a != 0) {
                color = vec4(curColor.rgb, min(1.0, curColor.a * 3.0));
                return;
            }
        }
    }
    discard;
}

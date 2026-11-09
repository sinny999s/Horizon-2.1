#version 330
#extension GL_ARB_separate_shader_objects : require

// The chams mask filled with one colour, with a soft outline around it.
uniform sampler2D Sampler0;

layout(std140) uniform UBO {
    vec2 uHalfTexelSize;
    vec4 uColor;
    float uLineWidth;
};

layout(location = 0) in vec2 uv;

layout(location = 0) out vec4 color;

void main() {
    vec4 center = texture(Sampler0, uv);

    if (center.a != 0) {
        color = uColor;
        return;
    }

    float alpha = 0;
    for (float x = -2; x <= 2; x++) {
        for (float y = -2; y <= 2; y++) {
            vec4 curColor = texture(Sampler0, uv + vec2(uHalfTexelSize.x * x, uHalfTexelSize.y * y));
            if (curColor.a != 0)
                alpha += max(0, (uLineWidth - sqrt(x * x + y * y)));
        }
    }

    color = vec4(uColor.rgb, alpha);
}

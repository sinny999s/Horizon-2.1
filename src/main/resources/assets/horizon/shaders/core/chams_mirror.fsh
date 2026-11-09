#version 330
#extension GL_ARB_separate_shader_objects : require

// The blurred screen (Sampler1) seen through the chams mask (Sampler0).
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

layout(std140) uniform UBO {
    vec2 uViewOffset;
    vec4 uColor;
    float uBorder;
    vec2 uHalfTexelSize;
    float alphaX;
    float alphaY;
};

layout(location = 0) in vec2 uv;

layout(location = 0) out vec4 color;

void main() {
    vec4 center = texture(Sampler0, uv);

    if (center.a != 0 || uBorder == 0) {
        if (alphaX == 0.0 || alphaY == 0.0) {
            color = texture(Sampler1, uv + uViewOffset) * uColor * center.a;
        } else {
            float factor = (center.x + center.y + center.z) / 3.0;
            color = texture(Sampler1, uv + (vec2(factor * alphaX, factor * alphaY))) * uColor * center.a;
        }
        return;
    }

    float alpha = 0;
    for (float x = -2; x <= 2; x++) {
        for (float y = -2; y <= 2; y++) {
            vec4 curColor = texture(Sampler0, uv + vec2(uHalfTexelSize.x * x, uHalfTexelSize.y * y));
            if (curColor.a != 0)
            alpha += max(0, (2.0 - sqrt(x * x + y * y)));
        }
    }

    color = vec4(uColor.rgb, alpha);
}
